package com.nezhahq.agent.util

import android.content.Context
import android.os.Build
import com.nezhahq.agent.core.config.ConfigRepository
import com.nezhahq.agent.core.config.StorageStatus
import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.AutoStartState
import com.nezhahq.agent.core.model.ConnectionDraft
import com.nezhahq.agent.core.model.KeepAliveSettings
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.core.model.RemoteCapability
import com.nezhahq.agent.core.model.SimulatorDraft
import com.nezhahq.agent.core.model.SimulatedDeviceConfig
import com.nezhahq.agent.core.platform.VpnTrafficCompatibility
import java.io.IOException

/** Synchronous reads are memory-only; initialization and writes are explicit IO operations. */
class DataStoreConfigRepository(context: Context) : ConfigRepository {
    private val context = context.applicationContext

    override fun initializeStorage(retry: Boolean): StorageStatus = ConfigStore.initialize(context, retry)
    override fun storageStatus(): StorageStatus = ConfigStore.status(context)
    override fun resetStorage(): Boolean = ConfigStore.resetConfigurationStorage(context)

    override fun loadAgentConfig(): Result<AgentConfig> = runCatching {
        val current = ConfigStore.snapshot(context)
        check(current.status.isUsable) { "Configuration storage is unavailable." }
        val values = checkNotNull(current.document).values
        val draft = connection(values)
        AgentConfig(
            server = draft.server, port = draft.port, secret = draft.secret, uuid = draft.uuid,
            useTls = draft.useTls, rootMode = draft.rootMode,
            keepAlive = tools(values), remoteCapabilities = capabilities(values)
        )
    }

    override fun hasCompleteConnection(): Boolean = ConfigurationStorageCoordinator.hasCompleteConnection(values())
    override fun loadConnectionDraft(): ConnectionDraft = connection(values())
    override fun loadToolSettings(): KeepAliveSettings = tools(values())
    override fun loadRemoteCapabilities(): RemoteCapabilities = capabilities(values())
    override fun loadAutoStartState(): AutoStartState = values().let {
        AutoStartState(it.boolean("enable_auto_start"), it.boolean("has_shown_auto_start_prompt"))
    }
    override fun loadSimulatorDraft(): SimulatorDraft = values().let {
        SimulatorDraft(
            server = it.string("simulator_server", it.string("server")),
            port = it.int("simulator_port", it.int("port", 5555)),
            secret = it.string("simulator_secret", it.string("secret")),
            useTls = it.boolean("simulator_use_tls", it.boolean("use_tls", true)),
            threadCount = it.int("simulator_thread_count", SimulatedDeviceConfig.DEFAULT_THREAD_COUNT)
                .coerceIn(1, SimulatedDeviceConfig.MAX_THREAD_COUNT)
        )
    }

    private fun values(): ConfigurationValues = ConfigStore.snapshot(context).document?.values ?: ConfigurationValues.EMPTY
    private fun connection(values: ConfigurationValues): ConnectionDraft = ConnectionDraft(
        server = values.string("server"), port = values.int("port", 5555), secret = values.string("secret"),
        uuid = values.string("uuid"), useTls = values.boolean("use_tls", true), rootMode = values.boolean("root_mode")
    )
    private fun tools(values: ConfigurationValues): KeepAliveSettings = KeepAliveSettings(
        audio = values.boolean("enable_keep_alive_audio"), overlay = values.boolean("enable_float_window"),
        vpn = VpnTrafficCompatibility.normalize(values.boolean("enable_vpn_traffic"), Build.VERSION.SDK_INT)
    )
    private fun capabilities(values: ConfigurationValues): RemoteCapabilities = RemoteCapabilities(
        shellEnabled = values.boolean("enable_remote_command"),
        fileManagerEnabled = values.boolean("enable_remote_file_manager"),
        natEnabled = values.boolean("enable_remote_nat")
    )
    override fun saveConnection(draft: ConnectionDraft): Result<Unit> = runCatching {
        requirePersisted(
            ConfigStore.saveConfig(
                context = context,
                server = draft.server,
                port = draft.port,
                secret = draft.secret,
                useTLS = draft.useTls,
                uuid = draft.uuid,
                rootMode = draft.rootMode
            ),
            "connection configuration"
        )
    }

    override fun saveToolSettings(settings: KeepAliveSettings): Result<Unit> = runCatching {
        requirePersisted(
            ConfigStore.saveToolSettings(
                context,
                settings.audio,
                settings.overlay,
                settings.vpn
            ),
            "tool settings"
        )
    }

    override fun saveRemoteCapability(
        capability: RemoteCapability,
        enabled: Boolean
    ): Result<Unit> = runCatching {
        val persisted = when (capability) {
            RemoteCapability.SHELL -> ConfigStore.setEnableRemoteCommand(context, enabled)
            RemoteCapability.FILE_MANAGER ->
                ConfigStore.setEnableRemoteFileManager(context, enabled)
            RemoteCapability.NAT -> ConfigStore.setEnableRemoteNat(context, enabled)
        }
        requirePersisted(persisted, "${capability.name.lowercase()} capability")
    }

    override fun saveAutoStart(enabled: Boolean): Result<Unit> = runCatching {
        requirePersisted(ConfigStore.setEnableAutoStart(context, enabled), "auto start")
    }

    override fun saveAutoStartPromptResult(enabled: Boolean): Result<Unit> = runCatching {
        requirePersisted(
            ConfigStore.saveAutoStartPromptResult(context, enabled),
            "auto start prompt result"
        )
    }

    override fun saveSimulator(draft: SimulatorDraft): Result<Unit> = runCatching {
        requirePersisted(
            ConfigStore.saveSimulatorConfig(
                context = context,
                server = draft.server,
                port = draft.port,
                secret = draft.secret,
                useTls = draft.useTls,
                threadCount = draft.threadCount
            ),
            "simulator configuration"
        )
    }

    private fun requirePersisted(persisted: Boolean, description: String) {
        if (!persisted) {
            throw IOException("Unable to persist $description.")
        }
    }
}
