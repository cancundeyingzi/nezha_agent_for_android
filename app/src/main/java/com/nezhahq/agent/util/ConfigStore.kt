package com.nezhahq.agent.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.nezhahq.agent.core.config.StorageStatus
import com.nezhahq.agent.core.model.SimulatedDeviceConfig
import com.nezhahq.agent.core.security.RootModeMutationCoordinator
import java.io.File

/**
 * Traditional app-private configuration storage.
 *
 * Values intentionally use ordinary [SharedPreferences], matching the upstream agent's plaintext
 * configuration model. Android sandbox permissions still protect the file on non-rooted devices,
 * but a root-capable process can read secrets from it.
 *
 * `internal` on purpose: this is the storage detail behind
 * [com.nezhahq.agent.core.config.ConfigRepository], not an API other modules may reach for.
 */
internal object ConfigStore {
    // Keep the historical fallback filename so devices that previously entered fallback mode use
    // those values directly instead of creating another empty preferences file.
    private const val PREFS_FILE = "nezha_secure_prefs_fallback"
    private const val LEGACY_ENCRYPTED_PREFS_FILE = "nezha_secure_prefs"
    private const val PLAINTEXT_MIGRATION_COMPLETE = "__plaintext_config_v1"

    private val runtimeLock = Any()
    private val rootModeMutations = RootModeMutationCoordinator(
        applyAuthorization = RootShell::configureAuthorization
    )

    @Volatile
    private var runtime: Runtime? = null

    /** Opens plaintext storage and non-destructively imports readable legacy encrypted values. */
    fun initialize(context: Context): StorageStatus = runtimeFor(context).coordinator.initialize()

    /** Clears live configuration and recreates a verified-empty plaintext store. */
    fun resetConfigurationStorage(context: Context): Boolean =
        rootModeMutations.persistAndApply(enabled = false) {
            runtimeFor(context).coordinator.reset()
        }

    /**
     * Applies the stored root mode under the same ordering boundary used by root-mode writes.
     */
    fun synchronizeRootAuthorization(
        context: Context,
        storageStatus: StorageStatus = initialize(context)
    ): Boolean = rootModeMutations.loadAndApply {
        storageStatus != StorageStatus.UNAVAILABLE && getRootMode(context)
    }

    /**
     * Writes the connection settings together with the root-mode grant they were confirmed under.
     *
     * Tool settings are deliberately absent: they belong to [saveToolSettings], so a connection
     * write can never clobber a concurrent tool-settings write.
     */
    fun saveConfig(
        context: Context,
        server: String,
        port: Int,
        secret: String,
        useTLS: Boolean = true,
        uuid: String = "",
        rootMode: Boolean = false
    ): Boolean = rootModeMutations.persistAndApply(rootMode) {
        commit(context) { editor ->
            editor.putString("server", server)
            editor.putInt("port", port)
            editor.putString("secret", secret)
            editor.putBoolean("use_tls", useTLS)
            editor.putString("uuid", uuid)
            editor.putBoolean("root_mode", rootMode)
        }
    }

    fun getServer(context: Context): String = read(context, "") {
        it.getString("server", "") ?: ""
    }

    fun getPort(context: Context): Int = read(context, 5555) { it.getInt("port", 5555) }

    fun getSecret(context: Context): String = read(context, "") {
        it.getString("secret", "") ?: ""
    }

    fun getUuid(context: Context): String = read(context, "") {
        it.getString("uuid", "") ?: ""
    }

    fun getUseTls(context: Context): Boolean = read(context, true) {
        it.getBoolean("use_tls", true)
    }

    fun getRootMode(context: Context): Boolean = read(context, false) {
        it.getBoolean("root_mode", false)
    }

    fun getEnableKeepAliveAudio(context: Context): Boolean = read(context, false) {
        it.getBoolean("enable_keep_alive_audio", false)
    }

    fun getEnableFloatWindow(context: Context): Boolean = read(context, false) {
        it.getBoolean("enable_float_window", false)
    }

    fun getEnableVpnTraffic(context: Context): Boolean = read(context, false) {
        it.getBoolean("enable_vpn_traffic", false)
    }

    fun getEnableAutoStart(context: Context): Boolean = read(context, false) {
        it.getBoolean("enable_auto_start", false)
    }

    fun getHasShownAutoStartPrompt(context: Context): Boolean = read(context, false) {
        it.getBoolean("has_shown_auto_start_prompt", false)
    }

    fun getEnableRemoteCommand(context: Context): Boolean = read(context, false) {
        it.getBoolean("enable_remote_command", false)
    }

    fun getEnableRemoteFileManager(context: Context): Boolean = read(context, false) {
        it.getBoolean("enable_remote_file_manager", false)
    }

    fun getEnableRemoteNat(context: Context): Boolean = read(context, false) {
        it.getBoolean("enable_remote_nat", false)
    }

