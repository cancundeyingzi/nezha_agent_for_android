package com.nezhahq.agent.mcp.privileged

import com.nezhahq.agent.core.mcp.McpBusinessError
import com.nezhahq.agent.core.mcp.McpErrorCode
import com.nezhahq.agent.mcp.transport.HelperFailure
import com.nezhahq.agent.mcp.transport.HelperBackend
import com.nezhahq.agent.mcp.transport.HelperProcessCleanup
import com.nezhahq.agent.mcp.transport.HelperProcessLease
import com.nezhahq.agent.mcp.transport.HelperProcessResources
import com.nezhahq.agent.mcp.transport.HelperProtocol
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** One owner captures each remote pipe once. Cleanup never re-enters Process stream getters. */
internal class PrivilegedHelperLease private constructor(
    private val permit: PrivilegedHelperAuthority.Permit,
    internal val program: TrustedHelperProgram,
    private val ticket: HelperProcessCleanup.Ticket
) : HelperProcessLease {
    override val backend = permit.backend
    override val expectedUid = permit.expectedUid
    private val lock = Any()
    private var process: Process? = null
    private var output: InputStream? = null
    private var control: OutputStream? = null
    private var diagnostic: InputStream? = null
    private var registration: AutoCloseable? = null
    private var launchFinished = false
    private var nativeAttempted = false
    private var closed = false
    private val pipesClosed = AtomicBoolean(false)
    private val cleanupScheduled = AtomicBoolean(false)
    @Volatile internal var acceptedIdentity: HelperProtocol.Hello? = null
        private set
    @Volatile private var groupReconciled = false
    @Volatile internal var needsProbe = false
        private set
    internal val cleanupComplete get() = ticket.released
    internal val canProbe get() = acceptedIdentity != null && !groupReconciled

    // CLEAN covers the fixed Shizuku proxy only after its child has sent a verified HELLO. It
    // cannot cover a pending su that has no marker yet, or the local su wrapper outside that group.
    private fun proofCoversBundle() = backend == HelperBackend.SHIZUKU &&
        acceptedIdentity != null && groupReconciled

    override val stdout: InputStream get() = output ?: throw unavailable()
    override val stderr: InputStream get() = diagnostic ?: throw unavailable()
    override val stdin = object : OutputStream() {
        private fun ready(): OutputStream {
            permit.requireFast()
            synchronized(lock) { if (closed) throw unavailable() }
            return control ?: throw unavailable()
        }
        override fun write(value: Int) = ready().write(value)
        override fun write(bytes: ByteArray, offset: Int, length: Int) = ready().write(bytes, offset, length)
        override fun flush() = ready().flush()
        // Lease owns the raw control pipe. Never block the caller on a buffered writer's monitor.
        override fun close() = this@PrivilegedHelperLease.close()
    }

    override fun acceptIdentity(hello: HelperProtocol.Hello) {
        try {
            permit.requireLive()
            if (hello.uid != expectedUid || hello.effectiveUid != expectedUid || hello.pid <= 1 ||
                hello.sessionId != hello.pid || hello.startTimeTicks <= 0) throw unavailable()
            synchronized(lock) {
                if (closed || acceptedIdentity != null) throw unavailable()
                acceptedIdentity = hello
            }
            permit.requireLive()
        } catch (failure: Throwable) {
            permit.rejectRoot()
            throw failure
        }
    }

    override fun beforeRequest() = permit.requireLive()

    override fun close() {
        val finished = synchronized(lock) {
            if (closed) return
            closed = true
            launchFinished
        }
        registration?.close()
        if (acceptedIdentity == null && finished && process != null) permit.rejectRoot()
        permit.close()
        if (finished) scheduleCleanup()
    }

    private fun finishLaunch() {
        val revoke = synchronized(lock) { launchFinished = true; closed }
        if (revoke) scheduleCleanup()
    }

    private fun scheduleCleanup() {
        if (!cleanupScheduled.compareAndSet(false, true)) return
        needsProbe = canProbe
        PrivilegedHelperReconciler.retain(this)
        ticket.scheduleUntil {
            if (pipesClosed.compareAndSet(false, true)) {
                // Original control EOF makes HelperMain kill its own UID/session. No App-UID kill,
                // no proxy destroy after HELLO acceptance, and no blocking IO on the caller.
                runCatching { control?.close() }
                runCatching { output?.close() }
                runCatching { diagnostic?.close() }
                // Before any accepted identity/payload, a failed first pipe getter may leave no
                // control channel at all. Destroy the proxy; without HELLO, unknown remains owned.
                if (control == null && acceptedIdentity == null) runCatching { process?.destroy() }
            }
            val owned = process
            var knownExited = owned == null && !nativeAttempted
            if (owned != null) {
                val until = System.nanoTime() + TimeUnitMillis.CLEANUP_WAIT * 1_000_000
                do {
                    knownExited = try { owned.exitValue(); control != null }
                        catch (_: IllegalThreadStateException) { false }
                        catch (_: Exception) { false }
                    if (knownExited || proofCoversBundle()) break
                    Thread.sleep(10)
                } while (System.nanoTime() < until)
            }
            val complete = knownExited || proofCoversBundle()
            needsProbe = !complete && canProbe
            if (complete) PrivilegedHelperReconciler.release(this)
            complete
        }
    }

    internal fun reconciled() {
        if (acceptedIdentity == null) return
        groupReconciled = true
        needsProbe = false
        ticket.retryRetained()
    }

    /** Recheck an already-owned local su only; never launch native work to prove its exit. */
    internal fun retryLocalExitCheck() {
        if (backend == HelperBackend.ROOT && cleanupScheduled.get()) ticket.retryRetained()
    }

    companion object {
        fun start(
            permit: PrivilegedHelperAuthority.Permit,
            program: TrustedHelperProgram,
            cleanup: HelperProcessCleanup = HelperProcessResources.cleanup,
            launch: () -> Process
        ): PrivilegedHelperLease {
            val ticket = cleanup.tryAcquire() ?: run {
                permit.close()
                throw HelperFailure(McpBusinessError(McpErrorCode.BUSY, "MCP helper process cleanup capacity is busy"))
            }
            val lease = PrivilegedHelperLease(permit, program, ticket)
            var attempted = false
            try {
                lease.registration = permit.register(lease::close)
                permit.requireLive()
                attempted = true
                lease.nativeAttempted = true
                lease.process = launch()
                permit.requireLive()
                // Remote getErrorStream creates a fresh relay/PFD on every call in SDK 13.1.5.
                lease.control = lease.process!!.outputStream
                permit.requireFast()
                lease.output = lease.process!!.inputStream
                permit.requireFast()
                lease.diagnostic = lease.process!!.errorStream
                permit.requireLive()
                lease.finishLaunch()
                permit.requireFast()
                return lease
            } catch (failure: Throwable) {
                if (failure is PrivilegedLaunchNotStarted && lease.process == null) lease.nativeAttempted = false
                if (attempted) permit.rejectRoot()
                lease.close()
                lease.finishLaunch()
                throw failure
            }
        }

        private fun unavailable() = PrivilegedHelperAuthority.unavailable()
    }

    private object TimeUnitMillis { const val CLEANUP_WAIT = 500L }
}
