package com.nezhahq.agent.device

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nezhahq.agent.core.config.StorageStatus
import com.nezhahq.agent.core.security.RootModeMutationCoordinator
import com.nezhahq.agent.util.AndroidConfigurationKeyProvider
import com.nezhahq.agent.util.AndroidLegacyConfigurationOperations
import com.nezhahq.agent.util.ConfigurationPersistence
import com.nezhahq.agent.util.ConfigurationStorageCoordinator
import com.nezhahq.agent.util.EncryptedConfigurationDataStore
import com.nezhahq.agent.util.LegacyConfigurationOperations
import java.io.File
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidConfigurationStorageRegressionTest {
    @get:Rule val evidence = DeviceEvidenceRule()
    private val context: Context get() = deviceApplication
    private lateinit var directory: File
    private lateinit var target: File
    private lateinit var plainName: String
    private lateinit var encryptedName: String
    private lateinit var keyAlias: String
    private lateinit var legacyKeyAlias: String
    private lateinit var legacy: AndroidLegacyConfigurationOperations
    private val coordinators = mutableListOf<ConfigurationStorageCoordinator>()

    @Before fun isolate() {
        val id = "device_${UUID.randomUUID().toString().replace("-", "") }"
        directory = File(context.noBackupFilesDir, "device-storage/$id").apply { check(mkdirs()) }
        target = File(directory, "configuration.enc")
        plainName = "${id}_plain"
        encryptedName = "${id}_encrypted"
        keyAlias = "${id}_aes"
        legacyKeyAlias = "${id}_legacy_aes"
        legacy = AndroidLegacyConfigurationOperations(context, plainName, encryptedName, legacyKeyAlias)
    }

    @After fun cleanupOwnedFixtures() {
        coordinators.forEach { runCatching { it.close() } }
        legacy.deletePlaintext()
        legacy.deleteEncrypted()
        keyStore().deleteEntry(keyAlias)
        keyStore().deleteEntry(legacyKeyAlias)
        assertTrue(directory.deleteRecursively())
    }

    @Test fun androidKeystoreAndDataStoreRoundTripCiphertextAcrossCloseAndReopen() {
        val storage = open()
        assertEquals(StorageStatus.READY, storage.initialize())
        assertTrue(storage.write { it.changed(connectionValues("synthetic-secret-first")) })
        assertNull("Android Keystore key must not be exportable", AndroidConfigurationKeyProvider(keyAlias).existingKey().encoded)
        val first = target.readBytes()
        assertFalse(first.toString(Charsets.ISO_8859_1).contains("synthetic-secret-first"))
        assertTrue(storage.write { it.changed(mapOf("secret" to "synthetic-secret-second")) })
        val second = target.readBytes()
        assertFalse(first.contentEquals(second))
        assertFalse(second.toString(Charsets.ISO_8859_1).contains("synthetic-secret-second"))
        assertFalse(File(target.path + ".tmp").exists())
        storage.close()

        val reopened = open()
        assertEquals(StorageStatus.READY, reopened.initialize())
        assertEquals("synthetic-secret-second", reopened.current().document!!.values.string("secret"))
        assertEquals("127.0.0.1", reopened.current().document!!.values.string("server"))
        assertArrayEquals(second, target.readBytes())
    }

    @Test fun realEncryptedPreferencesBackupMigratesWithoutMutatingEitherEncryptedSource() {
        val encrypted = createEncryptedSource()
        assertTrue(encrypted.edit().putString("server", "127.0.0.1").putInt("port", 8008)
            .putString("secret", "backup-synthetic-secret").putString("uuid", "backup-uuid")
            .putBoolean("root_mode", false).commit())
        val encryptedXml = preferencesFile(encryptedName)
        val backupBytes = encryptedXml.readBytes()
        assertTrue(encrypted.edit().putString("secret", "primary-synthetic-secret").commit())
        val primaryBytes = encryptedXml.readBytes()
        assertFalse(primaryBytes.contentEquals(backupBytes))
        val backup = File(encryptedXml.path + ".bak").apply { writeBytes(backupBytes) }
        assertTrue(context.getSharedPreferences(plainName, Context.MODE_PRIVATE).edit()
            .putInt("port", 8443).putBoolean("enable_auto_start", false).commit())

        val persistence = persistence()
        var cleanupSawVerifiedCiphertext = false
        val observedLegacy = object : LegacyConfigurationOperations by legacy {
            override fun deletePlaintext(): Boolean {
                val verified = persistence.verify()
                assertEquals("backup-synthetic-secret", verified.values.string("secret"))
                assertTrue(persistence.hasEstablishedStorage())
                cleanupSawVerifiedCiphertext = true
                return legacy.deletePlaintext()
            }
        }
        val storage = open(persistence, observedLegacy)
        assertEquals(StorageStatus.READY, storage.initialize())
        assertTrue(cleanupSawVerifiedCiphertext)
        assertEquals("backup-synthetic-secret", storage.current().document!!.values.string("secret"))
        assertEquals(8443, storage.current().document!!.values.int("port", 0))
        assertFalse(preferencesFile(plainName).exists())
        assertFalse(File(preferencesFile(plainName).path + ".bak").exists())
        assertArrayEquals(primaryBytes, encryptedXml.readBytes())
        assertArrayEquals(backupBytes, backup.readBytes())
        storage.close()
        assertEquals(StorageStatus.READY, open().initialize())
        assertArrayEquals(primaryBytes, encryptedXml.readBytes())
        assertArrayEquals(backupBytes, backup.readBytes())
    }

    @Test fun plaintextBackupIsImportedAndRemovedOnlyAfterEncryptedVerification() {
        assertTrue(context.getSharedPreferences(plainName, Context.MODE_PRIVATE).edit()
            .putString("server", "127.0.0.1").putString("secret", "plaintext-synthetic-secret")
            .putString("uuid", "plaintext-uuid").putInt("port", 8123).commit())
        val source = preferencesFile(plainName)
        File(source.path + ".bak").writeBytes(source.readBytes())
        // A broken primary must not win over Android's valid backup.
        source.writeText("<broken>")
        val persistence = persistence()
        var verifiedBeforeDeletion = false
        val guardedLegacy = object : LegacyConfigurationOperations by legacy {
            override fun deletePlaintext(): Boolean {
                assertEquals("plaintext-synthetic-secret", persistence.verify().values.string("secret"))
                verifiedBeforeDeletion = true
                return legacy.deletePlaintext()
            }
        }
        val storage = open(persistence, guardedLegacy)
        assertEquals(StorageStatus.READY, storage.initialize())
        assertTrue(verifiedBeforeDeletion)
        assertFalse(source.exists())
        assertFalse(File(source.path + ".bak").exists())
        assertFalse(target.readBytes().toString(Charsets.ISO_8859_1).contains("plaintext-synthetic-secret"))
    }

    @Test fun missingKeystoreAliasPreservesCiphertextAndCannotFallBackUntilExplicitReset() {
        val original = open()
        assertEquals(StorageStatus.READY, original.initialize())
        assertTrue(original.write { it.changed(connectionValues("protected-synthetic-secret")) })
        val ciphertext = target.readBytes()
        original.close()
        keyStore().deleteEntry(keyAlias)
        // This legacy source must never become an automatic replacement for unreadable authority.
        assertTrue(context.getSharedPreferences(plainName, Context.MODE_PRIVATE).edit()
            .putString("server", "127.0.0.1").putString("secret", "obsolete-synthetic-secret")
            .putString("uuid", "obsolete-uuid").commit())
        val source = preferencesFile(plainName).readBytes()
        val broken = open()
        assertEquals(StorageStatus.UNAVAILABLE, broken.initialize())
        assertEquals(StorageStatus.UNAVAILABLE, broken.initialize(retry = true))
        assertFalse(broken.write { it.changed(connectionValues("must-not-save")) })
        assertNull(broken.current().document)
        assertFalse(keyStore().containsAlias(keyAlias))
        assertArrayEquals(ciphertext, target.readBytes())
        assertArrayEquals(source, preferencesFile(plainName).readBytes())

        assertTrue(broken.reset())
        assertEquals(StorageStatus.READY, broken.currentStatus())
        assertTrue(keyStore().containsAlias(keyAlias))
        assertTrue(broken.current().document!!.values.entries.isEmpty())
        assertFalse(preferencesFile(plainName).exists())
        assertFalse(File(target.path + ".reset").exists())
    }

    @Test fun establishedMarkerPreventsReimportWhenEncryptedTargetDisappears() {
        val original = open()
        assertEquals(StorageStatus.READY, original.initialize())
        assertTrue(original.write { it.changed(connectionValues("established-synthetic-secret")) })
        original.close()
        assertTrue(File(target.path + ".established").exists())
        assertTrue(target.delete())
        assertTrue(context.getSharedPreferences(plainName, Context.MODE_PRIVATE).edit()
            .putString("server", "127.0.0.1").putString("secret", "obsolete")
            .putString("uuid", "obsolete").commit())
        val source = preferencesFile(plainName).readBytes()
        assertEquals(StorageStatus.UNAVAILABLE, open().initialize())
        assertFalse(target.exists())
        assertArrayEquals(source, preferencesFile(plainName).readBytes())
    }

    @Test fun interruptedExplicitResetResumesToVerifiedEmptyStoreOnRetry() {
        var refuseLegacyCleanup = true
        val interruptedLegacy = object : LegacyConfigurationOperations by legacy {
            override fun deleteEncrypted(): Boolean = !refuseLegacyCleanup && legacy.deleteEncrypted()
        }
        val storage = open(legacyOperations = interruptedLegacy)
        assertEquals(StorageStatus.READY, storage.initialize())
        assertTrue(storage.write { it.changed(connectionValues("reset-synthetic-secret")) })
        assertFalse(storage.reset())
        assertEquals(StorageStatus.UNAVAILABLE, storage.currentStatus())
        assertTrue(File(target.path + ".reset").exists())
        refuseLegacyCleanup = false
        assertEquals(StorageStatus.READY, storage.initialize(retry = true))
        assertTrue(storage.current().document!!.values.entries.isEmpty())
        assertFalse(File(target.path + ".reset").exists())
        storage.close()
        val reopened = open()
        assertEquals(StorageStatus.READY, reopened.initialize())
        assertTrue(reopened.current().document!!.values.entries.isEmpty())
    }

    private fun persistence() = EncryptedConfigurationDataStore({ target }, AndroidConfigurationKeyProvider(keyAlias))

    private fun open(
        persistence: ConfigurationPersistence = persistence(),
        legacyOperations: LegacyConfigurationOperations = legacy
    ) = ConfigurationStorageCoordinator(persistence, legacyOperations, RootModeMutationCoordinator {}).also {
        coordinators += it
    }

    @Suppress("DEPRECATION")
    private fun createEncryptedSource(): android.content.SharedPreferences {
        val masterKey = MasterKey.Builder(context, legacyKeyAlias).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context, encryptedName, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun preferencesFile(name: String) = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
    private fun connectionValues(secret: String): Map<String, Any> = mapOf(
        "server" to "127.0.0.1", "port" to 8008, "secret" to secret, "uuid" to "device-storage-uuid",
        "root_mode" to false, "use_tls" to true
    )
}
