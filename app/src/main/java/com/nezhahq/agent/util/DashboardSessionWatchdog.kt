package com.nezhahq.agent.util

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeout

class DashboardSessionTimeoutException(
    message: String,
    cause: Throwable
) : RuntimeException(message, cause)

/**
 * The Dashboard session's deadlines, and the two wrappers that apply them.
 *
 * This is plain Kotlin and coroutines — no Android, no service lifecycle — so it lives in `util`
 * rather than `service`. It used to sit in `service`, which forced the device simulator to import
 * from the foreground-service package just to reuse the handshake deadline: a debugging tool
 * depending on the runtime it exists to stand in for.
 */
object DashboardSessionWatchdog {
    const val HANDSHAKE_TIMEOUT_MS = 5_000L
    const val STATE_RECEIPT_TIMEOUT_MS = 10_000L

    /**
     * How long the requestTask stream may stay silent before the agent replaces that one stream.
     *
     * This is not a liveness check. The Dashboard pushes nothing here while it has no work to hand
     * out, so silence is the normal state of an idle deployment and any deadline short enough to
     * "detect" it only detects idleness. Transport liveness is already covered far more tightly by
     * [STATE_RECEIPT_TIMEOUT_MS] on the state stream, which rides the same channel and notices a
     * dead connection within ten seconds. What is left for this deadline is the one case the state
     * stream cannot see: the Dashboard keeping the task stream open while no longer serving it.
     *
     * It was 30s, which expired on every idle connection — and back then expiry tore down the whole
     * session, healthy heartbeat and live terminal included. It now replaces only the task stream
     * (see `consumeWithIdleRestart`), and sits far above any plausible Dashboard task cadence so an
     * idle agent stops re-opening the stream twice a minute for nothing.
     */
    const val TASK_IDLE_TIMEOUT_MS = 300_000L
    const val STATE_REPORT_INTERVAL_MS = 2_000L

    suspend fun <T> callWithin(
        timeoutMs: Long,
        operationName: String,
        block: suspend () -> T
    ): T {
        return try {
            withTimeout(timeoutMs) { block() }
        } catch (e: TimeoutCancellationException) {
            throw DashboardSessionTimeoutException(
                "$operationName timed out after ${timeoutMs}ms",
                e
            )
        }
    }

    suspend fun <T> receiveWithin(
        channel: ReceiveChannel<T>,
        timeoutMs: Long,
        streamName: String
    ): T = callWithin(timeoutMs, streamName) { channel.receive() }
}
