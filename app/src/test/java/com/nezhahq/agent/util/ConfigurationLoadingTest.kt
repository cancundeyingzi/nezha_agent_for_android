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

    @get:Rule val temporaryFolder = org.junit.rules.TemporaryFolder()

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
        ConfigurationStorageFixture(temporaryFolder.newFolder(), ConfigurationStorageFixture.CONNECTION).use { fixture ->
            fixture.keys.inaccessible = true
            val coordinator = fixture.coordinator
            val readiness = ConfigurationReadiness().apply { complete(coordinator.initialize()) }
            val reads = AtomicInteger()
            val readCurrentSnapshot = {
                reads.incrementAndGet()
                ConfigurationSnapshot.DEFAULTS.copy(status = coordinator.currentStatus())
            }
            assertFalse(awaitInitialConfiguration(readiness, readCurrentSnapshot).status.isUsable)
            fixture.keys.inaccessible = false
            assertEquals(StorageStatus.READY, coordinator.initialize(retry = true))
            // A recreated ViewModel observes the recovered cache, not the obsolete signal result.
            assertEquals(StorageStatus.UNAVAILABLE, readiness.await())
            assertEquals(StorageStatus.READY, awaitInitialConfiguration(readiness, readCurrentSnapshot).status)
            assertEquals(2, reads.get())
        }
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
