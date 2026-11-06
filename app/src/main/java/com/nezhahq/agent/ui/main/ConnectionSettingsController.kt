package com.nezhahq.agent.ui.main

import com.nezhahq.agent.ui.ClipboardConfigParser
import com.nezhahq.agent.ui.ParsedUuid
import com.nezhahq.agent.ui.UiEvents
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class ShizukuRequestStatus(val status: String, val message: String? = null)

internal interface ConnectionSettingsPlatform {
    fun readClipboard(maxChars: Int): String
    fun requestShizukuPermission(requestCode: Int): ShizukuRequestStatus
}

internal class ConnectionSettingsController(
    private val configuration: UiConfigurationCoordinator,
    private val platform: ConnectionSettingsPlatform,
    private val events: UiEvents,
    private val invalidateStart: () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val newUuid: () -> String = { UUID.randomUUID().toString() }
) {
    private val mutable = MutableStateFlow(ConnectionSettingsState())
    val state = mutable.asStateFlow()

    fun serverChanged(value: String) = edit { it.copy(server = value) }
    fun portChanged(value: String) = edit { it.copy(port = value) }
    fun secretChanged(value: String) = edit { it.copy(secret = value) }
    fun uuidChanged(value: String) = edit { it.copy(uuid = value) }
    fun tlsChanged(value: Boolean) = edit { it.copy(useTls = value) }

    fun rootModeChanged(enabled: Boolean, requestCode: Int) {
        if (!edit { it.copy(rootMode = enabled) }) return
        if (!enabled) { clearStatus(); return }
        val result = platform.requestShizukuPermission(requestCode)
        mutable.value = ConnectionSettingsState(result.status)
        result.message?.let(events::sendLong)
    }

    fun permissionResult(granted: Boolean) {
        if (!configuration.state.value.canEditDraft || !configuration.state.value.connection.rootMode) return
        if (granted) {
            mutable.value = ConnectionSettingsState("✅ Shizuku 已授权")
            events.send("Shizuku 权限已授予，ADB Shell 模式可用")
        } else {
            edit { it.copy(rootMode = false) }
            mutable.value = ConnectionSettingsState("❌ Shizuku 授权被拒绝")
            events.send("Shizuku 权限被拒绝，已关闭高权限模式")
        }
    }

    fun parseClipboard() {
        if (!configuration.state.value.canEditDraft) return
        val input = platform.readClipboard(64 * 1024)
        if (input.isBlank()) { events.send("剪贴板中没有可解析的安装脚本"); return }
        configuration.parseConnection(transform = { current ->
            val parsed = withContext(io) { ClipboardConfigParser.parse(input) }
            val uuid = when (val value = parsed.uuid) {
                is ParsedUuid.Found -> value.value
                ParsedUuid.Placeholder -> newUuid()
                ParsedUuid.Absent -> current.uuid.takeUnless { it.isBlank() || it == "''" || it == "\"\"" } ?: newUuid()
            }
            current.copy(server = parsed.server ?: current.server, port = parsed.port ?: current.port,
                secret = parsed.secret ?: current.secret, useTls = parsed.useTls ?: current.useTls, uuid = uuid)
        }, onApplied = { invalidateStart(); events.send("配置已解析完成") })
    }

    fun clearStatus() { mutable.value = ConnectionSettingsState() }

    private fun edit(transform: (ConnectionFields) -> ConnectionFields): Boolean {
        val edited = configuration.editConnection(transform)
        if (edited) invalidateStart()
        return edited
    }
}
