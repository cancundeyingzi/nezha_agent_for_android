package com.nezhahq.agent.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeShutdownGateTest {
    @Test
    fun submitDoesNotBlockTheCaller() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gate = RuntimeShutdownGate(scope) { }
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)

        val job = gate.submit {
            entered.countDown()
            release.await()
        }

        assertTrue(entered.await(2, TimeUnit.SECONDS))
        assertFalse(job.isCompleted)
        release.countDown()
        runBlocking { job.join() }
        scope.cancel()
    }

    @Test
    fun shutdownsRunOneAtATimeInSubmissionOrder() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gate = RuntimeShutdownGate(scope) { }
        val events = mutableListOf<String>()
        val overlapped = AtomicBoolean(false)
        val running = AtomicBoolean(false)
        val releaseFirst = CountDownLatch(1)
        val firstEntered = CountDownLatch(1)

        gate.submit {
            if (!running.compareAndSet(false, true)) overlapped.set(true)
            synchronized(events) { events += "first" }
            firstEntered.countDown()
            releaseFirst.await()
            running.set(false)
        }
        assertTrue(firstEntered.await(2, TimeUnit.SECONDS))

        val second = gate.submit {
            if (!running.compareAndSet(false, true)) overlapped.set(true)
            synchronized(events) { events += "second" }
            running.set(false)
        }
        releaseFirst.countDown()
        runBlocking { second.join() }

        assertEquals(listOf("first", "second"), events)
        assertFalse(overlapped.get())
        scope.cancel()
    }

    @Test
    fun awaitIdleReturnsOnlyAfterTheLastShutdownFinished() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val gate = RuntimeShutdownGate(scope) { }
        val finished = AtomicBoolean(false)
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)

        gate.submit {
            entered.countDown()
            release.await()
            finished.set(true)
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        release.countDown()

        runBlocking { gate.awaitIdle() }

        assertTrue(finished.get())
        scope.cancel()
    }

    @Test
    fun awaitIdleReturnsImmediatelyWhenNothingWasSubmitted() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        runBlocking { RuntimeShutdownGate(scope) { }.awaitIdle() }

        scope.cancel()
    }

    @Test
    fun failedShutdownRetainsTheQueueAndRetriesBeforeAllowingTheNextOne() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val failures = Channel<Throwable>(Channel.UNLIMITED)
        val releaseRetry = Channel<Unit>()
        val delays = mutableListOf<Long>()
        val gate = RuntimeShutdownGate(
            scope = scope,
            delayBeforeRetry = { delays += it; releaseRetry.receive() },
            onFailure = { failures.trySend(it) }
        )
        var attempts = 0
        var secondRan = false
        val first = gate.submit { if (++attempts <= 2) error("teardown failed") }
        val second = gate.submit { secondRan = true }

        try {
            repeat(2) {
                assertEquals("teardown failed", withTimeout(2_000) { failures.receive() }.message)
                val failure = runCatching { gate.awaitIdle() }.exceptionOrNull()
                assertTrue(failure is RuntimeShutdownPendingException)
                assertFalse(first.isCompleted)
                assertFalse(secondRan)
                releaseRetry.send(Unit)
            }
            withTimeout(2_000) { second.join(); gate.awaitIdle() }

            assertTrue(first.isCompleted)
            assertTrue(secondRan)
            assertEquals(3, attempts)
            assertEquals(listOf(5_000L, 10_000L), delays)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellingAnIdleWaitDoesNotCancelThePreviousShutdown() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val gate = RuntimeShutdownGate(scope) { }
        val release = CompletableDeferred<Unit>()
        var finished = false
        val shutdown = gate.submit { release.await(); finished = true }
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { gate.awaitIdle() }

        try {
            waiter.cancelAndJoin()
            assertFalse(shutdown.isCancelled)
            assertFalse(finished)
            release.complete(Unit)
            withTimeout(2_000) { shutdown.join(); gate.awaitIdle() }
            assertTrue(finished)
        } finally {
            release.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun cancellingTheCompletionHandleDoesNotReleaseOrCancelCleanup() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val gate = RuntimeShutdownGate(scope) { }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val shutdown = gate.submit { entered.complete(Unit); release.await() }

        try {
            withTimeout(2_000) { entered.await() }
            shutdown.cancelAndJoin()
            var unlocked = false
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
                gate.awaitIdle()
                unlocked = true
            }
            assertFalse(unlocked)
            release.complete(Unit)
            withTimeout(2_000) { waiter.join() }
            assertTrue(unlocked)
        } finally {
            release.complete(Unit)
            scope.cancel()
        }
    }

    @Test
    fun aThrowingFailureReporterCannotStopCleanupRetries() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val cleanupFailure = IllegalStateException("cleanup failed")
        var attempts = 0
        val gate = RuntimeShutdownGate(
            scope = scope,
            delayBeforeRetry = {},
            onFailure = { error("reporting failed") }
        )

        try {
            val shutdown = gate.submit { if (++attempts == 1) throw cleanupFailure }
            withTimeout(2_000) { shutdown.join(); gate.awaitIdle() }
            assertEquals(2, attempts)
            assertEquals(listOf("reporting failed"), cleanupFailure.suppressed.map(Throwable::message))
        } finally {
            scope.cancel()
        }
    }
}
