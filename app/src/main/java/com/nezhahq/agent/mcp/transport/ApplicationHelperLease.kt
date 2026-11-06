package com.nezhahq.agent.mcp.transport

import com.nezhahq.agent.core.mcp.McpBusinessError
import com.nezhahq.agent.core.mcp.McpErrorCode
import com.nezhahq.agent.mcp.helper.HelperSessionIdentity
import java.util.concurrent.TimeUnit

/** A process start token is checked again before signaling a numeric process-group ID. */
internal data class ApplicationProcessIdentity(val uid: Int, val stat: HelperSessionIdentity.Snapshot) {
    fun matches(expectedUid: Int, pid: Int): Boolean =
        uid == expectedUid && stat.pid == pid && stat.ownsSession && stat.isLive

    fun sameProcess(other: ApplicationProcessIdentity): Boolean =
        uid == other.uid && stat.pid == other.stat.pid &&
            stat.processGroupId == other.stat.processGroupId && stat.sessionId == other.stat.sessionId &&
            stat.startTimeTicks == other.stat.startTimeTicks && other.stat.isLive
}

/** Injected so API-23 exit/status/signal ordering is tested without Android stubs. */
internal interface ApplicationProcessControl {
    fun identity(pid: Int): ApplicationProcessIdentity?
    fun killGroup(pid: Int)
    fun prepareExitProof(expectedUid: Int) = ApplicationProcessExitFactory(::javaProcessExitProof)
}

