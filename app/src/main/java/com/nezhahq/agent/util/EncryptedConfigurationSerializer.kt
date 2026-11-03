package com.nezhahq.agent.util

import androidx.datastore.core.Serializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keys are created by an explicit storage lifecycle operation, never while decoding a file. */
internal interface ConfigurationKeyProvider {
    fun existingKey(): SecretKey
    fun createIfAbsent()
    fun replaceForExplicitReset()
}

/** Immutable copies also protect DataStore's equality/hash checks from mutable preference sets. */
internal class ConfigurationValues private constructor(val entries: Map<String, Any>) {
    fun string(key: String, default: String = ""): String = entries[key] as? String ?: default
    fun int(key: String, default: Int): Int = entries[key] as? Int ?: default
    fun boolean(key: String, default: Boolean = false): Boolean = entries[key] as? Boolean ?: default
    fun contains(key: String): Boolean = entries.containsKey(key)
    fun changed(changes: Map<String, Any>): ConfigurationValues = from(entries + changes)
    override fun equals(other: Any?): Boolean = other is ConfigurationValues && entries == other.entries
    override fun hashCode(): Int = entries.hashCode()

    companion object {
        val EMPTY = ConfigurationValues(emptyMap())

        fun from(values: Map<String, *>): ConfigurationValues {
            val copied = values.mapValues { (_, value) ->
                when (value) {
                    is String, is Int, is Long, is Float, is Boolean -> value
                    is Set<*> -> {
                        require(value.all { it is String }) { "Unsupported configuration value" }
                        Collections.unmodifiableSet(value.map { it as String }.toSet())
                    }
                    else -> throw IOException("Unsupported configuration value")
                }
            }
            return ConfigurationValues(Collections.unmodifiableMap(copied))
        }
    }
}

internal enum class ConfigurationCleanup { NONE, PLAINTEXT, ALL_LEGACY }

internal data class StoredConfiguration(
    // The uninitialized default differs from a persisted empty document: first run must write.
    val initialized: Boolean = false,
    val values: ConfigurationValues = ConfigurationValues.EMPTY,
    val cleanup: ConfigurationCleanup = ConfigurationCleanup.NONE,
    val legacyUnreadable: Boolean = false
)

/** Entire document is AES-256-GCM ciphertext; format/version are authenticated as AAD. */
internal class EncryptedConfigurationSerializer(
    private val keys: ConfigurationKeyProvider
) : Serializer<StoredConfiguration> {
    override val defaultValue = StoredConfiguration()

    override suspend fun readFrom(input: InputStream): StoredConfiguration = try {
        val encoded = readBounded(input)
        if (encoded.size < HEADER.size + IV_SIZE + TAG_SIZE ||
            !encoded.copyOfRange(0, HEADER.size).contentEquals(HEADER)) {
            throw IOException("Invalid encrypted configuration format")
        }
        val iv = encoded.copyOfRange(HEADER.size, HEADER.size + IV_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keys.existingKey(), GCMParameterSpec(TAG_SIZE * 8, iv))
        cipher.updateAAD(HEADER)
        decode(cipher.doFinal(encoded, HEADER.size + IV_SIZE, encoded.size - HEADER.size - IV_SIZE))
    } catch (error: Exception) {
        // No corruption handler is installed: failures preserve the ciphertext and require retry
        // or an explicitly confirmed reset. Exception details must not contain configuration.
        throw IOException("Unable to decrypt configuration", error)
    }

    override suspend fun writeTo(t: StoredConfiguration, output: OutputStream) {
        check(t.initialized) { "Refusing to persist uninitialized configuration" }
        val plain = encode(t)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            // Let the provider generate a fresh random IV. Android Keystore rejects caller IVs
            // when randomized encryption is required; that protection remains enabled.
            cipher.init(Cipher.ENCRYPT_MODE, keys.existingKey())
            check(cipher.iv.size == IV_SIZE) { "Unexpected AES-GCM IV size" }
            cipher.updateAAD(HEADER)
            output.write(HEADER)
            output.write(cipher.iv)
            output.write(cipher.doFinal(plain))
        } finally {
            plain.fill(0)
        }
    }

    private fun encode(document: StoredConfiguration): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeBoolean(document.initialized)
            out.writeInt(document.cleanup.ordinal)
            out.writeBoolean(document.legacyUnreadable)
            require(document.values.entries.size <= MAX_ENTRIES)
            out.writeInt(document.values.entries.size)
            document.values.entries.toSortedMap().forEach { (key, value) ->
                out.writeString(key)
                when (value) {
                    is String -> { out.writeByte(1); out.writeString(value) }
                    is Int -> { out.writeByte(2); out.writeInt(value) }
                    is Long -> { out.writeByte(3); out.writeLong(value) }
                    is Float -> { out.writeByte(4); out.writeFloat(value) }
                    is Boolean -> { out.writeByte(5); out.writeBoolean(value) }
                    is Set<*> -> {
                        out.writeByte(6)
                        require(value.size <= MAX_ENTRIES)
                        out.writeInt(value.size)
                        value.map { it as String }.sorted().forEach { out.writeString(it) }
                    }
                    else -> throw IOException("Unsupported configuration value")
                }
            }
        }
        return bytes.toByteArray().also { require(it.size <= MAX_BYTES) }
    }

    private fun decode(bytes: ByteArray): StoredConfiguration = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            check(input.readBoolean()) { "Uninitialized encrypted configuration" }
            val cleanup = ConfigurationCleanup.entries.getOrNull(input.readInt())
                ?: throw IOException("Invalid configuration metadata")
            val unreadable = input.readBoolean()
            val values = linkedMapOf<String, Any>()
            repeat(input.readCount()) {
                val key = input.readString()
                check(!values.containsKey(key)) { "Duplicate configuration field" }
                values[key] = when (input.readUnsignedByte()) {
                    1 -> input.readString()
                    2 -> input.readInt()
                    3 -> input.readLong()
                    4 -> input.readFloat()
                    5 -> input.readBoolean()
                    6 -> buildSet { repeat(input.readCount()) { add(input.readString()) } }
                    else -> throw IOException("Invalid configuration value type")
                }
            }
            check(input.read() == -1) { "Trailing configuration data" }
            StoredConfiguration(true, ConfigurationValues.from(values), cleanup, unreadable)
        }
    } finally {
        bytes.fill(0)
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        require(size in 0..MAX_STRING_BYTES)
        val bytes = ByteArray(size)
        readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun DataInputStream.readCount(): Int = readInt().also { require(it in 0..MAX_ENTRIES) }

    private fun readBounded(input: InputStream): ByteArray {
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return bytes.toByteArray()
            if (bytes.size() + count > MAX_BYTES + HEADER.size + IV_SIZE + TAG_SIZE) {
                throw IOException("Encrypted configuration is too large")
            }
            bytes.write(buffer, 0, count)
        }
    }

    private companion object {
        val HEADER = byteArrayOf(0x4e, 0x5a, 0x43, 0x46, 0x47, 1)
        const val IV_SIZE = 12
        const val TAG_SIZE = 16
        const val MAX_BYTES = 1024 * 1024
        const val MAX_STRING_BYTES = 64 * 1024
        const val MAX_ENTRIES = 256
    }
}
