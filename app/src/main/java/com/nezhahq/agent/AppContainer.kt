package com.nezhahq.agent

import android.content.Context
import com.nezhahq.agent.core.config.ConfigRepository
import com.nezhahq.agent.core.config.StorageStatus
import com.nezhahq.agent.grpc.ConnectionStateHolder
import com.nezhahq.agent.util.ConfigStore
import com.nezhahq.agent.util.ConfigurationReadiness
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.SharedPreferencesConfigRepository

/**
 * The application's object graph, assembled once in [NezhaAgentApplication].
 *
 * Hand-written on purpose: the graph is a handful of objects wired into three entry points (service,
 * view model, boot receiver), well below the point where an annotation processor earns its build
 * cost. Its providers map one-to-one onto `@Provides` should that change.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val configRepository: ConfigRepository = SharedPreferencesConfigRepository(appContext)

    /**
     * Resolves once [initializeConfigurationStorage] has run.
     *
     * Anything on the main thread waits for this rather than calling the repository directly: the
     * first read is what opens the store, and it blocks behind the background initialization for as
     * long as the legacy-store migration takes. Background callers such as [BootReceiver] already
     * have a thread to block on and need no signal.
     */
    internal val configurationReadiness = ConfigurationReadiness()

    /** Written by the running service, observed by the UI. */
    val connectionState = ConnectionStateHolder()

    /**
     * Opens configuration storage, applies the persisted root-mode grant, and releases waiters.
     *
     * Must be called off the main thread — see the call site in [NezhaAgentApplication].
     *
     * Root mode is routed through the storage layer so it takes the same ordering boundary as a
     * root-mode write; otherwise a startup read could race a concurrent toggle and re-enable stale
     * state. It is applied on a best-effort basis and deliberately cannot veto readiness: a grant
     * that fails to apply leaves the process unprivileged, which is a safe outcome, whereas a
     * configuration store that never reports ready is a UI that never becomes usable.
     *
     * Nothing here may propagate a failure for the same reason. A store that could not be opened
     * publishes UNAVAILABLE, a state every consumer already knows how to render.
     */
    fun initializeConfigurationStorage() {
        var status = StorageStatus.UNAVAILABLE
        try {
            status = configRepository.storageStatus()
            try {
                ConfigStore.synchronizeRootAuthorization(appContext, status)
            } catch (failure: Throwable) {
                Logger.e("AppContainer: 应用已保存的高权限授权失败", failure)
            }
        } catch (failure: Throwable) {
            Logger.e("AppContainer: 配置存储初始化失败", failure)
        } finally {
            // The one statement that must run on every path, logging failures included: a waiter
            // that is never resumed is a UI stuck on its loading state for the life of the process.
            configurationReadiness.complete(status)
        }
    }
}

/** Reaches the container from any component holding a [Context]. */
val Context.appContainer: AppContainer
    get() = (applicationContext as NezhaAgentApplication).container