internal class ApplicationHelperLease private constructor(
    private val process: Process,
    override val expectedUid: Int,
    private val control: ApplicationProcessControl,
    private val cleanupTicket: HelperProcessCleanup.Ticket,
    private val exitProof: ApplicationProcessExitProof
) : HelperProcessLease {
    override val backend = HelperBackend.APPLICATION
    override val stdout get() = process.inputStream
    override val stdin get() = process.outputStream
    override val stderr get() = process.errorStream
    private val lock = Any()
    private var identity: ApplicationProcessIdentity? = null
    private var closed = false
    internal val cleanupComplete: Boolean get() = cleanupTicket.released

    override fun acceptIdentity(hello: HelperProtocol.Hello) {
        if (hello.uid != expectedUid || hello.effectiveUid != expectedUid || hello.pid <= 1 || hello.sessionId != hello.pid) {
            throw rejectedIdentity()
        }
        synchronized(lock) { if (closed || identity != null) throw rejectedIdentity() }
        val observed = runCatching { control.identity(hello.pid) }.getOrNull()
        if (!running() || observed?.matches(expectedUid, hello.pid) != true) throw rejectedIdentity()
        synchronized(lock) {
            // close may already have destroyed the pre-request helper while /proc was read.
            // No command is sent until acceptance returns, so a late registration must only fail.
            if (closed || identity != null) throw rejectedIdentity()
            if (!runCatching { exitProof.prepareForRequest(observed) }.getOrDefault(false)) throw rejectedIdentity()
            identity = observed
        }
    }

    override fun close() {
        val registered = synchronized(lock) {
            if (closed) return
            closed = true
            identity
        }
        try {
            if (running()) {
                if (registered != null) {
                    val current = runCatching { control.identity(registered.stat.pid) }.getOrNull()
                    // A buffered terminal may outlive the helper by an arbitrary budget wait.
                    // Never signal its cached PID after exit, after identity change, or on an
                    // unreadable /proc record. Check process status again immediately before kill.
                    if (current != null && registered.sameProcess(current) && running()) {
                        runCatching { control.killGroup(registered.stat.pid) }
                    }
                    // No destroy() fallback after acceptance: it could kill the helper before its
                    // control-EOF handler kills descendants. Original control EOF/watchdog remain
                    // the fallback when a fresh numeric group cannot be safely identified.
                }
            }
        } finally {
            // Process pipes may flush or wait for a concurrent REQUEST writer's monitor. Never
            // perform that work on the cancelled coroutine. The pre-launch ticket bounds native
            // resource bundles until their pipes close and the original Process proves exit.
            scheduleCleanup(process, cleanupTicket, exitProof) {
                if (registered == null) runCatching { exitProof.destroyUnaccepted() }
            }
        }
    }

    /** exitValue is public on API 23. Unknown status is not permission to signal an old integer. */
    private fun running(): Boolean = try { process.exitValue(); false }
        catch (_: IllegalThreadStateException) { true }
        catch (_: Exception) { false }

    private fun rejectedIdentity() = HelperFailure(McpBusinessError(
        McpErrorCode.CAPABILITY_UNAVAILABLE, "Helper process identity could not be verified"
    ))

    companion object {
        private const val CLEANUP_EXIT_WAIT_MS = 250L
        private const val CLEANUP_EXIT_POLL_MS = 10L

        fun start(
            expectedUid: Int,
            control: ApplicationProcessControl,
            cleanup: HelperProcessCleanup = HelperProcessResources.cleanup,
            owner: HelperLifecycleOwner? = null,
            launch: () -> Process
        ): ApplicationHelperLease {
            val ticket = cleanup.tryAcquire(owner) ?: throw HelperFailure(McpBusinessError(
                McpErrorCode.BUSY, "MCP helper process cleanup capacity is busy"
            ))
            var process: Process? = null
            var exitProof: ApplicationProcessExitProof? = null
            try {
                val exitFactory = control.prepareExitProof(expectedUid)
                process = launch()
                exitProof = exitFactory.capture(process)
                return ApplicationHelperLease(process, expectedUid, control, ticket, exitProof)
            } catch (failure: Throwable) {
                val started = process
                if (started == null) ticket.releaseUnused() else {
                    val retainedProof = exitProof ?: javaProcessExitProof(started)
                    scheduleCleanup(started, ticket, retainedProof) {
                        runCatching { retainedProof.destroyUnaccepted() }
                    }
                }
                throw failure
            }
        }

        private fun scheduleCleanup(
            process: Process, ticket: HelperProcessCleanup.Ticket, exitProof: ApplicationProcessExitProof,
            beforeClose: () -> Unit
        ) {
            var closeCapturedPipes: (() -> Boolean)? = null
            ticket.scheduleUntil {
                if (closeCapturedPipes == null) {
                    beforeClose()
                    val control = capturePipeClose { process.outputStream }
                    val output = capturePipeClose { process.inputStream }
                    val error = capturePipeClose { process.errorStream }
                    closeCapturedPipes = {
                        val controlClosed = control()
                        val outputClosed = output()
                        val errorClosed = error()
                        controlClosed && outputClosed && errorClosed
                    }
                }
                val pipesClosed = closeCapturedPipes!!.invoke()
                // Sending SIGKILL or control EOF is not proof of exit. exitValue is nonblocking
                // on API 23 and stays bound to this Process even if its old numeric PID is reused.
                val exited = awaitExit(exitProof)
                pipesClosed && exited
            }
        }

        private fun awaitExit(exitProof: ApplicationProcessExitProof): Boolean {
            val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CLEANUP_EXIT_WAIT_MS)
            while (!Thread.currentThread().isInterrupted) {
                try {
                    if (exitProof.confirmExited()) return true
                } catch (_: IllegalThreadStateException) {
                    // The native reaper may publish an otherwise completed kill shortly later.
                } catch (_: Exception) {
                    return false
                }
                val remaining = until - System.nanoTime()
                if (remaining <= 0) return false
                try {
                    TimeUnit.NANOSECONDS.sleep(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(CLEANUP_EXIT_POLL_MS)))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return false
        }

        /** A failed close retries the captured object; successful closes and getters run once. */
        private fun capturePipeClose(get: () -> AutoCloseable): () -> Boolean {
            val pipe = runCatching(get).getOrNull()
            var closed = false
            return {
                if (!closed && pipe != null) closed = runCatching { pipe.close() }.isSuccess
                closed
            }
        }
    }
}
