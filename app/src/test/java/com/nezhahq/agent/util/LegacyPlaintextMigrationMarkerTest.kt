package com.nezhahq.agent.util

import com.nezhahq.agent.core.config.StorageStatus
import com.nezhahq.agent.core.security.RootModeMutationCoordinator
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyPlaintextMigrationMarkerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun absentAndBooleanMarkersKeepTheirExistingMeaning() {
        listOf(null, false, true).forEach { marker ->
            val directory = temporaryFolder.newFolder()
            val prefs = File(directory, "shared_prefs").apply { mkdirs() }
            val metadata = marker?.let { "<boolean name=\"__plaintext_config_v1\" value=\"$it\" />" }.orEmpty()
            File(prefs, "config.xml").writeText("<map><string name=\"server\">current.example</string>$metadata</map>")
            val parsed = readLegacyPlaintextConfiguration(SharedPreferencesFiles(directory, "config"))
            assertEquals(marker ?: false, parsed.migrationComplete)
            assertEquals("current.example", parsed.values.string("server"))
            assertFalse(parsed.values.contains("__plaintext_config_v1"))
        }
    }

    @Test fun nonBooleanBackupMarkerStopsMigrationBeforeReadingOldAccountAndPreservesSources() {
        listOf(
            "<string name=\"__plaintext_config_v1\">true</string>",
            "<int name=\"__plaintext_config_v1\" value=\"1\" />"
        ).forEach { damagedMarker ->
            ConfigurationStorageFixture(temporaryFolder.newFolder(), encrypted = ConfigurationStorageFixture.CONNECTION).use { f ->
                val directory = temporaryFolder.newFolder()
                val prefs = File(directory, "shared_prefs").apply { mkdirs() }
                val current = File(prefs, "config.xml").apply { writeText("<map />") }
                val backup = File(prefs, "config.xml.bak").apply {
                    writeText("<map><string name=\"server\">current.example</string>$damagedMarker</map>")
                }
                val originalCurrent = current.readBytes()
                val originalBackup = backup.readBytes()
                val originalEncrypted = f.legacy.encryptedFile.readBytes()
                val legacy = object : LegacyConfigurationOperations by f.legacy {
                    override fun readPlaintext() =
                        readLegacyPlaintextConfiguration(SharedPreferencesFiles(directory, "config"))
                }
                val failures = mutableListOf<Exception>()
                val coordinator = ConfigurationStorageCoordinator(
                    f.persistence, legacy, RootModeMutationCoordinator {}, failures::add
                )
                assertEquals(StorageStatus.UNAVAILABLE, coordinator.initialize())
                assertTrue(failures.single() is IOException)
                assertEquals(0, f.legacy.encryptedReads)
                assertEquals(0, f.keys.creates)
                assertFalse(f.file.exists())
                assertFalse(f.events.contains("deletePlaintext"))
                assertArrayEquals(originalCurrent, current.readBytes())
                assertArrayEquals(originalBackup, backup.readBytes())
                assertArrayEquals(originalEncrypted, f.legacy.encryptedFile.readBytes())
            }
        }
    }
}
