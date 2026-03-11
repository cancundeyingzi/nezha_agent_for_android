package com.nezhahq.agent.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object ConfigStore {

    private const val PREFS_FILE = "nezha_secure_prefs"

    private fun getEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun saveConfig(context: Context, server: String, port: Int, secret: String, useTLS: Boolean = true, uuid: String = "") {
        getEncryptedPrefs(context).edit().apply {
            putString("server", server)
            putInt("port", port)
            putString("secret", secret)
            putBoolean("use_tls", useTLS)
            putString("uuid", uuid)
            apply()
        }
    }

    fun getServer(context: Context): String = getEncryptedPrefs(context).getString("server", "") ?: ""
    fun getPort(context: Context): Int = getEncryptedPrefs(context).getInt("port", 5555)
    fun getSecret(context: Context): String = getEncryptedPrefs(context).getString("secret", "") ?: ""
    fun getUuid(context: Context): String = getEncryptedPrefs(context).getString("uuid", "") ?: ""
    fun getUseTls(context: Context): Boolean = getEncryptedPrefs(context).getBoolean("use_tls", true)
    
    fun hasValidConfig(context: Context): Boolean {
        return getServer(context).isNotEmpty() && getSecret(context).isNotEmpty() && getUuid(context).isNotEmpty()
    }
}
