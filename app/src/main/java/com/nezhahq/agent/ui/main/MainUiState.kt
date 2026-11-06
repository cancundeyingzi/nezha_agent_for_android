package com.nezhahq.agent.ui.main

import com.nezhahq.agent.core.config.ConfigurationSnapshot
import com.nezhahq.agent.core.config.StorageStatus
import com.nezhahq.agent.core.model.AutoStartState
import com.nezhahq.agent.core.model.KeepAliveSettings
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.grpc.GrpcConnectionState
import com.nezhahq.agent.simulator.SimulatorRunState
import com.nezhahq.agent.util.LocalDiagnosticState
import com.nezhahq.agent.util.PermissionChecker

data class ConnectionFields(
    val server: String = "",
    val port: String = ConfigurationSnapshot.DEFAULTS.connection.port.toString(),
    val secret: String = "",
    val uuid: String = "",
    val useTls: Boolean = true,
    val rootMode: Boolean = false
)

data class SimulatorFields(
    val server: String = "",
    val port: String = ConfigurationSnapshot.DEFAULTS.simulator.port.toString(),
    val secret: String = "",
    val useTls: Boolean = true,
    val threadCount: String = ConfigurationSnapshot.DEFAULTS.simulator.threadCount.toString()
)

data class UiConfigurationState(
    val connection: ConnectionFields = ConnectionFields(),
    val simulator: SimulatorFields = SimulatorFields(),
    val tools: KeepAliveSettings = KeepAliveSettings(),
    val capabilities: RemoteCapabilities = RemoteCapabilities(),
    val autoStart: AutoStartState = AutoStartState(false, false),
    val loading: Boolean = true,
    val storageStatus: StorageStatus = StorageStatus.READY,
    val writing: Boolean = false,
    val retrying: Boolean = false,
    val resetting: Boolean = false,
    val resetPending: Boolean = false,
    val storageError: String? = null,
    val connectionVersion: Long = 0L
) {
    val recovering: Boolean get() = retrying || resetting
    val storageAvailable: Boolean get() = !loading && storageStatus.isUsable && !resetPending
    val canEditDraft: Boolean get() = !loading && !writing && !recovering && !resetPending
    val canPersist: Boolean get() = canEditDraft && storageAvailable
}

data class PendingStart(val requestId: Long, val configurationVersion: Long)

data class RunControlState(
    val savingStart: Boolean = false,
    val pendingStart: PendingStart? = null,
    val showAutoStartPrompt: Boolean = false,
    val vpnCompatibilityAvailable: Boolean = false,
    val permissions: List<PermissionChecker.PermissionItem> = emptyList(),
    val permissionsLoading: Boolean = true,
    val permissionError: String? = null
)

data class ConnectionSettingsState(val shizukuStatus: String = "")

data class DiagnosticsState(
    val sampling: Boolean = false,
    val result: String? = null,
    val local: LocalDiagnosticState = LocalDiagnosticState()
)

/** Screens receive one immutable projection; configuration mutation stays behind explicit events. */
data class MainUiState(
    val configuration: UiConfigurationState = UiConfigurationState(),
    val connectionSettings: ConnectionSettingsState = ConnectionSettingsState(),
    val run: RunControlState = RunControlState(),
    val simulator: SimulatorRunState = SimulatorRunState(),
    val diagnostics: DiagnosticsState = DiagnosticsState(),
    val grpcConnectionState: GrpcConnectionState = GrpcConnectionState.IDLE
)
