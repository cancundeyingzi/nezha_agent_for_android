package com.nezhahq.agent.service

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull

class DashboardSessionTimeoutException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

object DashboardSessionWatchdog {
    const val HANDSHAKE_TIMEOUT_MS = 5_000L
    const val STATE_RECEIPT_TIMEOUT_MS = 10_000L
    // RequestTask is push-only; silence is normal. Its replacement must not affect state/session work.
    // Exec may run for 300s; leave another minute for result transfer and helper cleanup.
    const val TASK_IDLE_TIMEOUT_MS = 360_000L
    const val STATE_REPORT_INTERVAL_MS = 2_000L

    suspend fun <T> callWithin(
        timeoutMs: Long,
        operationName: String,
        block: suspend () -> T
    ): T {
        // withTimeoutOrNull recognizes its own deadline. A caller's cancellation/timeout must
        // propagate, rather than being mistaken for an idle task stream and restarted.
        val completed = withTimeoutOrNull(timeoutMs) { Completed(block()) }
            ?: throw DashboardSessionTimeoutException("$operationName timed out after ${timeoutMs}ms")
        return completed.value
    }

    suspend fun <T> receiveWithin(
        channel: ReceiveChannel<T>,
        timeoutMs: Long,
        streamName: String
    ): T = callWithin(timeoutMs, streamName) { channel.receive() }

    private data class Completed<T>(val value: T)
}
