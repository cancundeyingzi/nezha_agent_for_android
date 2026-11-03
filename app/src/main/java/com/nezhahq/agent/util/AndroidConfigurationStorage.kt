package com.nezhahq.agent.util

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Construction does no filesystem or Keystore work; every method is called on an IO path. */
internal class AndroidConfigurationKeyProvider(
    private val alias: String = "nezha_configuration_aes_gcm_v1"
) : ConfigurationKeyProvider {
    override fun existingKey(): SecretKey = keyStore().getKey(alias, null) as? SecretKey
        ?: throw IOException("Configuration key is missing")

    override fun createIfAbsent() {
        val store = keyStore()
        if (store.containsAlias(alias)) {
            check(store.getKey(alias, null) is SecretKey) { "Configuration key is unavailable" }
            return
        }
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }.generateKey()
    }

    override fun replaceForExplicitReset() {
        keyStore().deleteEntry(alias)
        createIfAbsent()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}

/**
 * Neither migration source receives new values. Names/alias can be isolated for device tests;
 * production keeps the historical names. The SharedPreferences cache is opened only when needed
 * for old encrypted decoding or for cleanup after target verification.
 */
internal class AndroidLegacyConfigurationOperations(
    private val context: Context,
    private val plainPrefsName: String = PLAIN_PREFS,
    private val encryptedPrefsName: String = ENCRYPTED_PREFS,
    private val legacyMasterKeyAlias: String = MasterKey.DEFAULT_MASTER_KEY_ALIAS
) : LegacyConfigurationOperations {
    override fun readPlaintext(): LegacyPlaintextConfiguration {
        val values = files(plainPrefsName).readValues().entries
        return LegacyPlaintextConfiguration(
            ConfigurationValues.from(values.filterKeys { it != MIGRATION_MARKER }),
            values[MIGRATION_MARKER] == true
        )
    }

    override fun encryptedExists(): Boolean = files(encryptedPrefsName).exists()

    @Suppress("DEPRECATION")
    override fun readEncrypted(): ConfigurationValues {
        // Verify key/keysets before opening the old helper, which can create them when absent.
        // The alias overload avoids constructing a new MasterKey during a read-only migration.
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        check(store.containsAlias(legacyMasterKeyAlias)) { "Legacy key is missing" }
        val source = context.getSharedPreferences(encryptedPrefsName, Context.MODE_PRIVATE)
        check(source.contains("__androidx_security_crypto_encrypted_prefs_key_keyset__") &&
            source.contains("__androidx_security_crypto_encrypted_prefs_value_keyset__")) {
            "Legacy keysets are missing"
        }
        val encrypted = EncryptedSharedPreferences.create(
            encryptedPrefsName, legacyMasterKeyAlias, context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        return ConfigurationValues.from(encrypted.all.filterKeys { it != MIGRATION_MARKER })
    }

    override fun deletePlaintext(): Boolean = delete(plainPrefsName)
    override fun deleteEncrypted(): Boolean = delete(encryptedPrefsName)
    private fun files(name: String) = SharedPreferencesFiles(File(context.applicationInfo.dataDir), name)

    private fun delete(name: String): Boolean = PreferencesDeletionCoordinator(
        object : PreferencesDeletionOperations {
            override val supportsPlatformDelete: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

            @SuppressLint("ApplySharedPref")
            override fun clearCacheSynchronously(): Boolean =
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()

            override fun deleteWithPlatform(): Boolean =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) context.deleteSharedPreferences(name) else false

            override fun deleteFiles(): Boolean = files(name).delete()
        }
    ).delete()

    companion object {
        const val PLAIN_PREFS = "nezha_secure_prefs_fallback"
        const val ENCRYPTED_PREFS = "nezha_secure_prefs"
        const val MIGRATION_MARKER = "__plaintext_config_v1"
    }
}
