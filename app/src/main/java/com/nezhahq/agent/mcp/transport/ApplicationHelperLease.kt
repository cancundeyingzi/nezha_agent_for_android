package com.nezhahq.agent.mcp.transport

import com.nezhahq.agent.core.mcp.McpBusinessError
import com.nezhahq.agent.core.mcp.McpErrorCode
import com.nezhahq.agent.mcp.helper.HelperSessionIdentity

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
}

internal class ApplicationHelperLease private constructor(
    private val process: Process,
    override val expectedUid: Int,
    private val control: ApplicationProcessControl,
    private val cleanupTicket: HelperProcessCleanup.Ticket
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
            // processes and retains this cleanup ownership until every close attempt has finished.
            cleanupTicket.schedule {
                if (registered == null && running()) runCatching { process.destroy() }
                closePipes(process)
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
        fun start(
            expectedUid: Int,
            control: ApplicationProcessControl,
            cleanup: HelperProcessCleanup = HelperProcessResources.cleanup,
            launch: () -> Process
        ): ApplicationHelperLease {
            val ticket = cleanup.tryAcquire() ?: throw HelperFailure(McpBusinessError(
                McpErrorCode.BUSY, "MCP helper process cleanup capacity is busy"
            ))
            var process: Process? = null
            try {
                process = launch()
                return ApplicationHelperLease(process, expectedUid, control, ticket)
            } catch (failure: Throwable) {
                val started = process
                if (started == null) ticket.releaseUnused() else ticket.schedule {
                    runCatching { started.destroy() }
                    closePipes(started)
                }
                throw failure
            }
        }

        private fun closePipes(process: Process) {
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }
}
