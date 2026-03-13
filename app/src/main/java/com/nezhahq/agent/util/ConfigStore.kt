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

    fun saveConfig(context: Context, server: String, port: Int, secret: String, useTLS: Boolean = true, uuid: String = "", rootMode: Boolean = false, enableKeepAliveAudio: Boolean = false, enableFloatWindow: Boolean = false) {
        getEncryptedPrefs(context).edit().apply {
            putString("server", server)
            putInt("port", port)
            putString("secret", secret)
            putBoolean("use_tls", useTLS)
            putString("uuid", uuid)
            putBoolean("root_mode", rootMode)
            putBoolean("enable_keep_alive_audio", enableKeepAliveAudio)
            putBoolean("enable_float_window", enableFloatWindow)
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
    
    fun hasValidConfig(context: Context): Boolean {
        return getServer(context).isNotEmpty() && getSecret(context).isNotEmpty() && getUuid(context).isNotEmpty()
    }
}
