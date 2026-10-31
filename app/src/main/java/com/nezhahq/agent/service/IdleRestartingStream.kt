package com.nezhahq.agent.service

import com.nezhahq.agent.util.DashboardSessionTimeoutException
import com.nezhahq.agent.util.DashboardSessionWatchdog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Consumes a stream until cancelled, replacing *only the stream* when it falls silent.
 *
 * The point is what an idle deadline is allowed to reach. [openStream] is called inside a private
 * [coroutineScope], so its producer and any work installed by [onStreamStarted] end on expiry;
 * work [onMessage] starts in an outer scope keeps running across the replacement. That is the difference
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
    onStreamStarted: suspend (CoroutineScope) -> Unit = {},
    onStreamStopped: suspend () -> Unit = {},
    streamStopTimeoutMillis: Long = 5_000L,
    nanoTime: () -> Long = System::nanoTime,
    onMessage: suspend (T) -> Unit
): Nothing {
    require(idleTimeoutMillis > 0) { "idleTimeoutMillis must be positive" }
    require(streamStopTimeoutMillis > 0) { "streamStopTimeoutMillis must be positive" }
    while (true) {
        currentCoroutineContext().ensureActive()
        var primaryFailure: Throwable? = null
        try {
            coroutineScope {
                try {
                    onStreamStarted(this)
                    val incoming = openStream()
                    try {
                        var lastReceivedNanos = nanoTime()
                        fun remainingMillis(): Long {
                            val elapsed = (nanoTime() - lastReceivedNanos).coerceAtLeast(0) / 1_000_000
                            return (idleTimeoutMillis - elapsed).coerceAtLeast(0)
                        }
                        fun idle() = DashboardSessionTimeoutException(
                            "$streamName timed out after ${idleTimeoutMillis}ms without receiving a task"
                        )
                        while (true) {
                            // Only these withTimeoutOrNull deadlines mean idle. Handler timeouts,
                            // caller cancellation and all other failures keep their own identity.
                            val received = withTimeoutOrNull(remainingMillis()) { Received(incoming.receive()) }
                            if (received == null) {
                                onIdleRestart(idle())
                                break
                            }
                            lastReceivedNanos = nanoTime()
                            val handled = withTimeoutOrNull(remainingMillis()) {
                                onMessage(received.value)
                                true
                            }
                            if (handled == null) {
                                onIdleRestart(idle())
                                break
                            }
                        }
                    } finally {
                        incoming.cancel()
                    }
                } finally {
                    // New stream-bound work (for example MCP) must end with this registration.
                    // Legacy terminal/NAT/file sessions are deliberately owned by the outer scope.
                    coroutineContext.cancelChildren()
                }
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            // coroutineScope has joined the old producer and every stream-bound task before this
            // hook runs. Finish registry/result cleanup before another registration can open.
            try {
                withContext(NonCancellable) {
                    DashboardSessionWatchdog.callWithin(streamStopTimeoutMillis, "$streamName cleanup") {
                        onStreamStopped()
                    }
                }
            } catch (cleanupFailure: Throwable) {
                val original = primaryFailure
                if (original != null) {
                    if (cleanupFailure !== original) original.addSuppressed(cleanupFailure)
                } else {
                    // Cancellation may arrive while the NonCancellable stop hook is running.
                    // Preserve it too, rather than turning shutdown into a reconnectable timeout.
                    try {
                        currentCoroutineContext().ensureActive()
                    } catch (cancelled: CancellationException) {
                        if (cleanupFailure !== cancelled) cancelled.addSuppressed(cleanupFailure)
                        throw cancelled
                    }
                    throw cleanupFailure
                }
            }
        }
    }
}

private data class Received<T>(val value: T)
