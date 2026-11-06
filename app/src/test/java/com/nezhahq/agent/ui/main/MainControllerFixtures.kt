package com.nezhahq.agent.ui.main

import com.nezhahq.agent.core.config.ConfigRepository
import com.nezhahq.agent.core.config.ConfigurationSnapshot
import com.nezhahq.agent.core.config.StorageStatus
import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.ConnectionDraft
import com.nezhahq.agent.core.model.KeepAliveSettings
import com.nezhahq.agent.core.model.RemoteCapability
import com.nezhahq.agent.core.model.SimulatorDraft
import com.nezhahq.agent.ui.UiEvents
import com.nezhahq.agent.util.ConfigurationReadiness
import com.nezhahq.agent.util.PermissionChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

internal class ControllerRepository : ConfigRepository {
    @Volatile var snapshot = ConfigurationSnapshot.DEFAULTS.copy(
        connection = ConnectionDraft("fixture.invalid", 5555, "synthetic-secret", "fixture-uuid", true, false),
        simulator = SimulatorDraft("fixture.invalid", 5555, "synthetic-secret", true, 1), status = StorageStatus.READY
    )
    @Volatile var status = StorageStatus.READY
    @Volatile var resetSucceeds = true
    var saveConnectionHook: () -> Result<Unit> = { Result.success(Unit) }
    var saveSimulatorHook: () -> Result<Unit> = { Result.success(Unit) }
    var saveToolsHook: () -> Result<Unit> = { Result.success(Unit) }
    val reads = AtomicInteger()
    val writes = AtomicInteger()
    val resets = AtomicInteger()
    override fun storageStatus() = status
    override fun initializeStorage(retry: Boolean) = status
    override fun resetStorage(): Boolean {
        resets.incrementAndGet()
        if (resetSucceeds) { snapshot = ConfigurationSnapshot.DEFAULTS.copy(status = StorageStatus.READY); status = StorageStatus.READY }
        else status = StorageStatus.UNAVAILABLE
        return resetSucceeds
    }
    override fun loadAgentConfig() = runCatching { snapshot.connection.let { AgentConfig(it.server, it.port, it.secret, it.uuid, it.useTls, it.rootMode) } }
    override fun hasCompleteConnection() = loadAgentConfig().isSuccess
    override fun loadConnectionDraft() = snapshot.connection.also { reads.incrementAndGet() }
    override fun loadToolSettings() = snapshot.tools.also { reads.incrementAndGet() }
    override fun loadRemoteCapabilities() = snapshot.capabilities.also { reads.incrementAndGet() }
    override fun loadAutoStartState() = snapshot.autoStart.also { reads.incrementAndGet() }
    override fun loadSimulatorDraft() = snapshot.simulator.also { reads.incrementAndGet() }
    override fun saveConnection(draft: ConnectionDraft): Result<Unit> {
        writes.incrementAndGet()
        return saveConnectionHook().onSuccess { snapshot = snapshot.copy(connection = draft) }
    }
    override fun saveSimulator(draft: SimulatorDraft): Result<Unit> {
        writes.incrementAndGet()
        return saveSimulatorHook().onSuccess { snapshot = snapshot.copy(simulator = draft) }
    }
    override fun saveToolSettings(settings: KeepAliveSettings): Result<Unit> {
        writes.incrementAndGet()
        return saveToolsHook().onSuccess { snapshot = snapshot.copy(tools = settings) }
    }
    override fun saveRemoteCapability(capability: RemoteCapability, enabled: Boolean): Result<Unit> {
        writes.incrementAndGet(); return Result.success(Unit)
    }
    override fun saveAutoStart(enabled: Boolean): Result<Unit> {
        writes.incrementAndGet(); snapshot = snapshot.copy(autoStart = snapshot.autoStart.copy(enabled = enabled)); return Result.success(Unit)
    }
    override fun saveAutoStartPromptResult(enabled: Boolean): Result<Unit> {
        writes.incrementAndGet(); snapshot = snapshot.copy(autoStart = snapshot.autoStart.copy(enabled = enabled, promptShown = true)); return Result.success(Unit)
    }
}

internal class ControllerPlatform(override val sdkInt: Int = 35) : RunControlPlatform, ConnectionSettingsPlatform {
    var starts = 0
    var stops = 0
    var refreshes = 0
    var startAction: () -> Unit = {}
    var refreshAction: () -> Unit = {}
    var clipboard = ""
    override fun startAgent() { startAction(); starts++ }
    override fun stopAgent() { stops++ }
    override fun refreshCapabilities() { refreshAction(); refreshes++ }
    override fun readClipboard(maxChars: Int) = clipboard.take(maxChars)
    override fun requestShizukuPermission(requestCode: Int) = ShizukuRequestStatus("waiting")
    override suspend fun readPermissions(autoStartEnabled: Boolean): List<PermissionChecker.PermissionItem> = emptyList()
}

internal suspend fun CoroutineScope.readyConfiguration(repository: ControllerRepository = ControllerRepository(), events: UiEvents = UiEvents()): UiConfigurationCoordinator {
    val readiness = ConfigurationReadiness().also { it.complete(repository.status) }
    return UiConfigurationCoordinator(this, repository, readiness, events).also {
        it.initialize()
        withTimeout(5_000) { it.state.first { state -> !state.loading } }
    }
}

internal suspend fun UiConfigurationCoordinator.awaitIdle() {
    withTimeout(5_000) { state.first { !it.loading && !it.writing && !it.recovering } }
}
