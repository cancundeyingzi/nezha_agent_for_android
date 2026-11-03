package com.nezhahq.agent

import android.content.Context
import com.nezhahq.agent.core.config.ConfigRepository
import com.nezhahq.agent.core.config.StorageStatus
import com.nezhahq.agent.grpc.ConnectionStateHolder
import com.nezhahq.agent.util.ConfigurationReadiness
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.LocalDiagnostics
import com.nezhahq.agent.util.DataStoreConfigRepository
import java.io.File

/**
 * The application's object graph, assembled once in [NezhaAgentApplication].
 *
 * Hand-written on purpose: the graph is a handful of objects wired into three entry points (service,
 * view model, boot receiver), well below the point where an annotation processor earns its build
 * cost. Its providers map one-to-one onto `@Provides` should that change.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val localDiagnostics = LocalDiagnostics(directory = { File(appContext.noBackupFilesDir, "diagnostics") })

    init {
        Logger.diagnostics = localDiagnostics
    }

    val configRepository: ConfigRepository = DataStoreConfigRepository(appContext)

    /** First-load UI barrier; later recovery publishes a fresh cached repository snapshot. */
    internal val configurationReadiness = ConfigurationReadiness()

    /** Written by the running service, observed by the UI. */
    val connectionState = ConnectionStateHolder()

    /** Initializes encrypted storage and Root authorization off the main thread, then releases UI waiters. */
    fun initializeConfigurationStorage() {
        var status = StorageStatus.UNAVAILABLE
        try {
            status = configRepository.initializeStorage()
        } catch (failure: Throwable) {
            Logger.e("AppContainer: 配置存储初始化失败 (Error=${failure.javaClass.simpleName})")
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
