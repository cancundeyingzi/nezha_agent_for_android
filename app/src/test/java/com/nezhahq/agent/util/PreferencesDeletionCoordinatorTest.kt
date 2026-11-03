package com.nezhahq.agent.util

import org.junit.Assert.*
import org.junit.Test

class PreferencesDeletionCoordinatorTest {
    @Test fun api24ClearsHeldCacheBeforeInvalidatingPlatformCacheAndDeletingFiles() {
        val operations = Operations(platform = true)
        assertTrue(PreferencesDeletionCoordinator(operations).delete())
        assertEquals(listOf("clear", "platform", "files"), operations.events)
    }

    @Test fun api24ReportsFailureWhenLiveCacheCannotBeCleared() {
        val operations = Operations(platform = true, clears = listOf(false))
        assertFalse(PreferencesDeletionCoordinator(operations).delete())
        assertEquals(listOf("clear"), operations.events)
    }

    @Test fun api23RetriesCacheClearAfterRemovingBrokenFiles() {
        val operations = Operations(platform = false, clears = listOf(false, true))
        assertTrue(PreferencesDeletionCoordinator(operations).delete())
        assertEquals(listOf("clear", "files", "clear", "files"), operations.events)
    }

    @Test fun api23KeepsCleanupPendingIfSecondCacheClearFails() {
        val operations = Operations(platform = false, clears = listOf(false, false))
        assertFalse(PreferencesDeletionCoordinator(operations).delete())
        assertEquals(listOf("clear", "files", "clear"), operations.events)
    }

    private class Operations(platform: Boolean, clears: List<Boolean> = listOf(true)) : PreferencesDeletionOperations {
        override val supportsPlatformDelete = platform
        val events = mutableListOf<String>()
        private val pending = ArrayDeque(clears)
        override fun clearCacheSynchronously(): Boolean { events += "clear"; return pending.removeFirst() }
        override fun deleteWithPlatform(): Boolean { events += "platform"; return true }
        override fun deleteFiles(): Boolean { events += "files"; return true }
    }
}
