package com.nezhahq.agent.ui.main

import com.nezhahq.agent.core.config.ConfigRepository
import com.nezhahq.agent.core.config.ConfigurationSnapshot
import com.nezhahq.agent.core.config.StorageStatus
import com.nezhahq.agent.core.config.loadConfigurationSnapshot
import com.nezhahq.agent.core.model.ConnectionDraft
import com.nezhahq.agent.core.model.KeepAliveSettings
import com.nezhahq.agent.core.model.RemoteCapability
import com.nezhahq.agent.core.model.SimulatorDraft
import com.nezhahq.agent.ui.UiEvents
import com.nezhahq.agent.util.ConfigurationOperationGate
import com.nezhahq.agent.util.ConfigurationReadiness
import com.nezhahq.agent.util.ConfigurationRecoveryState
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.SnapshotApplyMode
import com.nezhahq.agent.util.awaitInitialConfiguration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One UI-thread owner for drafts, repository access, recovery and the shared write/parse gate. */
internal class UiConfigurationCoordinator(
    private val scope: CoroutineScope,
    private val repository: ConfigRepository,
    private val readiness: ConfigurationReadiness,
    private val events: UiEvents,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    private val mutable = MutableStateFlow(UiConfigurationState())
    val state = mutable.asStateFlow()
    private val recovery = ConfigurationRecoveryState()
    private val operations = ConfigurationOperationGate { busy -> update { it.copy(writing = busy) } }
    private val editedConnection = mutableSetOf<ConnectionField>()
    private val editedSimulator = mutableSetOf<SimulatorField>()
    private var resetSnapshotApplied: (() -> Unit)? = null
    private var initialized = false

    /** Assembly calls this last: constructing the coordinator performs no repository reads. */
    fun initialize() {
        if (initialized) return
        initialized = true
        scope.launch {
            try {
                val snapshot = awaitInitialConfiguration(
                    readiness = readiness,
                    readSnapshot = { repository.loadConfigurationSnapshot() },
                    warningTimeoutMillis = 15_000L,
                    onSlowInitialization = { events.sendLong("配置存储打开较慢（可能正在迁移旧的加密配置），仍在等待，请稍候…") },
                    ioDispatcher = io
                )
                applySnapshot(snapshot, SnapshotApplyMode.INITIAL_LOAD)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Logger.e("Configuration: 配置加载失败 (Error=${failure.javaClass.simpleName})")
                update { it.copy(storageStatus = StorageStatus.UNAVAILABLE) }
            } finally {
                update { it.copy(loading = false) }
            }
            if (!state.value.storageAvailable) reportProblem(STORAGE_UNAVAILABLE_MESSAGE)
        }
    }

    fun editConnection(transform: (ConnectionFields) -> ConnectionFields): Boolean {
        if (!state.value.canEditDraft) return false
        val current = state.value
        val fields = transform(current.connection)
        if (fields == current.connection) return false
        recordConnectionEdits(current.connection, fields)
        mutable.value = current.copy(connection = fields, connectionVersion = current.connectionVersion + 1)
        return true
    }

    fun editSimulator(transform: (SimulatorFields) -> SimulatorFields) {
        if (!state.value.canEditDraft) return
        val current = state.value.simulator
        val next = transform(current)
        recordSimulatorEdits(current, next)
        update { it.copy(simulator = next) }
    }

    fun parseConnection(transform: suspend (ConnectionFields) -> ConnectionFields, onApplied: () -> Unit) {
        if (!state.value.canEditDraft) return
        val lease = operations.tryBegin() ?: return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                currentCoroutineContext().ensureActive()
                val fields = transform(state.value.connection)
                currentCoroutineContext().ensureActive()
                // A paste accepts the resulting draft, including explicit values equal to defaults.
                editedConnection.addAll(ConnectionField.entries)
                update { it.copy(connection = fields, connectionVersion = it.connectionVersion + 1) }
                onApplied()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Logger.e("Configuration: 剪贴板配置解析失败 (Error=${failure.javaClass.simpleName})")
                events.sendLong("剪贴板配置解析失败，请检查安装命令后重试")
            } finally {
                lease.close()
            }
        }
    }

    fun requireStorage(action: String): Boolean {
        val current = state.value
        if (current.canPersist) return true
        when {
            current.writing -> events.send("配置正在处理，请稍候")
            current.loading || current.recovering -> events.send("$action 已推迟：配置仍在加载，请稍候")
            else -> reportProblem("$action 已拒绝：$STORAGE_UNAVAILABLE_MESSAGE")
        }
        return false
    }

    fun saveConnection(draft: ConnectionDraft, onSuccess: () -> Unit, onFailure: () -> Unit) = persist(
        "保存连接配置并启动探针", "连接配置保存失败，探针未启动",
        { repository.saveConnection(draft) },
        { editedConnection.clear(); update { it.copy(connection = it.connection.copy(uuid = draft.uuid)) }; onSuccess() }, onFailure
    )

    fun saveSimulator(draft: SimulatorDraft, onSuccess: () -> Unit, onFailure: () -> Unit) = persist(
        "保存模拟器配置并启动模拟器", "模拟器配置保存失败，模拟器未启动",
        { repository.saveSimulator(draft) },
        { editedSimulator.clear(); update { it.copy(simulator = SimulatorFields(draft.server, draft.port.toString(), draft.secret, draft.useTls, draft.threadCount.toString())) }; onSuccess() },
        onFailure
    )

    fun saveTools(settings: KeepAliveSettings, onSuccess: () -> Unit = {}) = persist(
        "保存保活设置", "保活设置保存失败", { repository.saveToolSettings(settings) },
        { update { it.copy(tools = settings) }; onSuccess() }
    )

    fun saveCapability(capability: RemoteCapability, enabled: Boolean, onSuccess: () -> Unit) = persist(
        "保存远程能力设置", "远程能力设置保存失败", { repository.saveRemoteCapability(capability, enabled) },
        {
            update { current -> current.copy(capabilities = when (capability) {
                RemoteCapability.SHELL -> current.capabilities.copy(shellEnabled = enabled)
                RemoteCapability.FILE_MANAGER -> current.capabilities.copy(fileManagerEnabled = enabled)
                RemoteCapability.NAT -> current.capabilities.copy(natEnabled = enabled)
            }) }
            onSuccess()
        }
    )

    fun saveAutoStart(enabled: Boolean, promptResult: Boolean = false, onSuccess: () -> Unit = {}, onFailure: () -> Unit = {}) = persist(
        "保存开机自启动设置", "开机自启动设置保存失败",
        { if (promptResult) repository.saveAutoStartPromptResult(enabled) else repository.saveAutoStart(enabled) },
        { update { it.copy(autoStart = it.autoStart.copy(enabled = enabled, promptShown = it.autoStart.promptShown || promptResult)) }; onSuccess() },
        onFailure
    )

    private fun persist(action: String, failureMessage: String, write: () -> Result<Unit>, onSuccess: () -> Unit, onFailure: () -> Unit = {}) {
        if (!requireStorage(action)) { onFailure(); return }
        val lease = operations.tryBegin() ?: run { onFailure(); return }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val (saved, status) = try {
                currentCoroutineContext().ensureActive()
                withContext(io) { write().isSuccess to repository.storageStatus() }
            } catch (cancelled: CancellationException) {
                onFailure()
                throw cancelled
            } catch (failure: Exception) {
                Logger.e("Configuration: 配置保存失败 (Error=${failure.javaClass.simpleName})")
                false to StorageStatus.UNAVAILABLE
            } finally {
                lease.close()
            }
            update { it.copy(storageStatus = status) }
            if (saved && status.isUsable) {
                update { it.copy(storageError = null) }
                // Platform callbacks own their own error reporting. A startService/refresh failure
                // after a durable save must not misreport healthy storage as unavailable.
                onSuccess()
            } else {
                onFailure()
                reportProblem("$failureMessage；请检查配置存储状态后重试")
            }
        }
    }

    fun retry() = recover(reset = false, beforeReset = {})

    fun reset(onResetSnapshotApplied: () -> Unit = {}, beforeReset: () -> Unit) =
        recover(reset = true, beforeReset = beforeReset, onResetSnapshotApplied = onResetSnapshotApplied)

    private fun recover(reset: Boolean, beforeReset: () -> Unit, onResetSnapshotApplied: (() -> Unit)? = null) {
        val current = state.value
        if (current.loading || current.recovering || current.writing) return
        val lease = operations.tryBegin() ?: return
        if (reset) {
            recovery.confirmReset()
            // Keep the reset's presentation target through failures and ordinary retry calls.
            resetSnapshotApplied = onResetSnapshotApplied
            editedConnection.clear()
            editedSimulator.clear()
            // A failed reset must not leave old secrets or a Root grant available to a later save.
            update { UiConfigurationState(loading = false, writing = true, resetting = true,
                resetPending = true, storageStatus = StorageStatus.UNAVAILABLE,
                connectionVersion = it.connectionVersion + 1) }
        } else update { it.copy(retrying = true) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                currentCoroutineContext().ensureActive()
                if (reset) beforeReset()
                val snapshot = withContext(io) {
                    val status = if (reset) {
                        if (repository.resetStorage()) repository.storageStatus() else StorageStatus.UNAVAILABLE
                    } else recovery.retryStorage(repository)
                    if (status.isUsable) repository.loadConfigurationSnapshot() else ConfigurationSnapshot.DEFAULTS
                }
                update { it.copy(storageStatus = snapshot.status) }
                if (snapshot.status.isUsable) {
                    applySnapshot(snapshot, recovery.applyMode)
                    events.sendLong(if (reset) "配置已重置，请重新填写连接信息" else "加密配置存储已恢复")
                } else reportProblem(if (reset) "配置存储重置失败，请稍后重试" else STORAGE_UNAVAILABLE_MESSAGE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                update { it.copy(storageStatus = StorageStatus.UNAVAILABLE) }
                Logger.e("Configuration: 配置恢复失败 (Error=${failure.javaClass.simpleName})")
                reportProblem(STORAGE_UNAVAILABLE_MESSAGE)
            } finally {
                update { it.copy(resetting = false, retrying = false) }
                lease.close()
            }
        }
    }

    private fun applySnapshot(snapshot: ConfigurationSnapshot, mode: SnapshotApplyMode) {
        val current = state.value
        fun <T> connection(field: ConnectionField, value: T, loaded: T): T =
            if (mode == SnapshotApplyMode.RELOAD_AFTER_RESET || field !in editedConnection) loaded else value
        fun <T> simulator(field: SimulatorField, value: T, loaded: T): T =
            if (mode == SnapshotApplyMode.RELOAD_AFTER_RESET || field !in editedSimulator) loaded else value
        val c = snapshot.connection
        val s = snapshot.simulator
        update { it.copy(
            connection = ConnectionFields(
                connection(ConnectionField.SERVER, current.connection.server, c.server),
                connection(ConnectionField.PORT, current.connection.port, c.port.toString()),
                connection(ConnectionField.SECRET, current.connection.secret, c.secret),
                connection(ConnectionField.UUID, current.connection.uuid, c.uuid),
                connection(ConnectionField.TLS, current.connection.useTls, c.useTls),
                connection(ConnectionField.ROOT, current.connection.rootMode, c.rootMode)
            ),
            simulator = SimulatorFields(
                simulator(SimulatorField.SERVER, current.simulator.server, s.server),
                simulator(SimulatorField.PORT, current.simulator.port, s.port.toString()),
                simulator(SimulatorField.SECRET, current.simulator.secret, s.secret),
                simulator(SimulatorField.TLS, current.simulator.useTls, s.useTls),
                simulator(SimulatorField.THREADS, current.simulator.threadCount, s.threadCount.toString())
            ),
            // These controls only change after successful persistence; they have no unsaved draft.
            tools = snapshot.tools,
            capabilities = snapshot.capabilities,
            autoStart = snapshot.autoStart,
            storageStatus = snapshot.status,
            resetPending = false,
            storageError = null,
            connectionVersion = current.connectionVersion + 1
        ) }
        if (mode == SnapshotApplyMode.RELOAD_AFTER_RESET) {
            editedConnection.clear()
            editedSimulator.clear()
        }
        recovery.snapshotApplied(mode)
        if (mode == SnapshotApplyMode.RELOAD_AFTER_RESET) {
            val applied = resetSnapshotApplied
            resetSnapshotApplied = null
            applied?.invoke()
        }
    }

    private fun recordConnectionEdits(before: ConnectionFields, after: ConnectionFields) {
        if (before.server != after.server) editedConnection += ConnectionField.SERVER
        if (before.port != after.port) editedConnection += ConnectionField.PORT
        if (before.secret != after.secret) editedConnection += ConnectionField.SECRET
        if (before.uuid != after.uuid) editedConnection += ConnectionField.UUID
        if (before.useTls != after.useTls) editedConnection += ConnectionField.TLS
        if (before.rootMode != after.rootMode) editedConnection += ConnectionField.ROOT
    }

    private fun recordSimulatorEdits(before: SimulatorFields, after: SimulatorFields) {
        if (before.server != after.server) editedSimulator += SimulatorField.SERVER
        if (before.port != after.port) editedSimulator += SimulatorField.PORT
        if (before.secret != after.secret) editedSimulator += SimulatorField.SECRET
        if (before.useTls != after.useTls) editedSimulator += SimulatorField.TLS
        if (before.threadCount != after.threadCount) editedSimulator += SimulatorField.THREADS
    }

    private enum class ConnectionField { SERVER, PORT, SECRET, UUID, TLS, ROOT }
    private enum class SimulatorField { SERVER, PORT, SECRET, TLS, THREADS }

    private fun reportProblem(message: String) { update { it.copy(storageError = message) }; events.sendLong(message) }
    private inline fun update(transform: (UiConfigurationState) -> UiConfigurationState) { mutable.value = transform(mutable.value) }
}

private const val STORAGE_UNAVAILABLE_MESSAGE = "加密配置无法读取，当前不会保存或启动探针。请先重试；若系统密钥已丢失或失效，需要确认清空后重新填写配置。此前已确认但未完成的重置会在重试时继续。"
