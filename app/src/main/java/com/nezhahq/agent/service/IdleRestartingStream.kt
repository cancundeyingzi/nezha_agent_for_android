package com.nezhahq.agent.service

import com.nezhahq.agent.util.DashboardSessionTimeoutException
import com.nezhahq.agent.util.DashboardSessionWatchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope

/**
 * Consumes a stream until cancelled, replacing *only the stream* when it falls silent.
 *
 * The point is what an idle deadline is allowed to reach. [openStream] is called inside a private
 * [coroutineScope], so the producer it launches — and nothing else — is what an expiry tears down;
 * work [onMessage] started elsewhere keeps running across the replacement. That is the difference
 * between rebuilding a task stream and rebuilding a connection: an idle requestTask stream used to
 * take the metrics heartbeat and every live terminal, NAT tunnel and file transfer with it.
 *
 * The old stream is cancelled *and joined* before a new one is opened. Overlapping them is not just
 * untidy: the Dashboard keys the stream by client, and letting a cancel land after the replacement
 * registered would leave the agent holding a stream the Dashboard has already forgotten, with no
 * further deadline to notice — the exact "tasks silently stop arriving" failure this must not
 * introduce while fixing the other one. Leaving [coroutineScope] guarantees the ordering, because
 * it does not return until the cancelled producer has actually finished.
 *
 * Only the idle deadline restarts the stream. Every other failure propagates, so a broken channel
 * still reaches the connection loop and gets the full reconnect it needs.
 *
 * [openStream] must return a channel whose `cancel` ends everything it launched — `Flow.produceIn`
 * and `produce` both do. A producer that outlives its channel would never let the scope close.
 */
internal suspend fun <T> consumeWithIdleRestart(
    idleTimeoutMillis: Long,
    streamName: String,
    openStream: CoroutineScope.() -> ReceiveChannel<T>,
    onIdleRestart: (DashboardSessionTimeoutException) -> Unit = {},
    onMessage: suspend (T) -> Unit
): Nothing {
    while (true) {
        coroutineScope {
            val incoming = openStream()
            try {
                while (true) {
                    val message = try {
                        DashboardSessionWatchdog.receiveWithin(
                            incoming,
                            idleTimeoutMillis,
                            streamName
                        )
                    } catch (idle: DashboardSessionTimeoutException) {
                        onIdleRestart(idle)
                        break
                    }
                    onMessage(message)
                }
            } finally {
                incoming.cancel()
            }
        }
    }
}
