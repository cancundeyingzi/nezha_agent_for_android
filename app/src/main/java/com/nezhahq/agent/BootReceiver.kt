package com.nezhahq.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.nezhahq.agent.service.AgentService
import com.nezhahq.agent.util.ConfigStore
import com.nezhahq.agent.util.Logger

/**
 * 开机自启与应用更新后自动恢复的广播接收器。
 *
 * ## 监听事件
 *  - [Intent.ACTION_BOOT_COMPLETED]：设备完成开机（包括安全模式下的重启）
 *  - [Intent.ACTION_MY_PACKAGE_REPLACED]：本应用自身被更新替换后触发
 *
 * ## 触发条件
 * 仅当 [ConfigStore.hasValidConfig] 返回 true（即用户已配置过服务端信息）时，
 * 才自动重启探针服务，避免在未配置时产生无意义的前台服务。
 *
 * ## 注意事项
 * - BOOT_COMPLETED 是 Android 8.0+ 豁免列表中的隐式广播，可正常接收。
 * - android:exported="false" 防止第三方 App 伪造广播触发服务。
 * - 使用 [ContextCompat.startForegroundService] 兼容 Android O 以上前台服务要求。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // 仅处理关心的两个广播事件
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        // 若用户尚未配置探针，无需自动启动
        if (!ConfigStore.hasValidConfig(context)) {
            Logger.i("BootReceiver: 收到 $action，但探针尚未配置，跳过自动启动。")
            return
        }

        // 若用户未启用自启动功能，跳过
        if (!ConfigStore.getEnableAutoStart(context)) {
            Logger.i("BootReceiver: 收到 $action，但用户未启用自启动开关，跳过自动启动。")
            return
        }

        Logger.i("BootReceiver: 收到 $action，正在自动恢复探针后台服务...")
        val serviceIntent = Intent(context, AgentService::class.java)
        // ContextCompat.startForegroundService 会在 Android O+ 自动选用正确的启动方式
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
