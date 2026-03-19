package com.nezhahq.agent.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object ConfigStore {

    private const val PREFS_FILE = "nezha_secure_prefs"

    @Volatile
    private var prefsInstance: SharedPreferences? = null

    /**
     * 获取加密的 SharedPreferences 实例。
     * 采用双重检查锁（Double-Checked Locking）单例模式，避免由于反复初始化
     * EncryptedSharedPreferences 和读写 KeyStore 产生严重的性能开销和主线程卡顿。
     * [Security Audit] 使用了 applicationContext 防内存溢出，同时利用 AES256_GCM 保证数据存储安全。
     */
    private fun getEncryptedPrefs(context: Context): SharedPreferences {
        return prefsInstance ?: synchronized(this) {
            prefsInstance ?: run {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()

                val prefs = EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                prefsInstance = prefs
                prefs
            }
        }
    }

    /**
     * 保存连接配置和基础工具设置。
     *
     * 注意：enable_vpn_traffic 由 [setEnableVpnTraffic] 独立管理，
     * 不在此方法中写入，防止全量覆写导致设置被重置。
     */
    fun saveConfig(context: Context, server: String, port: Int, secret: String, useTLS: Boolean = true, uuid: String = "", rootMode: Boolean = false, enableKeepAliveAudio: Boolean = false) {
        getEncryptedPrefs(context).edit().apply {
            putString("server", server)
            putInt("port", port)
            putString("secret", secret)
            putBoolean("use_tls", useTLS)
            putString("uuid", uuid)
            putBoolean("root_mode", rootMode)
            putBoolean("enable_keep_alive_audio", enableKeepAliveAudio)
            // enable_float_window 由 setEnableFloatWindow() 独立管理
            // enable_vpn_traffic 由 setEnableVpnTraffic() 独立管理
            apply()
        }
    }

    fun getServer(context: Context): String = getEncryptedPrefs(context).getString("server", "") ?: ""
    fun getPort(context: Context): Int = getEncryptedPrefs(context).getInt("port", 5555)
    fun getSecret(context: Context): String = getEncryptedPrefs(context).getString("secret", "") ?: ""
    fun getUuid(context: Context): String = getEncryptedPrefs(context).getString("uuid", "") ?: ""
    fun getUseTls(context: Context): Boolean = getEncryptedPrefs(context).getBoolean("use_tls", true)
    fun getRootMode(context: Context): Boolean = getEncryptedPrefs(context).getBoolean("root_mode", false)
    fun getEnableKeepAliveAudio(context: Context): Boolean = getEncryptedPrefs(context).getBoolean("enable_keep_alive_audio", false)
    fun getEnableFloatWindow(context: Context): Boolean = getEncryptedPrefs(context).getBoolean("enable_float_window", false)
    fun getEnableVpnTraffic(context: Context): Boolean = getEncryptedPrefs(context).getBoolean("enable_vpn_traffic", false)
    fun getEnableAutoStart(context: Context): Boolean = getEncryptedPrefs(context).getBoolean("enable_auto_start", false)
    fun getHasShownAutoStartPrompt(context: Context): Boolean = getEncryptedPrefs(context).getBoolean("has_shown_auto_start_prompt", false)

    fun setEnableAutoStart(context: Context, enable: Boolean) {
        getEncryptedPrefs(context).edit().putBoolean("enable_auto_start", enable).apply()
    }

    fun setHasShownAutoStartPrompt(context: Context, shown: Boolean) {
        getEncryptedPrefs(context).edit().putBoolean("has_shown_auto_start_prompt", shown).apply()
    }

    /** 悬浮窗开关 — 独立保存，不受 saveConfig 全量覆写影响 */
    fun setEnableFloatWindow(context: Context, enable: Boolean) {
        getEncryptedPrefs(context).edit().putBoolean("enable_float_window", enable).apply()
    }

    /** VPN 流量计量开关 — 独立保存，不受 saveConfig 全量覆写影响 */
    fun setEnableVpnTraffic(context: Context, enable: Boolean) {
        getEncryptedPrefs(context).edit().putBoolean("enable_vpn_traffic", enable).apply()
    }

    fun hasValidConfig(context: Context): Boolean {
        return getServer(context).isNotEmpty() && getSecret(context).isNotEmpty() && getUuid(context).isNotEmpty()
    }
}
