package com.nezhahq.agent.util

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.nezhahq.agent.R
import com.nezhahq.agent.appContainer
import androidx.core.content.ContextCompat

/**
 * 权限检测工具类 — 集中检测应用所需的所有关键权限。
 *
 * ## 架构说明
 * 将分散在各处的权限检测逻辑统一收敛到此工具类，
 * 工具页面仅需调用 [getAllPermissionStatus] 即可获取全部权限的授权状态列表。
 *
 * ## 安全性审计
 * - 所有检测均为只读操作，不触发任何权限请求弹窗
 * - 无障碍服务检测使用 Settings.Secure 标准 API，不读取其他应用信息
 * - 电池优化检测在 API 23 以下直接返回 true（无此限制）
 */
object PermissionChecker {

    /**
     * 权限项的类型安全标识。
     *
     * 取代原先的 `String` key：UI 的 `when (item.key)` 因此能被编译器强制穷举（无 else），
     * 新增权限项时若漏了分支会**编译失败**，而不是像字面量那样静默变成“按钮点了没反应”。
     */
    enum class PermissionKey {
        SMS, USAGE_STATS, ACCESSIBILITY, OVERLAY, BATTERY, NOTIFICATION, AUTO_START, STORAGE;

        /**
         * 是否为“应用内本地开关”，而非跳系统设置页 / 弹运行时授权。
         *
         * 目前仅开机自启（[AUTO_START]）：其状态存在本地配置里、点击即切换，因此按钮文案是“启用”，
         * 且受 `canEditConfig`（配置可写）约束。其余权限都跳系统页或弹系统授权框，不受此约束。
         * UI 的两处特判（按钮可用性、按钮文案）都据此判断，避免再散落字面量 "auto_start"。
         */
        val isLocalToggle: Boolean get() = this == AUTO_START
    }

    /**
     * 单项权限状态数据类。
     *
     * @param name   权限显示名称（中文）
     * @param key    权限类型标识（见 [PermissionKey]）
     * @param granted 是否已授予
     * @param description 可选的二级说明。用于对隐私敏感的权限追加披露（如 SMS，见 B11）；
     *                    为 null 时列表项不显示二级文案。
     */
    data class PermissionItem(
        val name: String,
        val key: PermissionKey,
        val granted: Boolean,
        val description: String? = null
    )

    /**
     * 一次性检测所有关键权限的授予状态。
     *
     * @param context 应用上下文
     * @return 权限状态列表，顺序固定：短信 → 使用情况 → 无障碍 → 悬浮窗 → 电池优化 → 通知 → 开机自启
     */
    fun getAllPermissionStatus(context: Context): List<PermissionItem> {
        return listOf(
            PermissionItem(
                name = "短信读取权限",
                key = PermissionKey.SMS,
                granted = checkSmsPermission(context),
                // B11：短信是 OTP 级敏感数据，向用户明确披露它被 @agent sms 远程读取。
                // 文案取自 strings.xml，不硬编码。
                description = context.getString(R.string.sms_permission_list_desc)
            ),
            PermissionItem(
                name = "使用情况访问权限",
                key = PermissionKey.USAGE_STATS,
                granted = checkUsageStatsPermission(context)
            ),
            PermissionItem(
                name = "无障碍服务",
                key = PermissionKey.ACCESSIBILITY,
                granted = checkAccessibilityEnabled(context)
            ),
            PermissionItem(
                name = "悬浮窗权限",
                key = PermissionKey.OVERLAY,
                granted = checkOverlayPermission(context)
            ),
            PermissionItem(
                name = "电池优化豁免",
                key = PermissionKey.BATTERY,
                granted = checkBatteryOptimization(context)
            ),
            PermissionItem(
                name = "通知权限",
                key = PermissionKey.NOTIFICATION,
                granted = checkNotificationPermission(context)
            ),
            PermissionItem(
                name = "开机自启动",
                key = PermissionKey.AUTO_START,
                granted = context.appContainer.configRepository.loadAutoStartState().enabled
            ),
            PermissionItem(
                name = "所有文件访问",
                key = PermissionKey.STORAGE,
                granted = checkStoragePermission(context)
            )
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 各项权限检测私有方法
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 检测 READ_SMS 运行时权限。
     * 标准危险权限，通过 ContextCompat.checkSelfPermission 检测。
     */
    private fun checkSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检测使用情况访问权限（PACKAGE_USAGE_STATS）。
     *
     * 该权限属于 AppOps 特殊权限，不能通过 checkSelfPermission 检测，
     * 需要使用 AppOpsManager.checkOpNoThrow 进行检查。
     * 返回 MODE_ALLOWED 表示已授权。
     */
    private fun checkUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 检测本应用的无障碍服务 (KeepAliveAccessibilityService) 是否已启用。
     *
     * 通过读取 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 系统设置，
     * 匹配 "{包名}/{服务完整类名}" 格式的字符串来判断。
     */
    private fun checkAccessibilityEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        // 完整服务标识符格式：com.nezhahq.agent/.service.KeepAliveAccessibilityService
        // 或 com.nezhahq.agent/com.nezhahq.agent.service.KeepAliveAccessibilityService
        val packageName = context.packageName
        val shortServiceId = "$packageName/.service.KeepAliveAccessibilityService"
        val fullServiceId = "$packageName/com.nezhahq.agent.service.KeepAliveAccessibilityService"

        return enabledServices.contains(shortServiceId, ignoreCase = true) ||
                enabledServices.contains(fullServiceId, ignoreCase = true)
    }

    /**
     * 检测悬浮窗权限（SYSTEM_ALERT_WINDOW）。
     * API 23+ 使用 Settings.canDrawOverlays()，低版本默认返回 true。
     */
    private fun checkOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true // API 23 以下无需此权限
        }
    }

    /**
     * 检测电池优化豁免（忽略电池优化白名单）。
     * API 23+ 使用 PowerManager.isIgnoringBatteryOptimizations()。
     */
    private fun checkBatteryOptimization(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true // API 23 以下无 Doze 模式限制
        }
    }

    /**
     * 检测通知权限。
     * 使用 NotificationManagerCompat 统一兼容 API 33 以下和以上版本。
     */
    private fun checkNotificationPermission(context: Context): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * 检测文件管理器所需的存储访问权限。
     *
     * - Android 11+ (API 30)：检查 MANAGE_EXTERNAL_STORAGE 权限
     *   （需用户在系统设置中手动授权「所有文件访问」）。
     *   此权限可绕过 Scoped Storage FUSE 过滤，使 File.listFiles()
     *   在非 Root 模式下也能返回完整的文件和目录列表。
     * - Android 10 及以下：检查 READ_EXTERNAL_STORAGE 运行时权限。
     */
    private fun checkStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
