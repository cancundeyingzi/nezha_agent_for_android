package com.nezhahq.agent.mcp.privileged

import com.nezhahq.agent.mcp.transport.HelperBackend
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object PrivilegedHelperReconciler {
    private val shared = PrivilegedProbeCoordinator()
    fun retain(lease: PrivilegedHelperLease) = shared.retain(lease)
    fun release(lease: PrivilegedHelperLease) = shared.release(lease)
    fun authorized(permit: PrivilegedHelperAuthority.Permit) = shared.authorized(permit)
}

internal enum class MaintenanceSlotState { IDLE, RUNNING, UNKNOWN }

/** One maintenance resource bundle, including unknown native processes and outstanding IO. */
internal class PrivilegedProbeCoordinator(
    private val onCompleted: (MaintenanceSlotState) -> Unit = {},
    private val exitWaitMillis: Long = 300
) : AutoCloseable {
    private val queueLock = Any()
    private val retained = ArrayDeque<PrivilegedHelperLease>()
    private val working = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile var state = MaintenanceSlotState.IDLE
        private set
    // Keep the sole owner, its Process and captured pipes even after Binder makes exit unknown.
    @Volatile private var held: ProbeRun? = null
    private val worker = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1)) { task ->
        Thread(task, "mcp-helper-reconcile").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }
    private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "mcp-helper-probe-timeout").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun retain(lease: PrivilegedHelperLease) = synchronized(queueLock) {
        if (!retained.contains(lease)) retained.addLast(lease)
    }
    fun release(lease: PrivilegedHelperLease) = synchronized(queueLock) { retained.remove(lease); Unit }

    private fun next(uid: Int): PrivilegedHelperLease? = synchronized(queueLock) {
        repeat(retained.size) {
            val candidate = retained.removeFirst()
            if (!candidate.cleanupComplete) {
                retained.addLast(candidate) // Rotate once per actual attempt, including a failure.
                if (candidate.needsProbe && candidate.canProbe && candidate.expectedUid == uid) return@synchronized candidate
            }
        }
        null
    }

    fun authorized(permit: PrivilegedHelperAuthority.Permit) {
        if (closed.get()) return
        val local = synchronized(queueLock) {
            retained.filter { it.backend == HelperBackend.ROOT && !it.cleanupComplete }
        }
        local.forEach { it.retryLocalExitCheck() }
        if (!working.compareAndSet(false, true)) return
        val candidate = next(permit.expectedUid)
        if (candidate == null) { working.set(false); return }
        val maintenance = try { permit.fork() } catch (_: Exception) { working.set(false); return }
        val run = ProbeRun(candidate, maintenance)
        held = run
        state = MaintenanceSlotState.RUNNING
        try {
            worker.execute {
                val exited = try { run.execute() } catch (_: Throwable) { false }
                maintenance.close()
                val completed = if (exited) MaintenanceSlotState.IDLE else MaintenanceSlotState.UNKNOWN
                if (exited) {
                    held = null
                    state = MaintenanceSlotState.IDLE
                    working.set(false)
                } else state = MaintenanceSlotState.UNKNOWN
                // UNKNOWN deliberately retains working/held until application process recovery.
                runCatching { onCompleted(completed) }
            }
        } catch (_: Exception) {
            maintenance.close()
            held = null // Worker rejected before execute/native launch; there is nothing to reap.
            state = MaintenanceSlotState.IDLE
            working.set(false)
        }
    }

    override fun close() {
        closed.set(true)
        held?.permit?.close()
        worker.shutdownNow()
        deadlines.shutdownNow()
    }

    private inner class ProbeRun(
        val lease: PrivilegedHelperLease,
        val permit: PrivilegedHelperAuthority.Permit
    ) {
        @Volatile private var process: Process? = null
        private var stdout: InputStream? = null
        private var stdin: OutputStream? = null
        private var stderr: InputStream? = null
        private var controlClosed = false
        private var closeSucceeded = true
        private var nativeAttempted = false
        private var launchRequested = false
        private val destructionLock = Any()
        private var timeoutDisabled = false
        private val destroyed = AtomicBoolean(false)
        private val destroyFinished = AtomicBoolean(false)

        fun execute(): Boolean {
            var timeout: java.util.concurrent.ScheduledFuture<*>? = null
            val reply = ByteArray(257)
            var used = 0
            try {
                permit.requireLaunch()
                process = permit.launchProbe(lease.program, lease.acceptedIdentity) {
                    nativeAttempted = true
                    launchRequested = true
                }
                timeout = deadlines.schedule({ destroyOnce(fromTimeout = true) }, 1_500, TimeUnit.MILLISECONDS)
                val owned = process!!
                // Remote stream getters are each called once, and partial capture stays owned.
                stdin = owned.outputStream
                stdout = owned.inputStream
                stderr = owned.errorStream
                controlClosed = true
                try { stdin!!.close() } catch (failure: Exception) { closeSucceeded = false; throw failure }
                while (used < reply.size) {
                    val count = stdout!!.read(reply, used, reply.size - used)
                    if (count < 0) break
                    if (count == 0) throw java.io.IOException("Invalid maintenance stream")
                    used += count
                }
            } catch (_: PrivilegedLaunchNotStarted) {
                nativeAttempted = false
            } catch (_: Exception) {
                // Only fixed proof is interpreted below. Never log raw exception/stream content.
            } finally {
                synchronized(destructionLock) { timeoutDisabled = true }
                timeout?.cancel(false)
                val prefix = "READY:${lease.program.nonce}:${permit.expectedUid}\n".toByteArray(Charsets.US_ASCII)
                val ready = used >= prefix.size && reply.copyOf(prefix.size).contentEquals(prefix)
                val clean = ("READY:${lease.program.nonce}:${permit.expectedUid}\nCLEAN:${lease.program.nonce}\n")
                    .toByteArray(Charsets.US_ASCII)
                if (ready && used == clean.size && reply.copyOf(used).contentEquals(clean)) lease.reconciled()
                // A real UID0 READY with pending cleanup is a business failure, not su denial.
                if (launchRequested && permit.backend == HelperBackend.ROOT && !ready) permit.rejectRoot()
            }

            if (!nativeAttempted) return true
            val naturalExit = !destroyed.get() && exited()
            if (!naturalExit) destroyOnce(fromTimeout = false)
            closePipes()
            val knownExit = naturalExit || (permit.backend == HelperBackend.SHIZUKU && exited())
            // Destroying a local su wrapper does not prove its privileged child exited. A direct
            // Shizuku app_process probe has no child/helper payload and can use its trusted exit.
            return knownExit && closeSucceeded && (!destroyed.get() || destroyFinished.get())
        }

        private fun destroyOnce(fromTimeout: Boolean) {
            val owned = synchronized(destructionLock) {
                if ((fromTimeout && timeoutDisabled) || process == null || !destroyed.compareAndSet(false, true)) return
                process!!
            }
            try { owned.destroy() } catch (_: Exception) { /* UNKNOWN retains the owner. */ }
            finally { destroyFinished.set(true) }
        }

        private fun exited(): Boolean {
            val owned = process ?: return false
            val until = System.nanoTime() + exitWaitMillis * 1_000_000
            do {
                try { owned.exitValue(); return true }
                catch (_: IllegalThreadStateException) { /* Still running, bounded retry. */ }
                catch (_: Exception) { return false }
                if (System.nanoTime() >= until) return false
                try { Thread.sleep(10) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
            } while (true)
        }

        private fun closePipes() {
            fun close(stream: AutoCloseable?) {
                try { stream?.close() } catch (_: Exception) { closeSucceeded = false }
            }
            if (!controlClosed) { controlClosed = true; close(stdin) }
            close(stdout)
            close(stderr)
        }
    }
}
