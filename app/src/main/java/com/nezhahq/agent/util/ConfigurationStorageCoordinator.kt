package com.nezhahq.agent.util

import com.nezhahq.agent.core.config.StorageStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * One-shot completion signal for the first background initialization. Synchronous reads themselves
 * never wait for IO; the initial UI load awaits this signal so temporary defaults are not mistaken
 * for saved values. Recovery keeps the signal completed and reads the current verified snapshot.
 */
internal class ConfigurationReadiness {
    private val ready = CompletableDeferred<StorageStatus>()

    /** True once [complete] has run, i.e. once the storage lock is no longer held for migration. */
    val isReady: Boolean
        get() = ready.isCompleted

    /**
     * Publishes the outcome of the first initialization; the first call wins, later ones are noise.
     *
     * Every path out of initialization has to reach this, failures included. A waiter that is never
     * resumed is a UI frozen on its loading state for the life of the process.
     */
    fun complete(status: StorageStatus) {
        ready.complete(status)
    }

    suspend fun await(): StorageStatus = ready.await()

    /**
     * [await] bounded by [timeoutMillis], returning null when the deadline came first.
     *
     * Only the waiting is abandoned, never the signal: the timeout cancels this call's continuation,
     * so the deferred stays active and a later [await] still resolves. Callers use it to say
     * something about an unusually slow migration without giving up on the result.
     */
    suspend fun awaitWithin(timeoutMillis: Long): StorageStatus? =
        withTimeoutOrNull(timeoutMillis) { ready.await() }
}

/** Plaintext is a read-only migration source; its marker seals the previous import decision. */
internal data class LegacyPlaintextConfiguration(
    val values: ConfigurationValues,
    val migrationComplete: Boolean
)

internal interface LegacyConfigurationOperations {
    fun readPlaintext(): LegacyPlaintextConfiguration
    fun encryptedExists(): Boolean
    fun readEncrypted(): ConfigurationValues
    fun deletePlaintext(): Boolean
    fun deleteEncrypted(): Boolean
}

internal data class PublishedConfiguration(
    val status: StorageStatus = StorageStatus.UNAVAILABLE,
    val document: StoredConfiguration? = null
)

/**
 * Serializes lifecycle and persistence on background callers. Readers only see a volatile,
 * independently verified snapshot and never acquire the IO/Keystore lock.
 */
