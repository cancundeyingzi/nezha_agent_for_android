package com.nezhahq.agent.util

import com.nezhahq.agent.SilentLoggerRule
import com.nezhahq.agent.core.config.ConfigurationSnapshot
import com.nezhahq.agent.core.config.StorageStatus
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConfigurationLoadingTest {
    @get:Rule
    val silentLogger = SilentLoggerRule()

    @Test
    fun theFirstReadWaitsForReadinessAndRunsOffTheCallingThread() = runBlocking {
        val readiness = ConfigurationReadiness()
        val reads = AtomicInteger()
        val callingThread = Thread.currentThread()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            awaitInitialConfiguration(readiness, readSnapshot = {
                assertTrue("storage read ran on the calling UI thread", callingThread !== Thread.currentThread())
                reads.incrementAndGet()
                ConfigurationSnapshot.DEFAULTS.copy(status = StorageStatus.READY)
            })
        }

        assertEquals(0, reads.get())
        assertFalse(result.isCompleted)
        readiness.complete(StorageStatus.READY)

        assertEquals(StorageStatus.READY, withTimeout(5_000) { result.await() }.status)
        assertEquals(1, reads.get())
    }

    @Test
    fun failedInitializationStillReadsTheCurrentUnavailableSnapshot() = runBlocking {
        val readiness = ConfigurationReadiness().apply { complete(StorageStatus.UNAVAILABLE) }
        val reads = AtomicInteger()
        val snapshot = awaitInitialConfiguration(readiness, readSnapshot = {
            reads.incrementAndGet()
            ConfigurationSnapshot.DEFAULTS
        })

        assertEquals(ConfigurationSnapshot.DEFAULTS, snapshot)
        assertEquals(1, reads.get())
    }

    @Test
    fun aNewWaiterReadsRecoveredStorageAfterTheFirstInitializationFailed() = runBlocking {
        val coordinator = ConfigurationStorageCoordinator(object : ConfigurationStorageOperations<Unit> {
            override fun openPlainStorage(): Unit = throw IOException("initial open failed")
            override fun isMigrationComplete(storage: Unit): Boolean = true
            override fun importLegacyEncryptedStorage(storage: Unit): LegacyImportResult = LegacyImportResult.COMPLETED
            override fun resetPlainStorage() = Unit
            override fun isResetStorage(storage: Unit): Boolean = true
        })
        val readiness = ConfigurationReadiness().apply { complete(coordinator.initialize()) }
        val reads = AtomicInteger()
        val readCurrentSnapshot = {
            reads.incrementAndGet()
            val currentStatus = coordinator.initialize()
            coordinator.read(ConfigurationSnapshot.DEFAULTS) {
                ConfigurationSnapshot.DEFAULTS.copy(status = currentStatus)
            }
        }

        val first = awaitInitialConfiguration(readiness, readCurrentSnapshot)
        assertFalse(first.status.isUsable)
        assertTrue(coordinator.reset())

        // A recreated ViewModel reuses the process-wide signal, whose original result stays failed.
        assertEquals(StorageStatus.UNAVAILABLE, readiness.await())
        val recreated = awaitInitialConfiguration(readiness, readCurrentSnapshot)

        assertEquals(StorageStatus.READY, recreated.status)
        assertEquals(2, reads.get())
    }

    @Test
    fun aReadFailureCompletesWithUnavailableDefaultsAndNoSensitiveLog() = runBlocking {
        val readiness = ConfigurationReadiness().apply { complete(StorageStatus.READY) }
        val secret = "configuration-secret-57291"
        val snapshot = awaitInitialConfiguration(readiness, readSnapshot = { throw IOException(secret) })

        assertEquals(ConfigurationSnapshot.DEFAULTS, snapshot)
        assertFalse(silentLogger.messages.joinToString().contains(secret))
        assertFalse(Logger.getLogString().contains(secret))
    }

    @Test
    fun cancellationIsPreservedAsCancellation() {
        val readiness = ConfigurationReadiness().apply { complete(StorageStatus.READY) }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                awaitInitialConfiguration(readiness, readSnapshot = { throw CancellationException("cancelled") })
            }
        }
    }
}
