package com.nezhahq.agent.service.keepalive

import android.content.Context
import com.nezhahq.agent.core.model.KeepAliveSettings
import com.nezhahq.agent.service.reportLifecycleEvent
import com.nezhahq.agent.util.Logger
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal interface KeepAliveResource {
    suspend fun setEnabled(enabled: Boolean)
    suspend fun close()
}

internal fun interface KeepAliveCloseWaiter {
    suspend fun await(job: Job, timeoutMillis: Long): Boolean
}

internal class KeepAliveController(
    private val audio: KeepAliveResource,
    private val overlay: KeepAliveResource,
    private val wakeLock: KeepAliveResource,
    private val vpn: KeepAliveResource,
    private val cleanupScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val closeWaiter: KeepAliveCloseWaiter = TimeoutKeepAliveCloseWaiter()
) {
    private val lifecycleMutex = Mutex()

    suspend fun reconfigure(settings: KeepAliveSettings) = lifecycleMutex.withLock {
        attempt("audio reconfigure", NO_TIMEOUT) { configureAudio(settings.audio) }
        attempt("overlay reconfigure", NO_TIMEOUT) { configureOverlay(settings.overlay) }
        attempt("wake lock reconfigure", NO_TIMEOUT) { configureWakeLock() }
        attempt("VPN reconfigure", NO_TIMEOUT) { configureVpn(settings.vpn) }
    }

    /**
     * Releases every resource, each under its own share of the deadline.
     *
     * One budget for all four meant a slow release consumed the whole thing and the rest were
     * cancelled without ever being attempted. The order puts the overlay first because it is the
     * only resource whose loss is permanent — an abandoned window survives the process that owns
     * it, while a leaked wake lock or audio track does not.
     */
    suspend fun close(perResourceTimeoutMillis: Long = NO_TIMEOUT) = lifecycleMutex.withLock {
        attempt("overlay close", perResourceTimeoutMillis) { closeOverlay() }
        attempt("audio close", perResourceTimeoutMillis) { closeAudio() }
        attempt("VPN close", perResourceTimeoutMillis) { closeVpn() }
        attempt("wake lock close", perResourceTimeoutMillis) { closeWakeLock() }
    }

    /**
     * Closes every resource and releases [cleanupScope]. The controller is not reusable afterwards.
     *
     * Cancelling on timeout is deliberate: a replacement runtime may already be acquiring the same
     * audio and window resources, so an unfinished teardown must not keep running against them.
     * [timeoutMillis] is the overall deadline; each resource gets an equal share of it so that
     * exhausting one does not silently skip the others.
     */
    suspend fun closeWithin(timeoutMillis: Long): Boolean {
        val perResource = (timeoutMillis / RESOURCE_COUNT).coerceAtLeast(1L)
        val closeJob = cleanupScope.launch { close(perResource) }
        val closed = closeWaiter.await(closeJob, timeoutMillis)
        cleanupScope.cancel()
        return closed
    }

    private suspend fun configureAudio(enabled: Boolean) = audio.setEnabled(enabled)

    private suspend fun configureOverlay(enabled: Boolean) = overlay.setEnabled(enabled)

    private suspend fun configureWakeLock() = wakeLock.setEnabled(true)

    private suspend fun configureVpn(enabled: Boolean) = vpn.setEnabled(enabled)

    private suspend fun closeAudio() = audio.close()

    private suspend fun closeOverlay() = overlay.close()

    private suspend fun closeWakeLock() = wakeLock.close()

    private suspend fun closeVpn() = vpn.close()

    /**
     * Runs one teardown step, absorbing its failure and its overrun.
     *
     * A step that exceeds [timeoutMillis] is cancelled and reported, but the ones after it still
     * run — the point of bounding each step separately. Cancellation of the whole teardown still
     * propagates, so the caller's deadline remains in charge.
     */
    private suspend fun attempt(
        operation: String,
        timeoutMillis: Long,
        action: suspend () -> Unit
    ) {
        try {
            if (timeoutMillis == NO_TIMEOUT) {
                action()
            } else if (withTimeoutOrNull(timeoutMillis) { action() } == null) {
                reportLifecycleEvent {
                    Logger.e("KeepAliveController: $operation timed out after ${timeoutMillis}ms")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportLifecycleEvent { Logger.e("KeepAliveController: $operation failed", e) }
        }
        currentCoroutineContext().ensureActive()
    }

    companion object {
        /** Reconfiguration runs without a deadline; only teardown is bounded. */
        internal const val NO_TIMEOUT = Long.MAX_VALUE

        /** How many resources [close] walks, used to divide the teardown deadline. */
        internal const val RESOURCE_COUNT = 4

        /**
         * Builds one runtime's keep-alive resources around a single cleanup scope.
         *
         * Teardown must outlive [scope] — cancelling the runtime's work must not abort the release
         * of an AudioTrack or a window — so cleanup gets its own scope, owned and cancelled here by
         * [closeWithin] rather than left unowned inside each resource.
         */
        fun create(context: Context, scope: CoroutineScope): KeepAliveController {
            val appContext = context.applicationContext
            val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            return KeepAliveController(
                audio = AudioKeepAlive(scope, cleanupScope = cleanupScope),
                overlay = OverlayKeepAlive(AndroidOverlayHost(appContext)),
                wakeLock = WakeLockKeepAlive(
                    AndroidWakeLockLease(appContext),
                    CoroutineRenewalScheduler(scope)
                ),
                vpn = PlaceholderVpnKeepAlive(AndroidPlaceholderVpnHost(appContext)),
                cleanupScope = cleanupScope
            )
        }
    }
}

private class TimeoutKeepAliveCloseWaiter : KeepAliveCloseWaiter {
    override suspend fun await(job: Job, timeoutMillis: Long): Boolean =
        withTimeoutOrNull(timeoutMillis) {
            job.join()
            !job.isCancelled
        } ?: false
}
