package com.nezhahq.agent.util

import android.content.Context
import com.nezhahq.agent.core.config.StorageStatus
import com.nezhahq.agent.core.model.SimulatedDeviceConfig
import com.nezhahq.agent.core.security.RootModeMutationCoordinator
import java.io.File

/** Process-wide lifecycle and authorization owner for encrypted DataStore configuration. */
internal object ConfigStore {
    private val runtimeLock = Any()
    private val rootModeMutations = RootModeMutationCoordinator { enabled -> RootShell.configureAuthorization(enabled) }
    @Volatile private var runtime: ConfigurationStorageCoordinator? = null

    /** Explicit blocking IO boundary, called only by background initialization/recovery. */
    fun initialize(context: Context, retry: Boolean = false): StorageStatus =
        runtimeFor(context).initialize(retry)

    fun status(context: Context): StorageStatus = runtimeFor(context).currentStatus()
    fun snapshot(context: Context): PublishedConfiguration = runtimeFor(context).current()
    fun resetConfigurationStorage(context: Context): Boolean = runtimeFor(context).reset()

    fun saveConfig(
        context: Context, server: String, port: Int, secret: String, useTLS: Boolean = true,
        uuid: String = "", rootMode: Boolean = false
    ): Boolean = commit(context, mapOf(
        "server" to server, "port" to port, "secret" to secret, "use_tls" to useTLS,
        "uuid" to uuid, "root_mode" to rootMode
    ))

    fun setEnableAutoStart(context: Context, enable: Boolean): Boolean = commit(context, mapOf("enable_auto_start" to enable))
    fun setEnableRemoteCommand(context: Context, enable: Boolean): Boolean = commit(context, mapOf("enable_remote_command" to enable))
    fun setEnableRemoteFileManager(context: Context, enable: Boolean): Boolean = commit(context, mapOf("enable_remote_file_manager" to enable))
    fun setEnableRemoteNat(context: Context, enable: Boolean): Boolean = commit(context, mapOf("enable_remote_nat" to enable))

    fun saveSimulatorConfig(context: Context, server: String, port: Int, secret: String, useTls: Boolean, threadCount: Int): Boolean =
        commit(context, mapOf(
            "simulator_server" to server, "simulator_port" to port, "simulator_secret" to secret,
            "simulator_use_tls" to useTls,
            "simulator_thread_count" to threadCount.coerceIn(1, SimulatedDeviceConfig.MAX_THREAD_COUNT)
        ))

    fun saveToolSettings(context: Context, enableKeepAliveAudio: Boolean, enableFloatWindow: Boolean, enableVpnTraffic: Boolean): Boolean =
        commit(context, mapOf(
            "enable_keep_alive_audio" to enableKeepAliveAudio, "enable_float_window" to enableFloatWindow,
            "enable_vpn_traffic" to enableVpnTraffic
        ))

    fun saveAutoStartPromptResult(context: Context, enable: Boolean): Boolean =
        commit(context, mapOf("enable_auto_start" to enable, "has_shown_auto_start_prompt" to true))

    private fun commit(context: Context, changes: Map<String, Any>): Boolean =
        runtimeFor(context).write { it.changed(changes) }

    /** Creating this graph is memory-only. Even Context.noBackupFilesDir is deferred to IO. */
    private fun runtimeFor(context: Context): ConfigurationStorageCoordinator {
        runtime?.let { return it }
        return synchronized(runtimeLock) {
            runtime ?: run {
                val appContext = context.applicationContext
                ConfigurationStorageCoordinator(
                    EncryptedConfigurationDataStore(
                        file = { File(appContext.noBackupFilesDir, "configuration/agent.enc") },
                        keys = AndroidConfigurationKeyProvider()
                    ),
                    AndroidLegacyConfigurationOperations(appContext),
                    rootModeMutations,
                    onFailure = { failure ->
                        Logger.e("ConfigStore: 加密配置操作失败", failure)
                    }
                ).also { runtime = it }
            }
        }
    }
}
