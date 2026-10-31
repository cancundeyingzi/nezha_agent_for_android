package com.nezhahq.agent.util

import com.nezhahq.agent.core.config.StorageStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The latch that keeps the main thread off the storage lock during the legacy-store migration.
 *
 * Two properties carry the whole design: a waiter is always released — a missed release is a UI
 * frozen on its loading state for the life of the process — and giving up on one wait never
 * consumes the signal.
 */
class ConfigurationReadinessTest {

    @Test
    fun waiterArrivingAfterCompletionGetsTheStatusImmediately() = runBlocking {
        val readiness = ConfigurationReadiness()
        readiness.complete(StorageStatus.READY)

        assertTrue(readiness.isReady)
        assertEquals(StorageStatus.READY, readiness.await())
    }

    @Test
    fun waiterArrivingFirstIsResumedByCompletion() = runBlocking {
        val readiness = ConfigurationReadiness()
        val observed = CompletableDeferred<StorageStatus>()
        val waiterStarted = CompletableDeferred<Unit>()
        val waiter = launch {
            waiterStarted.complete(Unit)
            observed.complete(readiness.await())
        }
        waiterStarted.await()
        assertFalse(readiness.isReady)

        readiness.complete(StorageStatus.LEGACY_UNREADABLE)

        assertEquals(
            StorageStatus.LEGACY_UNREADABLE,
            withTimeout(GENEROUS_TIMEOUT_MS) { observed.await() }
        )
        waiter.join()
    }

    @Test
    fun failedInitializationStillReleasesEveryWaiter() = runBlocking {
        // 初始化失败必须照样放行：UNAVAILABLE 是界面能显示、用户能处理的结论，而一个永远
        // 不被唤醒的等待者是一块永远转圈的界面。
        val readiness = ConfigurationReadiness()
        val observed = List(3) { CompletableDeferred<StorageStatus>() }
        observed.forEach { slot -> launch { slot.complete(readiness.await()) } }

        readiness.complete(StorageStatus.UNAVAILABLE)

        observed.forEach { slot ->
            assertEquals(
                StorageStatus.UNAVAILABLE,
                withTimeout(GENEROUS_TIMEOUT_MS) { slot.await() }
            )
        }
    }

    @Test
    fun awaitWithinReturnsTheStatusThatArrivesBeforeTheDeadline() = runBlocking {
        val readiness = ConfigurationReadiness()
        launch {
            delay(SHORT_DELAY_MS)
            readiness.complete(StorageStatus.READY)
        }

        assertEquals(StorageStatus.READY, readiness.awaitWithin(GENEROUS_TIMEOUT_MS))
    }

    @Test
    fun expiredAwaitWithinAbandonsOnlyThatWaitAndNotTheSignal() = runBlocking {
        val readiness = ConfigurationReadiness()

        assertNull(readiness.awaitWithin(EXPIRING_TIMEOUT_MS))
        assertFalse(readiness.isReady)

        // A slow migration that finishes after the warning still has to reach everyone waiting on
        // it; the timeout only cancelled that one call's continuation.
        readiness.complete(StorageStatus.LEGACY_UNREADABLE)

        assertEquals(StorageStatus.LEGACY_UNREADABLE, readiness.await())
        assertEquals(StorageStatus.LEGACY_UNREADABLE, readiness.awaitWithin(GENEROUS_TIMEOUT_MS))
    }

    @Test
    fun firstPublishedOutcomeWins() = runBlocking {
        val readiness = ConfigurationReadiness()

        readiness.complete(StorageStatus.READY)
        readiness.complete(StorageStatus.UNAVAILABLE)

        assertEquals(StorageStatus.READY, readiness.await())
    }

    private companion object {
        /** Long enough that a loaded machine cannot turn a passing wait into a spurious failure. */
        const val GENEROUS_TIMEOUT_MS = 5_000L

        /** Short enough to keep the suite fast; expiring is the expected outcome, so it is stable. */
        const val EXPIRING_TIMEOUT_MS = 20L

        const val SHORT_DELAY_MS = 10L
    }
}