internal class ConfigurationStorageCoordinator(
    private val persistence: ConfigurationPersistence,
    private val legacy: LegacyConfigurationOperations,
    private val rootMutations: com.nezhahq.agent.core.security.RootModeMutationCoordinator,
    private val onFailure: (Exception) -> Unit = {}
) {
    private val lock = Any()
    private var attempted = false
    // Keep this fact even after a failed write/read clears the published snapshot. A missing file
    // must not look like first install merely because it disappeared within the same process.
    private var knownTarget = false
    @Volatile private var published = PublishedConfiguration()

    fun current(): PublishedConfiguration = published
    fun currentStatus(): StorageStatus = published.status

    fun initialize(retry: Boolean = false): StorageStatus {
        var result = StorageStatus.UNAVAILABLE
        rootMutations.loadAndApply {
            synchronized(lock) {
                if (!attempted || retry) {
                    attempted = true
                    unavailable()
                    try {
                        if (retry) persistence.close()
                        if (persistence.resetPending()) {
                            completeReset()
                        } else {
                            val document = if (persistence.exists()) {
                                knownTarget = true
                                persistence.read().also { check(it == persistence.verify()) }
                            } else {
                                check(!knownTarget && !persistence.hasEstablishedStorage()) {
                                    "Established encrypted configuration is missing"
                                }
                                migrate()
                            }
                            establishAuthority()
                            activate(finishCleanup(document))
                        }
                    } catch (failure: Exception) {
                        failed(failure)
                    }
                }
                result = published.status
                rootEnabled()
            }
        }
        return result
    }

    fun write(changes: (ConfigurationValues) -> ConfigurationValues): Boolean {
        var persisted = false
        rootMutations.loadAndApply {
            synchronized(lock) {
                val current = published.document
                if (current != null && published.status.isUsable) {
                    try {
                        // DataStore may still cache the last good value after the underlying file
                        // is damaged or replaced. Preserve that file instead of writing over it.
                        check(persistence.verify() == current) { "Stored configuration changed or is unreadable" }
                        establishAuthority()
                        val candidate = current.copy(values = changes(current.values))
                        persistVerified(candidate)
                        activate(finishCleanup(candidate))
                        persisted = true
                    } catch (failure: Exception) {
                        failed(failure)
                    }
                }
                persisted && rootEnabled()
            }
        }
        return persisted
    }

    /** Only the confirmed UI action calls this. The durable intent authorizes interrupted retry. */
    fun reset(): Boolean = rootMutations.persistAndApply(enabled = false) {
        synchronized(lock) {
            attempted = true
            unavailable()
            try {
                persistence.recordResetIntent()
                completeReset()
                true
            } catch (failure: Exception) {
                failed(failure)
                false
            }
        }
    }

    fun close() = synchronized(lock) { persistence.close() }

    private fun migrate(): StoredConfiguration {
        val plain = legacy.readPlaintext()
        var values = plain.values
        var unreadable = false
        if (!plain.migrationComplete && legacy.encryptedExists()) {
            val imported = try { legacy.readEncrypted() } catch (_: Exception) { null }
            if (imported == null) {
                // An unreadable source may contain the missing secret/UUID: do not seal an empty
                // or partial fallback as the new authority, and never start without credentials.
                check(hasCompleteConnection(values)) { "Legacy configuration requires recovery" }
                unreadable = true
            } else {
                values = ConfigurationValues.from(imported.entries + values.entries)
            }
        }
        val document = StoredConfiguration(
            initialized = true,
            values = values,
            // Retain encrypted legacy evidence, including when the old migration marker means
            // we deliberately did not open it. Only an explicit reset removes both old formats.
            cleanup = ConfigurationCleanup.PLAINTEXT,
            legacyUnreadable = unreadable
        )
        persistence.prepareNewKey()
        persistVerified(document)
        return document
    }

    private fun persistVerified(document: StoredConfiguration) {
        persistence.write(document)
        check(persistence.verify() == document) { "Configuration verification failed" }
    }

    private fun finishCleanup(document: StoredConfiguration): StoredConfiguration {
        if (document.cleanup == ConfigurationCleanup.NONE) return document
        // Never destroy a source before the current encrypted target has been read independently.
        check(persistence.verify() == document) { "Configuration verification failed" }
        if (!legacy.deletePlaintext()) return document
        if (document.cleanup == ConfigurationCleanup.ALL_LEGACY && !legacy.deleteEncrypted()) return document
        val completed = document.copy(cleanup = ConfigurationCleanup.NONE)
        persistVerified(completed)
        return completed
    }

    private fun establishAuthority() {
        knownTarget = true
        // This barrier precedes both source deletion and publishing READY. If it fails, the
        // verified ciphertext and untouched sources can be retried without losing either.
        persistence.recordEstablishedStorage()
    }

    private fun completeReset() {
        persistence.recreateForReset()
        val empty = StoredConfiguration(initialized = true, cleanup = ConfigurationCleanup.ALL_LEGACY)
        persistVerified(empty)
        establishAuthority()
        val completed = finishCleanup(empty)
        check(completed.cleanup == ConfigurationCleanup.NONE) { "Legacy cleanup is incomplete" }
        check(persistence.verify() == completed && completed.values.entries.isEmpty())
        persistence.finishReset()
        activate(completed)
    }

    private fun activate(document: StoredConfiguration) {
        check(document.initialized)
        published = PublishedConfiguration(
            when {
                document.cleanup != ConfigurationCleanup.NONE -> StorageStatus.CLEANUP_PENDING
                document.legacyUnreadable -> StorageStatus.LEGACY_UNREADABLE
                else -> StorageStatus.READY
            },
            document
        )
    }

    private fun unavailable() { published = PublishedConfiguration() }
    private fun failed(failure: Exception) {
        unavailable()
        // Diagnostics may fail independently; they cannot reopen storage or retain a Root grant.
        runCatching { onFailure(failure) }
    }
    private fun rootEnabled(): Boolean = published.status.isUsable &&
        published.document?.values?.boolean("root_mode") == true

    companion object {
        fun hasCompleteConnection(values: ConfigurationValues): Boolean =
            values.string("server").isNotBlank() && values.string("secret").isNotBlank() &&
                values.string("uuid").isNotBlank()
    }
}
/** Files Android uses for one SharedPreferences name. */
internal class SharedPreferencesFiles(dataDirectory: File, preferencesName: String) {
    private val preferencesDirectory = File(dataDirectory, "shared_prefs")
    private val xmlFile = File(preferencesDirectory, "$preferencesName.xml")
    private val backupFile = File(preferencesDirectory, "$preferencesName.xml.bak")

    fun exists(): Boolean = xmlFile.exists() || backupFile.exists()

    /** Match Android's backup precedence without its destructive rename or swallowed errors. */
    fun readValues(): ConfigurationValues = when {
        backupFile.exists() -> LegacyPreferencesXml.read(backupFile)
        xmlFile.exists() -> LegacyPreferencesXml.read(xmlFile)
        else -> ConfigurationValues.EMPTY
    }

    fun delete(): Boolean {
        val xmlDeleted = !xmlFile.exists() || xmlFile.delete()
        val backupDeleted = !backupFile.exists() || backupFile.delete()
        return xmlDeleted && backupDeleted && !exists()
    }
}

internal interface PreferencesDeletionOperations {
    val supportsPlatformDelete: Boolean

    fun clearCacheSynchronously(): Boolean
    fun deleteWithPlatform(): Boolean
    fun deleteFiles(): Boolean
}

/**
 * Deletes one SharedPreferences store while proving both its in-memory cache and disk files were
 * cleared. API 23 needs a second clear after removing a file that prevented the first commit.
 */
internal class PreferencesDeletionCoordinator(
    private val operations: PreferencesDeletionOperations
) {
    fun delete(): Boolean {
        if (operations.supportsPlatformDelete) {
            if (!safely { operations.clearCacheSynchronously() }) return false
            val platformCallCompleted = try {
                operations.deleteWithPlatform()
                true
            } catch (_: Exception) {
                false
            }
            val filesDeleted = safely { operations.deleteFiles() }
            return platformCallCompleted && filesDeleted
        }

        if (safely { operations.clearCacheSynchronously() }) {
            return safely { operations.deleteFiles() }
        }

        // Remove a corrupt file, rebuild/clear the cached preferences, then remove the new empty
        // file created by the successful synchronous clear.
        if (!safely { operations.deleteFiles() }) return false
        if (!safely { operations.clearCacheSynchronously() }) return false
        return safely { operations.deleteFiles() }
    }

    private inline fun safely(block: () -> Boolean): Boolean = try {
        block()
    } catch (_: Exception) {
        false
    }
}