    fun getSimulatorServer(context: Context): String = read(context, "") { prefs ->
        if (prefs.contains("simulator_server")) {
            prefs.getString("simulator_server", "") ?: ""
        } else {
            prefs.getString("server", "") ?: ""
        }
    }

    fun getSimulatorPort(context: Context): Int = read(context, 5555) { prefs ->
        if (prefs.contains("simulator_port")) {
            prefs.getInt("simulator_port", 5555)
        } else {
            prefs.getInt("port", 5555)
        }
    }

    fun getSimulatorSecret(context: Context): String = read(context, "") { prefs ->
        if (prefs.contains("simulator_secret")) {
            prefs.getString("simulator_secret", "") ?: ""
        } else {
            prefs.getString("secret", "") ?: ""
        }
    }

    fun getSimulatorUseTls(context: Context): Boolean = read(context, true) { prefs ->
        if (prefs.contains("simulator_use_tls")) {
            prefs.getBoolean("simulator_use_tls", true)
        } else {
            prefs.getBoolean("use_tls", true)
        }
    }

    fun getSimulatorThreadCount(context: Context): Int =
        read(context, SimulatedDeviceConfig.DEFAULT_THREAD_COUNT) { prefs ->
            prefs.getInt("simulator_thread_count", SimulatedDeviceConfig.DEFAULT_THREAD_COUNT)
                .coerceIn(1, SimulatedDeviceConfig.MAX_THREAD_COUNT)
        }

    fun setEnableAutoStart(context: Context, enable: Boolean): Boolean = commit(context) {
        it.putBoolean("enable_auto_start", enable)
    }

    fun saveSimulatorConfig(
        context: Context,
        server: String,
        port: Int,
        secret: String,
        useTls: Boolean,
        threadCount: Int
    ): Boolean = commit(context) { editor ->
        editor.putString("simulator_server", server)
        editor.putInt("simulator_port", port)
        editor.putString("simulator_secret", secret)
        editor.putBoolean("simulator_use_tls", useTls)
        editor.putInt(
            "simulator_thread_count",
            threadCount.coerceIn(1, SimulatedDeviceConfig.MAX_THREAD_COUNT)
        )
    }

    fun setEnableRemoteCommand(context: Context, enable: Boolean): Boolean = commit(context) {
        it.putBoolean("enable_remote_command", enable)
    }

    fun setEnableRemoteFileManager(context: Context, enable: Boolean): Boolean = commit(context) {
        it.putBoolean("enable_remote_file_manager", enable)
    }

    fun setEnableRemoteNat(context: Context, enable: Boolean): Boolean = commit(context) {
        it.putBoolean("enable_remote_nat", enable)
    }

    fun saveToolSettings(
        context: Context,
        enableKeepAliveAudio: Boolean,
        enableFloatWindow: Boolean,
        enableVpnTraffic: Boolean
    ): Boolean = commit(context) { editor ->
        editor.putBoolean("enable_keep_alive_audio", enableKeepAliveAudio)
        editor.putBoolean("enable_float_window", enableFloatWindow)
        editor.putBoolean("enable_vpn_traffic", enableVpnTraffic)
    }

    fun saveAutoStartPromptResult(context: Context, enable: Boolean): Boolean = commit(context) {
        it.putBoolean("enable_auto_start", enable)
        it.putBoolean("has_shown_auto_start_prompt", true)
    }

    fun hasValidConfig(context: Context): Boolean = read(context, false) { prefs ->
        !prefs.getString("server", "").isNullOrEmpty() &&
            !prefs.getString("secret", "").isNullOrEmpty() &&
            !prefs.getString("uuid", "").isNullOrEmpty()
    }

    private fun runtimeFor(context: Context): Runtime {
        runtime?.let { return it }
        return synchronized(runtimeLock) {
            runtime ?: Runtime(context.applicationContext).also { runtime = it }
        }
    }

    private inline fun <T> read(
        context: Context,
        defaultValue: T,
        crossinline reader: (SharedPreferences) -> T
    ): T {
        val currentRuntime = runtimeFor(context)
        currentRuntime.coordinator.initialize()
        return currentRuntime.coordinator.read(defaultValue) { reader(it) }
    }

    /** SharedPreferences.commit is intentional: callers must know whether persistence succeeded. */
    @SuppressLint("ApplySharedPref")
    private inline fun commit(
        context: Context,
        crossinline changes: (SharedPreferences.Editor) -> Unit
    ): Boolean {
        val currentRuntime = runtimeFor(context)
        if (currentRuntime.coordinator.initialize() == StorageStatus.UNAVAILABLE) return false
        return currentRuntime.coordinator.write { prefs ->
            val editor = prefs.edit()
            changes(editor)
            editor.putBoolean(PLAINTEXT_MIGRATION_COMPLETE, true)
            editor.commit()
        }
    }

