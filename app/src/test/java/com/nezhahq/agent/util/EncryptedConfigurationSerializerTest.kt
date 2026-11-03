package com.nezhahq.agent.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EncryptedConfigurationSerializerTest {
    private val keys = TestConfigurationKeys().apply { createIfAbsent() }
    private val serializer = EncryptedConfigurationSerializer(keys)
    private val document = StoredConfiguration(
        initialized = true,
        values = ConfigurationValues.from(ConfigurationStorageFixture.CONNECTION + mapOf(
            "strings" to setOf("one", "two"), "long" to 123L, "float" to 1.25f
        )),
        cleanup = ConfigurationCleanup.ALL_LEGACY
    )

    @Test fun roundTripPreservesEverySupportedTypeAndMigrationState() = runBlocking<Unit> {
        val bytes = encode(document)
        assertEquals(document, serializer.readFrom(ByteArrayInputStream(bytes)))
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("main-secret-5872"))
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("simulator-secret-9914"))
        assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("panel.example"))
    }

    @Test fun encryptingIdenticalConfigurationUsesDistinctRandomIvs() = runBlocking<Unit> {
        val ivs = (1..32).map { encode(document).copyOfRange(6, 18).toList() }.toSet()
        assertEquals(32, ivs.size)
    }

    @Test fun tamperedHeaderIvCiphertextAndTagAreRejected() = runBlocking<Unit> {
        val bytes = encode(document)
        listOf(0, 5, 6, 18, bytes.lastIndex).forEach { offset ->
            val modified = bytes.clone()
            modified[offset] = (modified[offset].toInt() xor 1).toByte()
            assertThrows(IOException::class.java) {
                runBlocking { serializer.readFrom(ByteArrayInputStream(modified)) }
            }
        }
        assertThrows(IOException::class.java) {
            runBlocking { serializer.readFrom(ByteArrayInputStream(bytes.copyOf(bytes.size - 1))) }
        }
    }

    @Test fun decodingNeverCreatesOrReplacesMissingKey() = runBlocking<Unit> {
        val bytes = encode(document)
        val creates = keys.creates
        keys.key = null
        assertThrows(IOException::class.java) {
            runBlocking { serializer.readFrom(ByteArrayInputStream(bytes)) }
        }
        assertEquals(creates, keys.creates)
        assertEquals(0, keys.replacements)
        assertNull(keys.key)
    }

    @Test fun changedKeyCannotAuthenticateExistingCiphertext() = runBlocking<Unit> {
        val bytes = encode(document)
        keys.key = keys.newKey()
        assertThrows(IOException::class.java) {
            runBlocking { serializer.readFrom(ByteArrayInputStream(bytes)) }
        }
    }

    @Test fun emptyOrOversizedInputIsRejectedWithoutCreatingKey() = runBlocking<Unit> {
        listOf(byteArrayOf(), ByteArray(1024 * 1024 + 31)).forEach { bytes ->
            assertThrows(IOException::class.java) {
                runBlocking { serializer.readFrom(ByteArrayInputStream(bytes)) }
            }
        }
        assertEquals(1, keys.creates)
    }

    private suspend fun encode(value: StoredConfiguration): ByteArray = ByteArrayOutputStream().also {
        serializer.writeTo(value, it)
    }.toByteArray()
}
