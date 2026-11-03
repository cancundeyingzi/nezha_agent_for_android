package com.nezhahq.agent.util

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReadOnlyLegacyPreferencesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun decoderCanReadEveryPreferenceTypeWithoutObtainingAnEditor() {
        val snapshot = ReadOnlyLegacyPreferences(ConfigurationValues.from(mapOf(
            "keyset" to "serialized-keyset", "int" to 7, "long" to 8L, "float" to 1.5f,
            "boolean" to true, "set" to setOf("one", "two")
        )))
        assertEquals("serialized-keyset", snapshot.getString("keyset", null))
        assertEquals(7, snapshot.getInt("int", 0))
        assertEquals(8L, snapshot.getLong("long", 0))
        assertEquals(1.5f, snapshot.getFloat("float", 0f), 0f)
        assertTrue(snapshot.getBoolean("boolean", false))
        assertEquals(setOf("one", "two"), snapshot.getStringSet("set", null))
        assertFalse(snapshot.contains("missing"))
        assertNull(snapshot.getString("missing", null))
        assertThrows(UnsupportedOperationException::class.java) { snapshot.edit() }
    }

    @Test fun wrongKeysetTypeFailsInsteadOfLookingMissingAndGeneratingKeys() {
        val snapshot = ReadOnlyLegacyPreferences(ConfigurationValues.from(mapOf("keyset" to 42)))
        assertTrue(snapshot.contains("keyset"))
        assertThrows(ClassCastException::class.java) { snapshot.getString("keyset", null) }
    }

    @Test fun returnedMapsAndSetsCannotChangeTheDecoderSource() {
        val snapshot = ReadOnlyLegacyPreferences(ConfigurationValues.from(mapOf("key" to "original", "set" to setOf("original"))))
        @Suppress("UNCHECKED_CAST")
        val all = snapshot.all as MutableMap<String, Any>
        all["key"] = "modified"
        assertEquals("original", snapshot.getString("key", null))
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (snapshot.getStringSet("set", null) as MutableSet<String>).add("modified")
        }
        assertEquals(setOf("original"), snapshot.getStringSet("set", null))
    }

    @Test fun encryptedRawSnapshotUsesBackupWithoutMovingSourceOrExposingEdits() {
        val directory = temporaryFolder.newFolder()
        val prefs = File(directory, "shared_prefs").apply { mkdirs() }
        val current = File(prefs, "encrypted.xml").apply { writeText("<map><string name=\"keyset\">current</string></map>") }
        val backup = File(prefs, "encrypted.xml.bak").apply { writeText("<map><string name=\"keyset\">backup</string></map>") }
        val currentBytes = current.readBytes()
        val backupBytes = backup.readBytes()
        val source = ReadOnlyLegacyPreferences(SharedPreferencesFiles(directory, "encrypted").readValues())
        assertEquals("backup", source.getString("keyset", null))
        assertThrows(UnsupportedOperationException::class.java) { source.edit() }
        assertArrayEquals(currentBytes, current.readBytes())
        assertArrayEquals(backupBytes, backup.readBytes())
        backup.writeText("<map><string")
        assertThrows(IOException::class.java) { SharedPreferencesFiles(directory, "encrypted").readValues() }
        assertArrayEquals(currentBytes, current.readBytes())
        assertEquals("<map><string", backup.readText())
    }
}
