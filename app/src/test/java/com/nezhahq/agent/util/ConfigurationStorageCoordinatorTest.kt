package com.nezhahq.agent.util

import androidx.datastore.core.Serializer
import com.nezhahq.agent.core.config.StorageStatus
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfigurationStorageCoordinatorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun firstEmptyConfigurationIsPhysicallyEncryptedAndVerifiable() = fixture().use { f ->
        val actual = f.coordinator.initialize()
        assertEquals(f.failures.joinToString { it.stackTraceToString() }, StorageStatus.READY, actual)
        assertTrue(f.file.isFile)
        assertTrue(f.backend.verify().initialized)
        assertTrue(f.backend.verify().values.entries.isEmpty())
        assertFalse(f.rootAuthorized)
        assertEquals(1, f.keys.creates)
        f.reopen()
        assertEquals(StorageStatus.READY, f.coordinator.initialize())
        assertEquals(1, f.keys.creates)
    }

    @Test fun migrationEncryptsBothSecretsAndDeletesSourcesOnlyAfterIndependentVerification() =
        fixture(plain = CONNECTION).use { f ->
            f.legacy.beforeDelete = {
                assertEquals(CONNECTION, f.backend.verify().values.entries)
                assertTrue(f.events.indexOf("verified") < f.events.indexOf("deletePlaintext"))
            }
            assertEquals(StorageStatus.READY, f.coordinator.initialize())
            assertEquals(CONNECTION, f.coordinator.current().document!!.values.entries)
            assertFalse(f.legacy.xml.exists())
            assertFalse(f.legacy.backup.exists())
            assertTrue(f.legacy.plain.isEmpty())
            val disk = f.file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(disk.contains(CONNECTION.getValue("secret") as String))
            assertFalse(disk.contains(CONNECTION.getValue("simulator_secret") as String))
            assertTrue(f.rootAuthorized)
        }

    @Test fun plaintextWinsAndReadableLegacyOnlyFillsMissingKeys() = fixture(
        plain = mapOf("server" to "current.example", "root_mode" to false),
        encrypted = CONNECTION
    ).use { f ->
        assertEquals(StorageStatus.READY, f.coordinator.initialize())
        val values = f.backend.verify().values
        assertEquals("current.example", values.string("server"))
        assertEquals(CONNECTION["secret"], values.string("secret"))
        assertFalse(values.boolean("root_mode"))
        assertEquals(1, f.legacy.encryptedReads)
        assertTrue(f.legacy.encryptedFile.exists())
    }

    @Test fun previousMigrationMarkerPreventsStaleLegacyFromRevivingDeletedValues() = fixture(
        plain = mapOf("server" to "new.example"), marker = true, encrypted = CONNECTION
    ).use { f ->
        f.legacy.encryptedUnreadable = true
        assertEquals(StorageStatus.READY, f.coordinator.initialize())
        assertEquals("", f.backend.verify().values.string("secret"))
        assertEquals(0, f.legacy.encryptedReads)
        assertFalse(f.rootAuthorized)
    }

    @Test fun unreadableLegacyWithoutCompleteAuthorityPreservesSourcesAndCreatesNoTarget() = fixture(
        plain = mapOf("server" to "new.example"), encrypted = CONNECTION
    ).use { f ->
        f.legacy.encryptedUnreadable = true
        assertEquals(StorageStatus.UNAVAILABLE, f.coordinator.initialize())
        assertFalse(f.file.exists())
        assertTrue(f.legacy.xml.exists())
        assertTrue(f.legacy.encryptedFile.exists())
        assertEquals(0, f.keys.creates)
        assertFalse(f.rootAuthorized)
    }

    @Test fun unreadableLegacyWithCompleteAuthorityEncryptsFallbackAndPreservesLegacy() = fixture(
        plain = CONNECTION, encrypted = mapOf("secret" to "unreadable-older-secret")
    ).use { f ->
        f.legacy.encryptedUnreadable = true
        assertEquals(StorageStatus.LEGACY_UNREADABLE, f.coordinator.initialize())
        assertEquals(CONNECTION, f.backend.verify().values.entries)
        assertFalse(f.legacy.xml.exists())
        assertTrue(f.legacy.encryptedFile.exists())
        f.reopen()
        assertEquals(StorageStatus.LEGACY_UNREADABLE, f.coordinator.initialize())
        assertEquals(1, f.legacy.encryptedReads)
    }

    @Test fun failedInitialWritePreservesPlaintextAndCanRetry() = fixture(plain = CONNECTION).use { f ->
        f.persistence.beforeWrite = { throw IOException("write interrupted") }
        assertEquals(StorageStatus.UNAVAILABLE, f.coordinator.initialize())
        assertTrue(f.legacy.xml.exists())
        assertFalse(f.events.contains("deletePlaintext"))
        f.persistence.beforeWrite = {}
        assertEquals(StorageStatus.READY, f.coordinator.initialize(retry = true))
        assertEquals(CONNECTION, f.backend.verify().values.entries)
    }

    @Test fun interruptedAfterAtomicWriteResumesFromTargetWithoutReimportingSource() = fixture(plain = CONNECTION).use { f ->
        f.persistence.afterWrite = { throw IOException("process interrupted after rename") }
        assertEquals(StorageStatus.UNAVAILABLE, f.coordinator.initialize())
        assertTrue(f.file.isFile)
        assertTrue(f.legacy.xml.exists())
        f.legacy.plain = mapOf("secret" to "stale-source")
        f.persistence.afterWrite = {}
        f.reopen()
        assertEquals(StorageStatus.READY, f.coordinator.initialize())
        assertEquals(CONNECTION["secret"], f.backend.verify().values.string("secret"))
        assertEquals(1, f.legacy.plainReads)
    }

    @Test fun failedIndependentVerificationNeverDeletesPlaintextAndRetryUsesCiphertext() = fixture(plain = CONNECTION).use { f ->
        f.persistence.beforeVerify = { throw IOException("verification interrupted") }
        assertEquals(StorageStatus.UNAVAILABLE, f.coordinator.initialize())
        assertTrue(f.file.isFile)
        assertTrue(f.legacy.xml.exists())
        assertTrue(f.legacy.backup.exists())
        assertFalse(f.events.contains("deletePlaintext"))
        f.persistence.beforeVerify = {}
        assertEquals(StorageStatus.READY, f.coordinator.initialize(retry = true))
        assertFalse(f.legacy.xml.exists())
    }

    @Test fun cleanupFailurePublishesEncryptedConfigurationAndRetriesIdempotently() = fixture(plain = CONNECTION).use { f ->
        f.legacy.failPlainCleanup = true
        assertEquals(StorageStatus.CLEANUP_PENDING, f.coordinator.initialize())
        assertTrue(f.legacy.xml.exists())
        assertEquals(CONNECTION, f.coordinator.current().document!!.values.entries)
        assertTrue(f.coordinator.write { it.changed(mapOf("enable_float_window" to true)) })
        f.legacy.failPlainCleanup = false
        f.reopen()
        assertEquals(StorageStatus.READY, f.coordinator.initialize())
        assertTrue(f.backend.verify().values.boolean("enable_float_window"))
        assertEquals(1, f.legacy.plainReads)
        assertFalse(f.legacy.xml.exists())
    }

    @Test fun metadataWriteFailureAfterCleanupStillLeavesRecoverableEncryptedConfiguration() = fixture(plain = CONNECTION).use { f ->
        var writes = 0
        f.persistence.beforeWrite = { if (++writes == 2) throw IOException("metadata interrupted") }
        assertEquals(StorageStatus.UNAVAILABLE, f.coordinator.initialize())
        assertFalse(f.legacy.xml.exists())
        assertEquals(CONNECTION, f.backend.verify().values.entries)
        f.persistence.beforeWrite = {}
        f.reopen()
        assertEquals(StorageStatus.READY, f.coordinator.initialize())
        assertEquals(CONNECTION, f.backend.verify().values.entries)
    }

    @Test fun corruptCiphertextIsNeverOverwrittenByStalePlaintextOrResetImplicitly() = fixture(plain = CONNECTION).use { f ->
        assertEquals(StorageStatus.READY, f.coordinator.initialize())
        f.coordinator.close()
        val corrupt = f.file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        f.file.writeBytes(corrupt)
        f.legacy.plain = mapOf("secret" to "stale-secret")
        f.legacy.xml.writeText("stale-secret")
        f.reopen()
        assertEquals(StorageStatus.UNAVAILABLE, f.coordinator.initialize())
        assertArrayEquals(corrupt, f.file.readBytes())
        assertTrue(f.legacy.xml.exists())
        assertFalse(f.coordinator.write { it.changed(mapOf("server" to "replacement")) })
        assertEquals(0, f.keys.replacements)
        assertEquals(1, f.legacy.plainReads)
        assertFalse(f.rootAuthorized)
    }

    @Test fun missingAndInvalidatedKeysPreserveCiphertextAndDoNotGenerateReplacement() {
        for (missing in listOf(true, false)) fixture(plain = CONNECTION).use { f ->
            assertEquals(StorageStatus.READY, f.coordinator.initialize())
            val original = f.file.readBytes()
            if (missing) f.keys.key = null else f.keys.key = f.keys.newKey()
            f.reopen()
            assertEquals(StorageStatus.UNAVAILABLE, f.coordinator.initialize())
            assertArrayEquals(original, f.file.readBytes())
            assertEquals(1, f.keys.creates)
            assertEquals(0, f.keys.replacements)
            assertFalse(f.rootAuthorized)
        }
    }

    @Test fun corruptionAfterInitializationIsPreservedWhenNextWriteSeesCachedDataStoreState() = fixture(plain = CONNECTION).use { f ->
        assertEquals(StorageStatus.READY, f.coordinator.initialize())
        val corrupt = f.file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        f.file.writeBytes(corrupt)
        assertFalse(f.coordinator.write { it.changed(mapOf("secret" to "replacement")) })
        assertEquals(StorageStatus.UNAVAILABLE, f.coordinator.currentStatus())
        assertArrayEquals(corrupt, f.file.readBytes())
        assertFalse(f.rootAuthorized)
        assertEquals(0, f.keys.replacements)
    }

    @Test fun authenticatedFileReplacementCannotBeOverwrittenFromAnObsoleteCache() = fixture(plain = CONNECTION).use { f ->
        assertEquals(StorageStatus.READY, f.coordinator.initialize())
        val replacement = f.backend.verify().copy(values = ConfigurationValues.from(CONNECTION + ("secret" to "new-authority")))
        val bytes = java.io.ByteArrayOutputStream().also { output ->
            kotlinx.coroutines.runBlocking { EncryptedConfigurationSerializer(f.keys).writeTo(replacement, output) }
        }.toByteArray()
        f.file.writeBytes(bytes)
        assertFalse(f.coordinator.write { it.changed(mapOf("enable_float_window" to true)) })
        assertArrayEquals(bytes, f.file.readBytes())
        assertFalse(f.rootAuthorized)
        assertEquals(StorageStatus.READY, f.coordinator.initialize(retry = true))
        assertEquals("new-authority", f.coordinator.current().document!!.values.string("secret"))
    }

    @Test fun transientKeystoreFailureRecoversWithoutDeletingOrReplacingAnything() = fixture(plain = CONNECTION).use { f ->
        assertEquals(StorageStatus.READY, f.coordinator.initialize())
        val original = f.file.readBytes()
        f.keys.inaccessible = true
        assertEquals(StorageStatus.UNAVAILABLE, f.coordinator.initialize(retry = true))
        assertArrayEquals(original, f.file.readBytes())
        f.keys.inaccessible = false
        assertEquals(StorageStatus.READY, f.coordinator.initialize(retry = true))
        assertArrayEquals(original, f.file.readBytes())
        assertEquals(CONNECTION, f.coordinator.current().document!!.values.entries)
        assertEquals(0, f.keys.replacements)
    }

    @Test fun concurrentSectionUpdatesCannotLoseConnectionToolsOrSimulatorValues() = fixture().use { f ->
        assertEquals(StorageStatus.READY, f.coordinator.initialize())
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val updates = listOf(
                mapOf<String, Any>("server" to "panel.example", "secret" to "primary", "uuid" to "uuid", "root_mode" to true),
                mapOf<String, Any>("enable_float_window" to true, "enable_keep_alive_audio" to true),
                mapOf<String, Any>("simulator_secret" to "simulated", "simulator_thread_count" to 12)
            )
            val tasks = updates.map { changes -> executor.submit<Boolean> {
                start.await()
                f.coordinator.write { it.changed(changes) }
            } }
            start.countDown()
            tasks.forEach { assertTrue(it.get(10, TimeUnit.SECONDS)) }
            val stored = f.backend.verify().values.entries
            updates.forEach { changes -> changes.forEach { (key, value) -> assertEquals(value, stored[key]) } }
            assertTrue(f.rootAuthorized)
        } finally { executor.shutdownNow() }
    }

    @Test fun snapshotReadDoesNotWaitBehindSlowInitialization() = fixture(plain = CONNECTION).use { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.legacy.beforePlainRead = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val initialization = executor.submit<StorageStatus> { f.coordinator.initialize() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val read = executor.submit<PublishedConfiguration> { f.coordinator.current() }
            assertEquals(StorageStatus.UNAVAILABLE, read.get(1, TimeUnit.SECONDS).status)
            release.countDown()
            assertEquals(StorageStatus.READY, initialization.get(5, TimeUnit.SECONDS))
            assertEquals(CONNECTION, f.coordinator.current().document!!.values.entries)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun failedWriteRevokesRootAndKeepsPreviouslyCommittedCiphertext() = fixture(plain = CONNECTION).use { f ->
        assertEquals(StorageStatus.READY, f.coordinator.initialize())
        assertTrue(f.rootAuthorized)
        val original = f.file.readBytes()
        f.keys.inaccessible = true
        assertFalse(f.coordinator.write { it.changed(mapOf("secret" to "changed", "root_mode" to true)) })
        assertFalse(f.rootAuthorized)
        assertEquals(StorageStatus.UNAVAILABLE, f.coordinator.currentStatus())
        assertArrayEquals(original, f.file.readBytes())
    }

    @Test fun resetWritesVerifiedEmptyCiphertextClosesOldStoreAndCannotReimportLegacy() = fixture(
        plain = CONNECTION, encrypted = mapOf("secret" to "old-secret")
    ).use { f ->
        f.legacy.failPlainCleanup = true
        assertEquals(StorageStatus.CLEANUP_PENDING, f.coordinator.initialize())
        f.legacy.failPlainCleanup = false
        assertTrue(f.coordinator.reset())
        assertTrue(f.file.isFile)
        assertTrue(f.backend.verify().values.entries.isEmpty())
        assertFalse(f.rootAuthorized)
        assertFalse(f.legacy.xml.exists())
        assertFalse(f.legacy.backup.exists())
        assertFalse(f.legacy.encryptedFile.exists())
        assertFalse(f.backend.resetPending())
        f.reopen()
        assertEquals(StorageStatus.READY, f.coordinator.initialize())
        assertTrue(f.backend.verify().values.entries.isEmpty())
        assertEquals(1, f.legacy.plainReads)
    }

    @Test fun resetWithLostKeyCreatesNewKeyOnlyAfterExplicitRequest() = fixture(plain = CONNECTION).use { f ->
        assertEquals(StorageStatus.READY, f.coordinator.initialize())
        f.keys.key = null
        assertEquals(StorageStatus.UNAVAILABLE, f.coordinator.initialize(retry = true))
        assertEquals(0, f.keys.replacements)
        assertTrue(f.coordinator.reset())
        assertEquals(1, f.keys.replacements)
        assertTrue(f.backend.verify().values.entries.isEmpty())
        assertFalse(f.rootAuthorized)
    }

    @Test fun interruptedResetBeforeRecreationResumesFromDurableIntentAndDoesNotImportSources() = fixture(plain = CONNECTION).use { f ->
        f.persistence.beforeRecreate = { throw IOException("interrupted reset") }
        assertFalse(f.coordinator.reset())
        assertTrue(f.backend.resetPending())
        assertTrue(f.legacy.xml.exists())
        assertFalse(f.rootAuthorized)
        f.persistence.beforeRecreate = {}
        f.reopen()
        assertEquals(StorageStatus.READY, f.coordinator.initialize())
        assertTrue(f.backend.verify().values.entries.isEmpty())
        assertEquals(0, f.legacy.plainReads)
        assertFalse(f.legacy.xml.exists())
    }

    @Test fun interruptedResetCleanupRemainsUnavailableAndRestartFinishesWithoutResurrection() = fixture(
        plain = CONNECTION, encrypted = CONNECTION
    ).use { f ->
        f.legacy.failPlainCleanup = true
        assertFalse(f.coordinator.reset())
        assertEquals(StorageStatus.UNAVAILABLE, f.coordinator.currentStatus())
        assertTrue(f.backend.resetPending())
        assertTrue(f.file.isFile)
        assertTrue(f.backend.verify().values.entries.isEmpty())
        assertFalse(f.rootAuthorized)
        f.legacy.failPlainCleanup = false
        f.reopen()
        assertEquals(StorageStatus.READY, f.coordinator.initialize())
        assertFalse(f.backend.resetPending())
        assertTrue(f.backend.verify().values.entries.isEmpty())
        assertEquals(0, f.legacy.plainReads)
    }

    @Test fun failedResetVerificationPreservesSourcesUntilLaterRetrySucceeds() = fixture(plain = CONNECTION).use { f ->
        f.persistence.beforeVerify = { throw IOException("reset verification interrupted") }
        assertFalse(f.coordinator.reset())
        assertTrue(f.legacy.xml.exists())
        assertTrue(f.backend.resetPending())
        f.persistence.beforeVerify = {}
        assertEquals(StorageStatus.READY, f.coordinator.initialize(retry = true))
        assertTrue(f.backend.verify().values.entries.isEmpty())
        assertFalse(f.legacy.xml.exists())
    }

    @Test fun realDataStorePartialWriteFailureIsAtomicAndLeavesOldCiphertextIntact() {
        val keys = TestConfigurationKeys()
        val delegate = EncryptedConfigurationSerializer(keys)
        var failWrite = false
        val serializer = object : Serializer<StoredConfiguration> by delegate {
            override suspend fun writeTo(t: StoredConfiguration, output: OutputStream) {
                if (!failWrite) return delegate.writeTo(t, output)
                var count = 0
                delegate.writeTo(t, object : OutputStream() {
                    override fun write(value: Int) {
                        if (++count > 30) throw IOException("injected disk write interruption")
                        output.write(value)
                    }
                })
            }
        }
        ConfigurationStorageFixture(temporaryFolder.newFolder(), CONNECTION, keys = keys, serializer = serializer).use { f ->
            assertEquals(StorageStatus.READY, f.coordinator.initialize())
            val original = f.file.readBytes()
            failWrite = true
            assertFalse(f.coordinator.write { it.changed(mapOf("secret" to "new-secret")) })
            assertArrayEquals(original, f.file.readBytes())
            assertFalse(f.rootAuthorized)
            failWrite = false
            assertEquals(StorageStatus.READY, f.coordinator.initialize(retry = true))
            assertEquals(CONNECTION["secret"], f.backend.verify().values.string("secret"))
        }
    }

    @Test fun sharedPreferencesFilesDeletesXmlAndBackup() {
        val directory = temporaryFolder.newFolder()
        val prefs = File(directory, "shared_prefs").apply { mkdirs() }
        File(prefs, "config.xml").writeText("xml")
        File(prefs, "config.xml.bak").writeText("backup")
        val files = SharedPreferencesFiles(directory, "config")
        assertTrue(files.exists())
        assertTrue(files.delete())
        assertFalse(files.exists())
    }

    @Test fun failureReporterCannotReopenStorageOrLeaveRootAuthorized() = fixture(plain = CONNECTION).use { f ->
        f.keys.inaccessible = true
        var authorized = true
        var reported = false
        val coordinator = ConfigurationStorageCoordinator(
            f.persistence, f.legacy,
            com.nezhahq.agent.core.security.RootModeMutationCoordinator { authorized = it },
            onFailure = { reported = true; throw IllegalStateException("diagnostics unavailable") }
        )
        assertEquals(StorageStatus.UNAVAILABLE, coordinator.initialize())
        assertTrue(reported)
        assertFalse(authorized)
        assertNull(coordinator.current().document)
    }

    private fun fixture(
        plain: Map<String, Any> = emptyMap(), marker: Boolean = false, encrypted: Map<String, Any>? = null
    ) = ConfigurationStorageFixture(temporaryFolder.newFolder(), plain, marker, encrypted)

    companion object { private val CONNECTION = ConfigurationStorageFixture.CONNECTION }
}
