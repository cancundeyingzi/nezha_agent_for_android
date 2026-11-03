package com.nezhahq.agent.service

import android.app.Service
import com.nezhahq.agent.core.config.ConfigRepository
import com.nezhahq.agent.core.model.AgentConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class AgentServiceCommand {
    START_OR_RELOAD,
    RECOVER_LATEST,
    REFRESH_CAPABILITIES,
    IGNORE
}

internal data class AgentServiceCommandDecision(
    val command: AgentServiceCommand,
    val startResult: Int
)

internal object AgentServiceCommandPolicy {
    fun decide(action: String?): AgentServiceCommandDecision =
        AgentServiceCommandDecision(
            command = fromAction(action),
            startResult = Service.START_STICKY
        )

    fun fromAction(action: String?): AgentServiceCommand = when (action) {
        null -> AgentServiceCommand.RECOVER_LATEST
        AgentService.ACTION_START_OR_RELOAD -> AgentServiceCommand.START_OR_RELOAD
        AgentService.ACTION_REFRESH_CAPABILITIES -> AgentServiceCommand.REFRESH_CAPABILITIES
        else -> AgentServiceCommand.IGNORE
    }
}

/**
 * How a freshly loaded configuration snapshot is applied to the runtime.
 *
 * [RELOAD] replaces the runtime and therefore reconnects. [CAPABILITIES_ONLY] only changes which
 * remote tasks the live runtime accepts, leaving the connection untouched.
 */
internal enum class ConfigApplyMode {
    CAPABILITIES_ONLY,
    RELOAD;

    /** A reload starts from the latest snapshot, so it already covers a pending refresh. */
    fun merge(other: ConfigApplyMode): ConfigApplyMode =
        if (this == RELOAD || other == RELOAD) RELOAD else CAPABILITIES_ONLY
}

/** Collapses requests arriving during a transition into the single strongest mode. */
internal class PendingApplyMode {
    private val lock = Any()
    private var mode: ConfigApplyMode? = null

    fun record(requested: ConfigApplyMode) = synchronized(lock) {
        mode = mode?.merge(requested) ?: requested
    }

    fun take(): ConfigApplyMode? = synchronized(lock) {
        mode.also { mode = null }
    }
}

internal sealed interface ConfigLoadOutcome {
    data class Ready(val config: AgentConfig) : ConfigLoadOutcome
    data class Failed(val error: Throwable) : ConfigLoadOutcome
}

/**
 * Conflates pending requests and loads configuration only when a serial transition is ready.
 *
 * Failures are split by what the user would have to do about them. A configuration that cannot be
 * loaded needs the user to fix it, so the service gives up; a runtime that failed to start is
 * usually a transient condition, so it is retried with [retryPolicy] and the agent comes back
 * without anyone touching the app.
 */
internal class AgentConfigCommandProcessor(
    private val scope: CoroutineScope,
    private val repository: ConfigRepository,
    private val controller: AgentRuntimeController,
    private val ioDispatcher: CoroutineDispatcher,
    private val onConfigurationUnusable: (Throwable) -> Unit,
    private val onRuntimeUnavailable: (failure: Throwable, retryDelayMillis: Long) -> Unit,
    private val onReloadRejected: (Throwable) -> Unit,
    private val awaitPreviousShutdown: suspend () -> Unit = {},
    private val retryPolicy: StartRetryPolicy = StartRetryPolicy(),
    private val delayBeforeRetry: suspend (Long) -> Unit = { delay(it) }
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val pendingMode = PendingApplyMode()
    private val closed = AtomicBoolean()
    private val retryLock = Any()
    private var retryJob: Job? = null

    private val worker: Job = scope.launch {
        for (ignored in requests) {
            drainRedundantWakeUps()
            val mode = pendingMode.take() ?: continue
            when (val outcome = loadConfig()) {
                is ConfigLoadOutcome.Ready -> applyOrReport(mode, outcome.config)
                is ConfigLoadOutcome.Failed -> reportConfigurationFailure(outcome.error)
            }
        }
    }

    /**
     * Requests a full runtime replacement built from the latest persisted configuration.
     *
     * This is the explicit path — a user start or a boot — so it preempts a scheduled retry and
     * restarts the backoff instead of making the user wait out the previous schedule.
     */
    fun requestReload() {
        cancelPendingRetry()
        retryPolicy.reset()
        enqueue(ConfigApplyMode.RELOAD)
    }

    /**
     * Requests that the live runtime re-read its remote capability grants.
     *
     * The connection is preserved, so revoking a capability takes effect without a reconnect.
     */
    fun requestCapabilityRefresh() = enqueue(ConfigApplyMode.CAPABILITIES_ONLY)

    /** Closes admission immediately; onDestroy must not wait for the shutdown queue to reach us. */
    fun cancel() {
        if (closed.compareAndSet(false, true)) requests.close()
        cancelPendingRetry()
        worker.cancel()
    }

    suspend fun close() {
        cancel()
        worker.join()
    }

    private fun enqueue(mode: ConfigApplyMode) {
        if (closed.get()) return
        // Recorded before the wake-up so the worker always finds a mode waiting for it.
        pendingMode.record(mode)
        requests.trySend(Unit)
    }

    private fun drainRedundantWakeUps() {
        while (requests.tryReceive().isSuccess) {
            // Their modes are already merged into pendingMode; only the signal is redundant.
        }
    }

    private suspend fun loadConfig(): ConfigLoadOutcome = withContext(ioDispatcher) {
        repository.loadAgentConfig().fold(
            onSuccess = { ConfigLoadOutcome.Ready(it) },
            onFailure = { ConfigLoadOutcome.Failed(it) }
        )
    }

    private suspend fun applyOrReport(mode: ConfigApplyMode, config: AgentConfig) {
        try {
            awaitPreviousShutdown()
            apply(mode, config)
            retryPolicy.reset()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            if (controller.isRunning) onReloadRejected(failure) else scheduleRetry(failure)
        }
    }

    private suspend fun apply(mode: ConfigApplyMode, config: AgentConfig) = when (mode) {
        ConfigApplyMode.RELOAD -> controller.reload(config)
        ConfigApplyMode.CAPABILITIES_ONLY ->
            controller.updateCapabilities(config.remoteCapabilities)
    }

    /** A live runtime keeps serving, so a configuration that cannot be read is not fatal yet. */
    private fun reportConfigurationFailure(failure: Throwable) {
        if (controller.isRunning) onReloadRejected(failure) else onConfigurationUnusable(failure)
    }

    private fun scheduleRetry(failure: Throwable) {
        if (closed.get()) return
        val retryDelayMillis = retryPolicy.nextDelayMillis()
        onRuntimeUnavailable(failure, retryDelayMillis)
        val scheduled = scope.launch {
            delayBeforeRetry(retryDelayMillis)
            enqueue(ConfigApplyMode.RELOAD)
        }
        synchronized(retryLock) { retryJob = scheduled }
    }

    private fun cancelPendingRetry() {
        synchronized(retryLock) {
            retryJob?.cancel()
            retryJob = null
        }
    }
}
