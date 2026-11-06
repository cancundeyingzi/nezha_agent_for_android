package com.nezhahq.agent

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nezhahq.agent.core.config.ConfigRepository
import com.nezhahq.agent.core.model.RemoteCapability
import com.nezhahq.agent.ui.UiEvents
import com.nezhahq.agent.ui.main.AndroidInstantSampler
import com.nezhahq.agent.ui.main.AndroidMainPlatform
import com.nezhahq.agent.ui.main.ConnectionSettingsController
import com.nezhahq.agent.ui.main.DiagnosticsController
import com.nezhahq.agent.ui.main.MainUiState
import com.nezhahq.agent.ui.main.RunControlController
import com.nezhahq.agent.ui.main.SimulatorController
import com.nezhahq.agent.ui.main.UiConfigurationCoordinator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Assembles feature controllers, projects immutable state, and forwards explicit UI events. */
class MainViewModel(application: Application, repository: ConfigRepository) : ViewModel() {
    private val container = application.appContainer
    private val uiEvents = UiEvents()
    val events = uiEvents.flow
    private val platform = AndroidMainPlatform(application, uiEvents)
    private val configuration = UiConfigurationCoordinator(viewModelScope, repository, container.configurationReadiness, uiEvents)
    private val run = RunControlController(configuration, platform, uiEvents, viewModelScope)
    private val connection = ConnectionSettingsController(configuration, platform, uiEvents, run::invalidateStart)
    private val simulator = SimulatorController(configuration, container.simulatorSessions, uiEvents)
    private val diagnostics = DiagnosticsController(viewModelScope, container.localDiagnostics.state,
        container.localDiagnostics::setEnabled, AndroidInstantSampler(application)::sample)

    val state: StateFlow<MainUiState> = combine(configuration.state, connection.state, run.state, simulator.state, diagnostics.state) {
            configuration, connection, run, simulator, diagnostics ->
        MainUiState(configuration, connection, run, simulator, diagnostics)
    }.combine(container.connectionState.connectionState) { state, grpc -> state.copy(grpcConnectionState = grpc) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState(configuration.state.value,
            connection.state.value, run.state.value, simulator.state.value, diagnostics.state.value,
            container.connectionState.connectionState.value))

    init { configuration.initialize() }

    fun onServerChanged(value: String) { connection.serverChanged(value) }
    fun onPortChanged(value: String) { connection.portChanged(value) }
    fun onSecretChanged(value: String) { connection.secretChanged(value) }
    fun onUuidChanged(value: String) { connection.uuidChanged(value) }
    fun onUseTlsChanged(enabled: Boolean) { connection.tlsChanged(enabled) }
    fun onRootModeChanged(enabled: Boolean, shizukuRequestCode: Int) = connection.rootModeChanged(enabled, shizukuRequestCode)
    fun onShizukuPermissionResult(granted: Boolean) = connection.permissionResult(granted)
    fun parseClipboardConfig() = connection.parseClipboard()

    fun startAgent(notificationPermGranted: Boolean) = run.start(notificationPermGranted)
    fun onNotificationPermissionResult(requestId: Long, granted: Boolean) = run.notificationPermissionResult(requestId, granted)
    fun stopAgent() = run.stop()
    fun onAutoStartPromptResult(accepted: Boolean) = run.autoStartPromptResult(accepted)
    fun toggleAutoStart(enabled: Boolean) = run.autoStartChanged(enabled)
    fun refreshPermissions() = run.refreshPermissions()
    fun setKeepAliveAudio(enabled: Boolean) = run.keepAliveAudioChanged(enabled)
    fun setFloatWindow(enabled: Boolean) = run.floatWindowChanged(enabled)
    fun setVpnTraffic(enabled: Boolean) = run.vpnTrafficChanged(enabled)
    fun toggleRemoteCommand(enabled: Boolean) = run.capabilityChanged(RemoteCapability.SHELL, enabled)
    fun toggleRemoteFileManager(enabled: Boolean) = run.capabilityChanged(RemoteCapability.FILE_MANAGER, enabled)
    fun toggleRemoteNat(enabled: Boolean) = run.capabilityChanged(RemoteCapability.NAT, enabled)

    fun onSimulatorServerChanged(value: String) = simulator.serverChanged(value)
    fun onSimulatorPortChanged(value: String) = simulator.portChanged(value)
    fun onSimulatorSecretChanged(value: String) = simulator.secretChanged(value)
    fun onSimulatorThreadCountChanged(value: String) = simulator.threadCountChanged(value)
    fun onSimulatorUseTlsChanged(enabled: Boolean) = simulator.tlsChanged(enabled)
    fun startSimulator() = simulator.start()
    fun stopSimulator(expectedSessionId: Long?) = simulator.requestStop(expectedSessionId)

    fun runInstantTest() = diagnostics.runInstantTest()
    fun dismissTestResult() = diagnostics.dismissTestResult()
    fun setLocalDiagnostics(enabled: Boolean) = diagnostics.setLocalDiagnostics(enabled)

    fun retryConfigurationStorage() { run.invalidateStart(); configuration.retry() }
    fun resetConfigurationStorage() {
        val simulatorReset = simulator.captureConfigurationReset()
        configuration.reset(onResetSnapshotApplied = { simulator.configurationResetApplied(simulatorReset) }) {
            run.stop()
            run.resetUi()
            connection.clearStatus()
            simulator.requestStop(simulatorReset.sessionId)
        }
    }

    override fun onCleared() {
        run.invalidateStart()
        simulator.onCleared()
        // LocalDiagnostics and pending native cleanup belong to AppContainer. The running agent
        // service has its own lifecycle and continues after the Activity is finished.
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY])
                MainViewModel(application, application.appContainer.configRepository)
            }
        }
    }
}
