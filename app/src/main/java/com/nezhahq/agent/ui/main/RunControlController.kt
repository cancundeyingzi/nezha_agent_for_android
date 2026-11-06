package com.nezhahq.agent.ui.main

import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.ConnectionDraft
import com.nezhahq.agent.core.model.KeepAliveSettings
import com.nezhahq.agent.core.model.RemoteCapability
import com.nezhahq.agent.core.platform.VpnTrafficCompatibility
import com.nezhahq.agent.ui.UiEvents
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.SafeErrorText
import com.nezhahq.agent.util.PermissionChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal interface RunControlPlatform {
    val sdkInt: Int
    fun startAgent()
    fun stopAgent()
    fun refreshCapabilities()
    suspend fun readPermissions(autoStartEnabled: Boolean): List<PermissionChecker.PermissionItem>
}

/** Saved starts are versioned intents. No Activity/Compose callback owns service startup. */
internal class RunControlController(
    private val configuration: UiConfigurationCoordinator,
    private val platform: RunControlPlatform,
    private val events: UiEvents,
    private val scope: CoroutineScope,
    private val newUuid: () -> String = { UUID.randomUUID().toString() }
) {
    private val mutable = MutableStateFlow(RunControlState(
        vpnCompatibilityAvailable = VpnTrafficCompatibility.isSupported(platform.sdkInt)
    ))
    val state = mutable.asStateFlow()
    private var acceptedStart: PendingStart? = null
    private var permissionRefresh: Job? = null

    fun start(notificationPermissionGranted: Boolean) {
        if (acceptedStart != null || !configuration.requireStorage("启动探针")) return
        val snapshot = configuration.state.value
        val fields = snapshot.connection
        AgentConfig.validationError(fields.server, fields.port, fields.secret)?.let { events.send(it); return }
        val cleanedUuid = fields.uuid.trim().replace(Regex("^['\"]|['\"]$"), "")
        val uuid = cleanedUuid.takeUnless { it.isBlank() || it == "\\" } ?: newUuid()
        val intent = PendingStart(requestIds.incrementAndGet(), snapshot.connectionVersion)
        acceptedStart = intent
        update { it.copy(savingStart = true) }
        configuration.saveConnection(
            ConnectionDraft(fields.server, fields.port.trim().toInt(), fields.secret, uuid, fields.useTls, fields.rootMode),
            onSuccess = {
                if (isCurrent(intent)) {
                    update { it.copy(savingStart = false) }
                    if (platform.sdkInt >= 33 && !notificationPermissionGranted) {
                        update { it.copy(pendingStart = intent) }
                    } else launch(intent)
                } else discardInvalidStart(intent)
            },
            onFailure = { if (acceptedStart == intent) invalidateStart() }
        )
    }

    fun notificationPermissionResult(requestId: Long, granted: Boolean) {
        val pending = state.value.pendingStart ?: return
        if (pending.requestId != requestId) return
        if (!isCurrent(pending)) { discardInvalidStart(pending); return }
        if (!granted) events.sendLong("通知权限被拒绝！状态栏保活通知无法显示，系统可能降低探针保活优先级。")
        launch(pending)
    }

    fun invalidateStart() {
        acceptedStart = null
        update { it.copy(savingStart = false, pendingStart = null) }
    }

    fun stop() {
        invalidateStart()
        if (platformAction("停止探针", platform::stopAgent)) events.send("后台探针服务已停止")
    }

    fun resetUi() { invalidateStart(); update { it.copy(showAutoStartPrompt = false) } }

    private fun isCurrent(intent: PendingStart): Boolean = acceptedStart == intent &&
        configuration.state.value.let { it.connectionVersion == intent.configurationVersion && it.canPersist }

    private fun discardInvalidStart(intent: PendingStart) {
        if (acceptedStart != intent) return // An old completion must neither clear nor report on a successor.
        invalidateStart()
        val current = configuration.state.value
        when {
            current.writing -> events.send("配置正在处理，本次启动已取消，请保存完成后重新启动探针")
            current.connectionVersion != intent.configurationVersion -> events.send("连接配置已变化，请重新启动探针")
            else -> configuration.requireStorage("启动探针")
        }
    }

    private fun launch(intent: PendingStart) {
        if (!isCurrent(intent)) return
        invalidateStart() // Consume before calling the platform: duplicate results cannot start twice.
        if (!platformAction("启动探针", platform::startAgent)) return
        events.send("后台探针服务已启动")
        update { it.copy(showAutoStartPrompt = !configuration.state.value.autoStart.promptShown) }
    }

    fun autoStartPromptResult(accepted: Boolean) = configuration.saveAutoStart(
        accepted, promptResult = true,
        onSuccess = { update { it.copy(showAutoStartPrompt = false) } },
        onFailure = { update { it.copy(showAutoStartPrompt = false) } }
    )

    fun autoStartChanged(enabled: Boolean) = configuration.saveAutoStart(enabled, onSuccess = ::refreshPermissions)
    fun keepAliveAudioChanged(enabled: Boolean) = saveToolSettings(configuration.state.value.tools.copy(audio = enabled))
    fun floatWindowChanged(enabled: Boolean) = saveToolSettings(configuration.state.value.tools.copy(overlay = enabled))
    fun vpnTrafficChanged(enabled: Boolean) = saveToolSettings(configuration.state.value.tools.copy(
        vpn = VpnTrafficCompatibility.normalize(enabled, platform.sdkInt)
    ))

    private fun saveToolSettings(settings: KeepAliveSettings) = configuration.saveTools(settings) {
        events.sendLong("配置已保存，请在主页停止并重新启动探针以生效")
    }
    fun capabilityChanged(capability: RemoteCapability, enabled: Boolean) = configuration.saveCapability(capability, enabled) {
        platformAction("刷新运行中的远程权限", platform::refreshCapabilities)
    }

    fun refreshPermissions() {
        if (configuration.state.value.loading || permissionRefresh?.isActive == true) return
        if (!configuration.state.value.storageAvailable) {
            update { it.copy(permissions = emptyList(), permissionsLoading = false, permissionError = "配置存储不可用，请前往配置页重试读取") }
            return
        }
        update { it.copy(permissionsLoading = true) }
        permissionRefresh = scope.launch {
            try {
                val permissions = platform.readPermissions(configuration.state.value.autoStart.enabled)
                val current = configuration.state.value
                update { it.copy(
                    permissions = if (current.storageAvailable) permissions.map { item ->
                        if (item.key.isLocalToggle) item.copy(granted = current.autoStart.enabled) else item
                    } else emptyList(),
                    permissionError = if (current.storageAvailable) null else "配置存储不可用，请前往配置页重试读取"
                ) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                update { it.copy(permissionError = "权限状态读取失败，请重新进入此页面重试") }
            } finally {
                update { it.copy(permissionsLoading = false) }
            }
        }
    }

    private fun platformAction(action: String, execute: () -> Unit): Boolean = try {
        execute()
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Logger.e("RunControl: $action 失败", failure)
        events.sendLong(SafeErrorText.display("$action 失败", failure))
        false
    }
    private inline fun update(transform: (RunControlState) -> RunControlState) { mutable.value = transform(mutable.value) }

    private companion object {
        // Shared across ViewModels; an old Activity result cannot collide with a replacement's ID.
        val requestIds = AtomicLong(System.currentTimeMillis())
    }
}
