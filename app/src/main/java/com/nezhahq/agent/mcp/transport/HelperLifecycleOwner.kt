package com.nezhahq.agent.mcp.transport

import com.nezhahq.agent.core.mcp.McpBusinessError
import com.nezhahq.agent.core.mcp.McpErrorCode
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** One runtime's queued/native IO and process bundles, independent of coroutine cancellation. */
internal class HelperLifecycleOwner {
    private val lock = Any()
    private val activities = mutableSetOf<Activity>()
    private val tickets = mutableSetOf<HelperProcessCleanup.Ticket>()
    private val pending = MutableStateFlow(0)
    private var closing = false

    fun registerActivity(onDiscard: () -> Unit = {}): Activity = synchronized(lock) {
        if (closing) throw HelperFailure(McpBusinessError(McpErrorCode.BUSY, "MCP helper runtime is closing"))
        Activity(onDiscard).also { activities += it; publishPending() }
    }

    /** Registration precedes native launch; admission closing rejects a late ticket atomically. */
    fun retain(ticket: HelperProcessCleanup.Ticket): Boolean = synchronized(lock) {
        if (closing) false else {
            tickets += ticket
            publishPending()
            true
        }
    }

    fun released(ticket: HelperProcessCleanup.Ticket) = synchronized(lock) {
        tickets -= ticket
        publishPending()
    }

    fun beginShutdown() {
        val queued = synchronized(lock) {
            closing = true
            activities.toList()
        }
        queued.forEach(Activity::discardQueued)
    }

    /** Timeout reports pending ownership. Only real worker completion and cleanup proof release it. */
    suspend fun close(timeoutMillis: Long = 1_000) {
        beginShutdown()
        val retained = synchronized(lock) { tickets.toList() }
        // A failed proof is retained without polling. Retry only this owner's existing processes;
        // never reacquire authority, start a probe, or depend on global capacity pressure.
        retained.forEach { it.retryRetained() }
        if (withTimeoutOrNull(timeoutMillis) { pending.first { it == 0 } } == null) {
            throw HelperShutdownPendingException()
        }
    }

    private fun publishPending() { pending.value = activities.size + tickets.size }

    internal inner class Activity(private val onDiscard: () -> Unit) {
        private val state = AtomicInteger(QUEUED)

        fun start(): Boolean = synchronized(lock) {
            !closing && state.compareAndSet(QUEUED, RUNNING)
        }

        fun discardQueued() {
            if (state.compareAndSet(QUEUED, FINISHED)) {
                release()
                runCatching(onDiscard)
            }
        }

        fun finish() {
            if (state.getAndSet(FINISHED) != FINISHED) release()
        }

        private fun release() = synchronized(lock) {
            activities -= this
            publishPending()
        }
    }

    private companion object {
        const val QUEUED = 0
        const val RUNNING = 1
        const val FINISHED = 2
    }
}

internal class HelperShutdownPendingException :
    IllegalStateException("MCP helper cleanup has not confirmed process and IO termination.")

internal fun Throwable.hasPendingHelperShutdown(): Boolean {
    val queue = ArrayDeque<Throwable>()
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    queue.addLast(this)
    repeat(64) {
        val failure = queue.removeFirstOrNull() ?: return false
        if (failure is HelperShutdownPendingException) return true
        if (seen.add(failure)) {
            failure.cause?.let { if (queue.size < 64) queue.addLast(it) }
            for (suppressed in failure.suppressed) {
                if (queue.size >= 64) break
                queue.addLast(suppressed)
            }
        }
    }
    return false
}
