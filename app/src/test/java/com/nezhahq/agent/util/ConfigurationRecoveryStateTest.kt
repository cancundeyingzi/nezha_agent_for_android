package com.nezhahq.agent.util

import com.nezhahq.agent.StubConfigRepository
import com.nezhahq.agent.core.config.StorageStatus
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConfigurationRecoveryStateTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun failedConfirmedResetThenRetryCannotRestoreOldUiSecretsOrPrivileges() {
        ConfigurationStorageFixture(temporaryFolder.newFolder(), ConfigurationStorageFixture.CONNECTION).use { f ->
            assertEquals(StorageStatus.READY, f.coordinator.initialize())
            val recovery = ConfigurationRecoveryState()
            val repository = Repository(f.coordinator)
            recovery.confirmReset()
            f.persistence.beforeFinishReset = { throw IOException("reset finalization interrupted") }
            assertFalse(repository.resetStorage())
            assertEquals(StorageStatus.UNAVAILABLE, repository.storageStatus())
            assertEquals(SnapshotApplyMode.RELOAD_AFTER_RESET, recovery.applyMode)

            f.persistence.beforeFinishReset = {}
            assertEquals(StorageStatus.READY, recovery.retryStorage(repository))
            assertEquals(2, repository.resets)
            assertEquals(0, repository.initializations)
            assertTrue(f.backend.verify().values.entries.isEmpty())
            // MainViewModel uses this exact strategy for each field before acknowledging it.
            val mode = recovery.applyMode
            assertEquals("", mode.resolve("old-main-secret", "", ""))
            assertEquals("", mode.resolve("old-simulator-secret", "", ""))
            assertEquals("", mode.resolve("old-server", "", ""))
            assertFalse(mode.resolve(true, false, false)) // Root, tools and remote capabilities.
            assertEquals(5555, mode.resolve(443, 5555, 5555))
            assertEquals(SnapshotApplyMode.RELOAD_AFTER_RESET, recovery.applyMode)
            recovery.snapshotApplied(mode)
            assertEquals(SnapshotApplyMode.INITIAL_LOAD, recovery.applyMode)
            assertFalse(f.rootAuthorized)
        }
    }

    @Test fun resetIntentWriteFailureStillContinuesTheAlreadyConfirmedReset() {
        ConfigurationStorageFixture(temporaryFolder.newFolder(), ConfigurationStorageFixture.CONNECTION).use { f ->
            assertEquals(StorageStatus.READY, f.coordinator.initialize())
            val repository = Repository(f.coordinator)
            val recovery = ConfigurationRecoveryState().apply { confirmReset() }
            f.persistence.beforeRecordReset = { throw IOException("unable to persist reset intent") }
            assertFalse(repository.resetStorage())
            assertFalse(f.backend.resetPending())
            assertEquals(ConfigurationStorageFixture.CONNECTION, f.backend.verify().values.entries)
            f.persistence.beforeRecordReset = {}
            assertEquals(StorageStatus.READY, recovery.retryStorage(repository))
            assertTrue(f.backend.verify().values.entries.isEmpty())
            assertEquals(SnapshotApplyMode.RELOAD_AFTER_RESET, recovery.applyMode)
        }
    }

    @Test fun anotherCallerCompletingResetDoesNotClearTheUiReplacementRequirement() {
        ConfigurationStorageFixture(temporaryFolder.newFolder(), ConfigurationStorageFixture.CONNECTION).use { f ->
            assertEquals(StorageStatus.READY, f.coordinator.initialize())
            val repository = Repository(f.coordinator)
            val recovery = ConfigurationRecoveryState().apply { confirmReset() }
            f.persistence.beforeFinishReset = { throw IOException("interrupted") }
            assertFalse(repository.resetStorage())
            f.persistence.beforeFinishReset = {}
            assertEquals(StorageStatus.READY, f.coordinator.initialize(retry = true))
            assertEquals(StorageStatus.READY, recovery.retryStorage(repository))
            assertEquals(SnapshotApplyMode.RELOAD_AFTER_RESET, recovery.applyMode)
            assertEquals("", recovery.applyMode.resolve("stale-secret", "", ""))
        }
    }

    @Test fun ordinaryRetryPreservesEditedFieldsAndNeverRequestsReset() {
        ConfigurationStorageFixture(temporaryFolder.newFolder(), ConfigurationStorageFixture.CONNECTION).use { f ->
            assertEquals(StorageStatus.READY, f.coordinator.initialize())
            val repository = Repository(f.coordinator)
            val recovery = ConfigurationRecoveryState()
            assertEquals(StorageStatus.READY, recovery.retryStorage(repository))
            assertEquals(0, repository.resets)
            assertEquals(1, repository.initializations)
            assertEquals(SnapshotApplyMode.INITIAL_LOAD, recovery.applyMode)
            assertEquals("edited-secret", recovery.applyMode.resolve("edited-secret", "saved-secret", ""))
            assertEquals(8443, recovery.applyMode.resolve(8443, 443, 5555))
            assertEquals(ConfigurationStorageFixture.CONNECTION, f.backend.verify().values.entries)
            assertEquals(0, f.keys.replacements)
        }
    }

    private class Repository(private val storage: ConfigurationStorageCoordinator) : StubConfigRepository() {
        var resets = 0
        var initializations = 0
        override fun storageStatus(): StorageStatus = storage.currentStatus()
        override fun resetStorage(): Boolean { resets++; return storage.reset() }
        override fun initializeStorage(retry: Boolean): StorageStatus {
            initializations++
            return storage.initialize(retry)
        }
    }
}
