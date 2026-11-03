package com.nezhahq.agent.util

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** IO-only boundary; tests wrap this real DataStore to inject faults at transaction boundaries. */
internal interface ConfigurationPersistence {
    fun exists(): Boolean
    fun read(): StoredConfiguration
    fun write(value: StoredConfiguration)
    fun verify(): StoredConfiguration
    fun prepareNewKey()
    fun close()
    fun hasEstablishedStorage(): Boolean
    fun recordEstablishedStorage()
    fun resetPending(): Boolean
    fun recordResetIntent()
    fun recreateForReset()
    fun finishReset()
}

/** A single lifecycle owner. A cancelled store is joined before the same path is opened again. */
internal class EncryptedConfigurationDataStore(
    private val file: () -> File,
    private val keys: ConfigurationKeyProvider,
    private val serializer: Serializer<StoredConfiguration> = EncryptedConfigurationSerializer(keys)
) : ConfigurationPersistence {
    private var job: Job? = null
    private var store: DataStore<StoredConfiguration>? = null

    override fun exists(): Boolean = file().exists()
    override fun prepareNewKey() = keys.createIfAbsent()
    override fun read(): StoredConfiguration = runBlocking { dataStore().data.first() }
    override fun write(value: StoredConfiguration) {
        runBlocking { dataStore().updateData { value } }
    }

    /** Deliberately bypasses DataStore's cached flow after each successful atomic write. */
    override fun verify(): StoredConfiguration = file().inputStream().use { input ->
        runBlocking { serializer.readFrom(input) }
    }

    override fun close() {
        val closing = job
        job = null
        store = null
        if (closing != null) runBlocking { closing.cancelAndJoin() }
    }

    override fun resetPending(): Boolean = resetFile().exists()

    override fun hasEstablishedStorage(): Boolean =
        establishedFile().exists() || File(establishedFile().path + ".tmp").exists()

    override fun recordEstablishedStorage() = recordMarker(establishedFile())

    override fun recordResetIntent() {
        recordMarker(resetFile())
    }

    /** Markers contain only a version byte. Callers establish authority after ciphertext verifies. */
    private fun recordMarker(marker: File) {
        if (marker.exists()) return
        check(marker.parentFile!!.isDirectory || marker.parentFile!!.mkdirs())
        val temporary = File(marker.path + ".tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(byteArrayOf(1))
            stream.fd.sync()
        }
        check(temporary.renameTo(marker)) { "Unable to persist configuration lifecycle marker" }
        check(marker.inputStream().use { it.read() == 1 && it.read() == -1 }) {
            "Unable to verify configuration lifecycle marker"
        }
    }

    override fun recreateForReset() {
        check(resetPending()) { "Reset requires recorded user intent" }
        close()
        val target = file()
        check(!target.exists() || target.delete()) { "Unable to remove configuration for reset" }
        val temporary = File(target.path + ".tmp")
        check(!temporary.exists() || temporary.delete()) { "Unable to remove pending configuration" }
        keys.replaceForExplicitReset()
    }

    override fun finishReset() {
        check(resetFile().delete()) { "Unable to finish configuration reset" }
    }

    private fun resetFile(): File = File(file().path + ".reset")
    private fun establishedFile(): File = File(file().path + ".established")

    private fun dataStore(): DataStore<StoredConfiguration> {
        store?.let { return it }
        val scopeJob = SupervisorJob()
        job = scopeJob
        return DataStoreFactory.create(
            serializer = serializer,
            scope = CoroutineScope(scopeJob + Dispatchers.IO),
            produceFile = file
        ).also { store = it }
    }
}
