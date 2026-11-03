package com.nezhahq.agent.util

import androidx.datastore.core.Serializer
import com.nezhahq.agent.core.security.RootModeMutationCoordinator
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

internal class TestConfigurationKeys : ConfigurationKeyProvider {
    var key: SecretKey? = null
    var inaccessible = false
    var creates = 0
    var replacements = 0
    override fun existingKey(): SecretKey {
        if (inaccessible) throw GeneralSecurityException("temporarily unavailable")
        return key ?: throw GeneralSecurityException("missing key")
    }
    override fun createIfAbsent() {
        if (inaccessible) throw GeneralSecurityException("temporarily unavailable")
        if (key == null) { key = newKey(); creates++ }
    }
    override fun replaceForExplicitReset() {
        if (inaccessible) throw GeneralSecurityException("temporarily unavailable")
        replacements++
        key = newKey()
    }
    fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}

internal class FileLegacyConfiguration(
    directory: File,
    var plain: Map<String, Any> = emptyMap(),
    var marker: Boolean = false,
    var encrypted: Map<String, Any>? = null,
    private val events: MutableList<String> = mutableListOf()
) : LegacyConfigurationOperations {
    val xml = File(directory, "legacy.xml")
    val backup = File(directory, "legacy.xml.bak")
    val encryptedFile = File(directory, "encrypted.xml")
    var encryptedUnreadable = false
    var failPlainCleanup = false
    var failEncryptedCleanup = false
    var plainReads = 0
    var encryptedReads = 0
    var beforePlainRead: () -> Unit = {}
    var beforeDelete: () -> Unit = {}

    init {
        directory.mkdirs()
        if (plain.isNotEmpty() || marker) {
            xml.writeText(plain.toString())
            backup.writeText(plain.toString())
        }
        if (encrypted != null) encryptedFile.writeBytes(byteArrayOf(9, 8, 7))
    }

    override fun readPlaintext(): LegacyPlaintextConfiguration {
        plainReads++
        beforePlainRead()
        return LegacyPlaintextConfiguration(ConfigurationValues.from(plain), marker)
    }
    override fun encryptedExists(): Boolean = encryptedFile.exists()
    override fun readEncrypted(): ConfigurationValues {
        encryptedReads++
        if (encryptedUnreadable) throw IOException("legacy key unavailable")
        return ConfigurationValues.from(encrypted.orEmpty())
    }
    override fun deletePlaintext(): Boolean {
        events += "deletePlaintext"
        beforeDelete()
        if (failPlainCleanup) return false
        plain = emptyMap()
        marker = false
        return (!xml.exists() || xml.delete()) && (!backup.exists() || backup.delete())
    }
    override fun deleteEncrypted(): Boolean {
        events += "deleteEncrypted"
        if (failEncryptedCleanup) return false
        encrypted = null
        return !encryptedFile.exists() || encryptedFile.delete()
    }
}

internal class FaultInjectingConfigurationPersistence(
    private val delegate: ConfigurationPersistence,
    private val events: MutableList<String>
) : ConfigurationPersistence by delegate {
    var beforeWrite: () -> Unit = {}
    var afterWrite: () -> Unit = {}
    var beforeVerify: () -> Unit = {}
    var beforeRecreate: () -> Unit = {}
    var beforeFinishReset: () -> Unit = {}
    override fun write(value: StoredConfiguration) {
        beforeWrite()
        events += "write"
        delegate.write(value)
        events += "written"
        afterWrite()
    }
    override fun verify(): StoredConfiguration {
        beforeVerify()
        events += "verify"
        return delegate.verify().also { events += "verified" }
    }
    override fun recreateForReset() {
        beforeRecreate()
        delegate.recreateForReset()
    }
    override fun finishReset() {
        beforeFinishReset()
        delegate.finishReset()
    }
}

internal class ConfigurationStorageFixture(
    directory: File,
    plain: Map<String, Any> = emptyMap(),
    marker: Boolean = false,
    encrypted: Map<String, Any>? = null,
    val keys: TestConfigurationKeys = TestConfigurationKeys(),
    serializer: Serializer<StoredConfiguration> = EncryptedConfigurationSerializer(keys)
) : AutoCloseable {
    val file = File(directory, "configuration/agent.enc")
    val events = mutableListOf<String>()
    val legacy = FileLegacyConfiguration(directory, plain, marker, encrypted, events)
    val backend = EncryptedConfigurationDataStore({ file }, keys, serializer)
    val persistence = FaultInjectingConfigurationPersistence(backend, events)
    var rootAuthorized = false
    val rootEvents = mutableListOf<Boolean>()
    val failures = mutableListOf<Exception>()
    private val root = RootModeMutationCoordinator { rootAuthorized = it; rootEvents += it }
    var coordinator = ConfigurationStorageCoordinator(persistence, legacy, root, failures::add)
        private set

    fun reopen() {
        coordinator.close()
        coordinator = ConfigurationStorageCoordinator(persistence, legacy, root, failures::add)
    }
    override fun close() = coordinator.close()

    companion object {
        val CONNECTION = mapOf<String, Any>(
            "server" to "panel.example", "port" to 443, "secret" to "main-secret-5872",
            "uuid" to "device-uuid", "root_mode" to true, "enable_auto_start" to true,
            "simulator_secret" to "simulator-secret-9914"
        )
    }
}
