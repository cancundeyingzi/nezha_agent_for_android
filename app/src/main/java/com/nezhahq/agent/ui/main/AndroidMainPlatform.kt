package com.nezhahq.agent.ui.main

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.nezhahq.agent.service.AgentService
import com.nezhahq.agent.ui.UiEvents
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.SafeErrorText
import com.nezhahq.agent.util.PermissionChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** Narrow Android bridge; controller tests need neither an Application nor static platform mocks. */
internal class AndroidMainPlatform(context: Context, private val events: UiEvents) : ConnectionSettingsPlatform, RunControlPlatform {
    private val context = context.applicationContext
    override val sdkInt: Int get() = Build.VERSION.SDK_INT

    override fun readClipboard(maxChars: Int): String = context.getSystemService(ClipboardManager::class.java)
        ?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)
        ?.take(maxChars)?.toString().orEmpty()

    override fun requestShizukuPermission(requestCode: Int): ShizukuRequestStatus = try {
        when {
            !Shizuku.pingBinder() -> ShizukuRequestStatus("⚠️ Shizuku 未运行（可使用 Root 则忽略此提示）",
                "Shizuku 未运行。如设备已 Root 可忽略此提示；\n否则请先安装并启动 Shizuku 应用。")
            Shizuku.isPreV11() -> ShizukuRequestStatus("⚠️ Shizuku 版本过低，不受支持", "当前 Shizuku 版本过低，请升级到 v11 以上")
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED ->
                ShizukuRequestStatus("✅ Shizuku 已授权（UID=${Shizuku.getUid()}）")
            Shizuku.shouldShowRequestPermissionRationale() -> ShizukuRequestStatus(
                "❌ Shizuku 授权被永久拒绝，请在 Shizuku 应用中手动授权",
                "Shizuku 权限已被永久拒绝，请打开 Shizuku 应用，在「授权管理」中手动为本应用授权。")
            else -> { Shizuku.requestPermission(requestCode); ShizukuRequestStatus("⏳ 等待 Shizuku 授权...") }
        }
    } catch (failure: Exception) {
        Logger.e("ConnectionSettings: Shizuku 检测失败", failure)
        ShizukuRequestStatus("⚠️ Shizuku 检测异常", SafeErrorText.display("Shizuku 检测失败", failure) + "\n如设备已 Root 可忽略此提示。")
    }

    override fun startAgent() {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!power.isIgnoringBatteryOptimizations(context.packageName)) {
            try {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                events.sendLong("请在弹出的系统对话框中选择 '允许' 以保证后台保活")
            } catch (_: Exception) {
                try {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                } catch (_: Exception) { /* A settings UI failure does not prevent service startup. */ }
            }
        }
        AgentService.startOrReload(context)
    }

    override fun stopAgent() { context.stopService(Intent(context, AgentService::class.java)) }
    override fun refreshCapabilities() { AgentService.requestCapabilityRefreshIfRunning(context) }
    override suspend fun readPermissions(autoStartEnabled: Boolean) = withContext(Dispatchers.IO) {
        PermissionChecker.getAllPermissionStatus(context, autoStartEnabled)
    }
}