    private class Runtime(context: Context) {
        val coordinator = ConfigurationStorageCoordinator(
            AndroidConfigurationStorageOperations(context)
        )
    }

    private class AndroidConfigurationStorageOperations(
        private val context: Context
    ) : ConfigurationStorageOperations<SharedPreferences> {
        private val legacyEncryptedFiles =
            preferencesFiles(context, LEGACY_ENCRYPTED_PREFS_FILE)

        override fun openPlainStorage(): SharedPreferences =
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

        override fun isMigrationComplete(storage: SharedPreferences): Boolean =
            storage.getBoolean(PLAINTEXT_MIGRATION_COMPLETE, false)

        override fun importLegacyEncryptedStorage(
            storage: SharedPreferences
        ): LegacyImportResult {
            if (!legacyEncryptedFiles.exists()) {
                return if (markMigrationComplete(storage)) {
                    LegacyImportResult.COMPLETED
                } else {
                    LegacyImportResult.FAILED
                }
            }

            val legacyValues = try {
                createLegacyEncryptedStorage().all
            } catch (error: Exception) {
                Logger.e(
                    "ConfigStore: 旧加密配置无法读取，保留原文件并改用明文兼容存储",
                    error
                )
                return LegacyImportResult.UNREADABLE
            }

            val imported = PreferenceValuesImporter(
                AndroidPreferenceImportOperations(storage)
            ).importMissing(legacyValues)
            if (!imported) return LegacyImportResult.FAILED

            return if (markMigrationComplete(storage)) {
                Logger.i("ConfigStore: 已将旧加密配置迁移到明文兼容存储")
                LegacyImportResult.COMPLETED
            } else {
                LegacyImportResult.FAILED
            }
        }

        override fun resetPlainStorage(): SharedPreferences {
            check(deletePreferencesCompat(context, PREFS_FILE)) {
                "Unable to delete plaintext configuration"
            }
            val storage = openPlainStorage()
            check(markMigrationComplete(storage)) {
                "Unable to initialize empty plaintext configuration"
            }
            return storage
        }

        override fun isResetStorage(storage: SharedPreferences): Boolean {
            return storage.getBoolean(PLAINTEXT_MIGRATION_COMPLETE, false) &&
                storage.all.keys == setOf(PLAINTEXT_MIGRATION_COMPLETE)
        }

        private fun createLegacyEncryptedStorage(): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                LEGACY_ENCRYPTED_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        @SuppressLint("ApplySharedPref")
        private fun markMigrationComplete(storage: SharedPreferences): Boolean =
            storage.edit().putBoolean(PLAINTEXT_MIGRATION_COMPLETE, true).commit()
    }

    private class AndroidPreferenceImportOperations(
        private val targetPreferences: SharedPreferences
    ) : PreferenceImportOperations {
        override fun targetContains(key: String): Boolean = targetPreferences.contains(key)

        override fun targetEditor(): PreferenceValueEditor =
            SharedPreferencesValueEditor(targetPreferences.edit())
    }

    private class SharedPreferencesValueEditor(
        private val editor: SharedPreferences.Editor
    ) : PreferenceValueEditor {
        override fun putString(key: String, value: String) {
            editor.putString(key, value)
        }

        override fun putStringSet(key: String, value: Set<String>) {
            editor.putStringSet(key, value)
        }

        override fun putInt(key: String, value: Int) {
            editor.putInt(key, value)
        }

        override fun putLong(key: String, value: Long) {
            editor.putLong(key, value)
        }

        override fun putFloat(key: String, value: Float) {
            editor.putFloat(key, value)
        }

        override fun putBoolean(key: String, value: Boolean) {
            editor.putBoolean(key, value)
        }

        @SuppressLint("ApplySharedPref")
        override fun commit(): Boolean = editor.commit()
    }

    private fun deletePreferencesCompat(context: Context, name: String): Boolean {
        return PreferencesDeletionCoordinator(
            AndroidPreferencesDeletionOperations(context, name)
        ).delete()
    }

    private class AndroidPreferencesDeletionOperations(
        private val context: Context,
        private val name: String
    ) : PreferencesDeletionOperations {
        private val files = preferencesFiles(context, name)

        override val supportsPlatformDelete: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

        @SuppressLint("ApplySharedPref")
        override fun clearCacheSynchronously(): Boolean {
            return context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }

        override fun deleteWithPlatform(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.deleteSharedPreferences(name)
            } else {
                false
            }
        }

        override fun deleteFiles(): Boolean = files.delete()
    }

    private fun preferencesFiles(context: Context, name: String): SharedPreferencesFiles {
        return SharedPreferencesFiles(File(context.applicationInfo.dataDir), name)
    }
}
