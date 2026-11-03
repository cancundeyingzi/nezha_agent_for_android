package com.nezhahq.agent.util

import com.nezhahq.agent.core.config.StorageStatus
import com.nezhahq.agent.core.security.RootModeMutationCoordinator
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyPreferencesXmlTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun parsesPlatformTypesEscapedSecretsAndMigrationMarker() {
        val file = xml("""
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
              <string name="server">panel.example</string>
              <string name="secret">a&amp;b&lt;c&gt;&quot;中文</string>
              <int name="port" value="5555" />
              <long name="long" value="9223372036854775807" />
              <float name="float" value="1.5" />
              <boolean name="__plaintext_config_v1" value="true" />
              <set name="strings"><string>one</string><string>two</string></set>
            </map>
        """.trimIndent())
        val original = file.readBytes()
        val values = LegacyPreferencesXml.read(file)
        assertEquals("a&b<c>\"中文", values.string("secret"))
        assertEquals(5555, values.int("port", 0))
        assertEquals(Long.MAX_VALUE, values.entries["long"])
        assertEquals(1.5f, values.entries["float"])
        assertEquals(setOf("one", "two"), values.entries["strings"])
        assertTrue(values.boolean("__plaintext_config_v1"))
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun backupIsAuthoritativeWithoutRenamingOrOverwritingEitherFile() {
        val directory = temporaryFolder.newFolder()
        val preferences = File(directory, "shared_prefs").apply { mkdirs() }
        val current = File(preferences, "config.xml").apply { writeText("<map><string name=\"secret\">current</string></map>") }
        val backup = File(preferences, "config.xml.bak").apply { writeText("<map><string name=\"secret\">backup</string></map>") }
        val currentBytes = current.readBytes()
        val backupBytes = backup.readBytes()
        assertEquals("backup", SharedPreferencesFiles(directory, "config").readValues().string("secret"))
        assertArrayEquals(currentBytes, current.readBytes())
        assertArrayEquals(backupBytes, backup.readBytes())
    }

    @Test fun corruptBackupDoesNotSilentlyFallBackToPotentiallyUncommittedXml() {
        val directory = temporaryFolder.newFolder()
        val preferences = File(directory, "shared_prefs").apply { mkdirs() }
        val current = File(preferences, "config.xml").apply { writeText("<map />") }
        val backup = File(preferences, "config.xml.bak").apply { writeText("<map><string") }
        assertThrows(IOException::class.java) { SharedPreferencesFiles(directory, "config").readValues() }
        assertEquals("<map />", current.readText())
        assertEquals("<map><string", backup.readText())
    }

    @Test fun malformedDuplicateUnsupportedAndDeeplyNestedValuesAreRejectedWithoutChanges() {
        listOf(
            "", "<map><string", "<list />",
            "<map><string name=\"secret\">one</string><string name=\"secret\">two</string></map>",
            "<map><null name=\"secret\" /></map>",
            "<map><int name=\"port\" value=\"no-number\" /></map>",
            "<map><boolean name=\"root_mode\" value=\"yes\" /></map>",
            "<map><string name=\"secret\"><nested>hidden</nested></string></map>",
            "<map><set name=\"x\"><set><string>nested</string></set></set></map>"
        ).forEach { content ->
            val file = xml(content)
            val original = file.readBytes()
            assertThrows(IOException::class.java) { LegacyPreferencesXml.read(file) }
            assertArrayEquals(original, file.readBytes())
        }
    }

    @Test fun doctypeExternalEntitiesAndEntityExpansionAreRejectedBeforeParsing() {
        val privateFile = temporaryFolder.newFile().apply { writeText("must-not-import") }
        listOf(
            "<!DOCTYPE map [<!ENTITY x SYSTEM '${privateFile.toURI()}'>]><map><string name=\"secret\">&x;</string></map>",
            "<!DOCTYPE map SYSTEM 'https://invalid.example/external.dtd'><map />",
            "<!DOCTYPE map [<!ENTITY x 'expanded'>]><map><string name=\"secret\">&x;&x;</string></map>"
        ).forEach { content ->
            assertThrows(IOException::class.java) { LegacyPreferencesXml.read(xml(content)) }
        }
    }

    @Test fun oversizedSourceInvalidUtf8AndExcessiveEntriesAreRejected() {
        val huge = temporaryFolder.newFile().apply { writeBytes(ByteArray(1024 * 1024 + 1)) }
        assertThrows(IOException::class.java) { LegacyPreferencesXml.read(huge) }
        val invalid = temporaryFolder.newFile().apply { writeBytes(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(IOException::class.java) { LegacyPreferencesXml.read(invalid) }
        val entries = (0..256).joinToString("") { "<string name=\"key$it\">value</string>" }
        assertThrows(IOException::class.java) { LegacyPreferencesXml.read(xml("<map>$entries</map>")) }
    }

    @Test fun brokenPlaintextNeverCreatesTargetKeyOrCleansOriginalSource() {
        ConfigurationStorageFixture(temporaryFolder.newFolder()).use { f ->
            val source = xml("<map><string name=\"secret\">damaged")
            val original = source.readBytes()
            val legacy = object : LegacyConfigurationOperations by f.legacy {
                override fun readPlaintext() = LegacyPlaintextConfiguration(LegacyPreferencesXml.read(source), false)
            }
            val failures = mutableListOf<Exception>()
            val coordinator = ConfigurationStorageCoordinator(
                f.persistence, legacy, RootModeMutationCoordinator {}, failures::add
            )
            assertEquals(StorageStatus.UNAVAILABLE, coordinator.initialize())
            assertFalse(f.file.exists())
            assertEquals(0, f.keys.creates)
            assertFalse(f.events.contains("deletePlaintext"))
            assertTrue(failures.single() is IOException)
            assertArrayEquals(original, source.readBytes())
        }
    }

    private fun xml(content: String): File = temporaryFolder.newFile().apply { writeText(content) }
}
