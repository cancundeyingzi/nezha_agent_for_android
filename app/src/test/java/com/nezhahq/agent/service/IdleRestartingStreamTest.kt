package com.nezhahq.agent.service

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The contract that keeps an idle task stream from taking a whole connection with it.
 */
class IdleRestartingStreamTest {

    @Test
    fun idleDeadlineAlsoExpiresWhileTheMessageHandlerIsBackpressured() = runBlocking {
        val handlerEntered = CompletableDeferred<Unit>()
        val handlerStopped = CompletableDeferred<Unit>()
        val replacementOpened = CompletableDeferred<Unit>()
        var opens = 0
        val consumer = launch {
            consumeWithIdleRestart<String>(
                idleTimeoutMillis = SHORT_IDLE_EXPIRY_MILLIS,
                streamName = "backpressured stream",
                openStream = {
                    val generation = ++opens
                    if (generation == 2) replacementOpened.complete(Unit)
                    flow<String> {
                        if (generation == 1) emit("blocked")
                        awaitCancellation()
                    }.produceIn(this)
                }
            ) {
                handlerEntered.complete(Unit)
                try { awaitCancellation() } finally { handlerStopped.complete(Unit) }
            }
        }
        try {
            withTimeout(TEST_TIMEOUT_MILLIS) { handlerEntered.await(); replacementOpened.await() }
            assertTrue(handlerStopped.isCompleted)
        } finally { consumer.cancelAndJoin() }
    }

    @Test
    fun handlerTimeConsumesTheSameDeadlineAsTheFollowingReceive() = runBlocking {
        var nanos = 0L
        var opens = 0
        val replacementOpened = CompletableDeferred<Unit>()
        val consumer = launch {
            consumeWithIdleRestart<String>(
                idleTimeoutMillis = NO_IDLE_EXPIRY_MILLIS,
                streamName = "shared deadline",
                nanoTime = { nanos },
                openStream = {
                    val generation = ++opens
                    if (generation == 2) replacementOpened.complete(Unit)
                    flow<String> {
                        if (generation == 1) emit("work")
                        awaitCancellation()
                    }.produceIn(this)
                }
            ) { nanos += NO_IDLE_EXPIRY_MILLIS * 1_000_000 }
        }
        try {
            withTimeout(TEST_TIMEOUT_MILLIS) { replacementOpened.await() }
            assertTrue(opens >= 2)
        } finally { consumer.cancelAndJoin() }
    }

    @Test
    fun aHandlerDashboardTimeoutIsNotMistakenForTheInternalIdleDeadline() {
        var opens = 0
        val failure = assertThrows(DashboardSessionTimeoutException::class.java) {
            runBlocking {
                consumeWithIdleRestart<String>(
                    idleTimeoutMillis = NO_IDLE_EXPIRY_MILLIS,
                    streamName = "handler failure",
                    openStream = {
                        opens++
                        flow { emit("work"); awaitCancellation() }.produceIn(this)
                    }
                ) { throw DashboardSessionTimeoutException("handler's own timeout") }
            }
        }
        assertEquals("handler's own timeout", failure.message)
        assertEquals(1, opens)
    }

    @Test
    fun aHandlerCoroutineTimeoutPropagatesWithoutRestartingTheStream() {
        var opens = 0
        assertThrows(kotlinx.coroutines.TimeoutCancellationException::class.java) {
            runBlocking {
                consumeWithIdleRestart<String>(
                    idleTimeoutMillis = NO_IDLE_EXPIRY_MILLIS,
                    streamName = "handler timeout",
                    openStream = {
                        opens++
                        flow { emit("work"); awaitCancellation() }.produceIn(this)
                    }
                ) { withTimeout(SHORT_IDLE_EXPIRY_MILLIS) { awaitCancellation() } }
            }
        }
        assertEquals(1, opens)
    }

    @Test
    fun cancellationArrivingDuringAnIdleStopHookStillPreventsReplacement() = runBlocking {
        val stopHookEntered = CompletableDeferred<Unit>()
        val observedFailure = CompletableDeferred<Throwable>()
        val original = CancellationException("cancel during cleanup")
        var opens = 0
        val consumer = launch {
            try {
                consumeWithIdleRestart<String>(
                    idleTimeoutMillis = SHORT_IDLE_EXPIRY_MILLIS,
                    streamName = "test stream",
                    openStream = {
                        opens++
                        flow<String> { awaitCancellation() }.produceIn(this)
                    },
                    onStreamStopped = {
                        stopHookEntered.complete(Unit)
                        awaitCancellation()
                    },
                    streamStopTimeoutMillis = 200
                ) { }
            } catch (failure: Throwable) {
                observedFailure.complete(failure)
                throw failure
            }
        }
        withTimeout(TEST_TIMEOUT_MILLIS) { stopHookEntered.await() }
        consumer.cancel(original)

        val failure = withTimeout(TEST_TIMEOUT_MILLIS) { observedFailure.await() }
        withTimeout(TEST_TIMEOUT_MILLIS) { consumer.join() }

        assertCancellationAndCleanupFailure(original, failure)
        assertEquals(1, opens)
    }

    @Test
    fun aStopHookTimeoutPreservesTheOriginalExternalCancellation() = runBlocking {
        val opened = CompletableDeferred<Unit>()
        val observedFailure = CompletableDeferred<Throwable>()
        val original = CancellationException("external cancellation")
        var opens = 0
        val consumer = launch {
            try {
                consumeWithIdleRestart<String>(
                    idleTimeoutMillis = NO_IDLE_EXPIRY_MILLIS,
                    streamName = "test stream",
                    openStream = {
                        opens++
                        opened.complete(Unit)
                        flow<String> { awaitCancellation() }.produceIn(this)
                    },
                    onStreamStopped = { awaitCancellation() },
                    streamStopTimeoutMillis = SHORT_IDLE_EXPIRY_MILLIS
                ) { }
            } catch (failure: Throwable) {
                observedFailure.complete(failure)
                throw failure
            }
        }
        withTimeout(TEST_TIMEOUT_MILLIS) { opened.await() }

        consumer.cancel(original)
        val failure = withTimeout(TEST_TIMEOUT_MILLIS) { observedFailure.await() }
        withTimeout(TEST_TIMEOUT_MILLIS) { consumer.join() }

        assertCancellationAndCleanupFailure(original, failure)
        assertEquals(1, opens)
    }

    @Test
    fun aStalledStopHookHasABoundAndPreventsANewRegistration() {
        var opens = 0
        assertThrows(DashboardSessionTimeoutException::class.java) {
            runBlocking {
                withTimeout(TEST_TIMEOUT_MILLIS) {
                    consumeWithIdleRestart<String>(
                        idleTimeoutMillis = SHORT_IDLE_EXPIRY_MILLIS,
                        streamName = "test stream",
                        openStream = {
                            opens++
                            flow<String> { awaitCancellation() }.produceIn(this)
                        },
                        onStreamStopped = { awaitCancellation() },
                        streamStopTimeoutMillis = SHORT_IDLE_EXPIRY_MILLIS
                    ) { }
                }
            }
        }
        assertEquals(1, opens)
    }

    @Test
    fun replacementWaitsForStreamBoundWorkAndTheStopHook() = runBlocking {
        val opened = AtomicInteger()
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val secondOpened = CompletableDeferred<Unit>()
        val events = CopyOnWriteArrayList<String>()
        var generation = 0
        val consumer = launch {
            consumeWithIdleRestart<String>(
                idleTimeoutMillis = SHORT_IDLE_EXPIRY_MILLIS,
                streamName = "test stream",
                onStreamStarted = { streamScope ->
                    val current = ++generation
                    events += "start$current"
                    streamScope.launch {
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                cleanupStarted.complete(Unit)
                                releaseCleanup.await()
                                events += "taskClosed$current"
                            }
                        }
                    }
                },
                openStream = {
                    val current = opened.incrementAndGet()
                    events += "open$current"
                    if (current == 2) secondOpened.complete(Unit)
                    flow<String> { awaitCancellation() }.produceIn(this)
                },
                onStreamStopped = { events += "stop$generation" }
            ) { }
        }
        try {
            withTimeout(TEST_TIMEOUT_MILLIS) { cleanupStarted.await() }
            assertEquals("replacement raced uncompleted stream work", 1, opened.get())
            releaseCleanup.complete(Unit)
            withTimeout(TEST_TIMEOUT_MILLIS) { secondOpened.await() }
            assertEquals(listOf("start1", "open1", "taskClosed1", "stop1", "start2", "open2"), events.take(6))
        } finally {
            releaseCleanup.complete(Unit)
            consumer.cancelAndJoin()
        }
    }

    @Test
    fun anOuterDeadlineCancelsWithoutRestartingTheStream() = runBlocking {
        var opens = 0
        var stops = 0
        val result = withTimeoutOrNull(SHORT_IDLE_EXPIRY_MILLIS) {
            consumeWithIdleRestart<String>(
                idleTimeoutMillis = NO_IDLE_EXPIRY_MILLIS,
                streamName = "test stream",
                openStream = {
                    opens++
                    flow<String> { awaitCancellation() }.produceIn(this)
                },
                onStreamStopped = { stops++ }
            ) { }
        }
        assertNull(result)
        assertEquals(1, opens)
        assertEquals(1, stops)
    }

    @Test
    fun everyMessageReachesTheHandlerWhileTheStreamIsAlive() = runBlocking {
        val received = Channel<String>(Channel.UNLIMITED)
        val consumer = launch {
            consumeWithIdleRestart(
                idleTimeoutMillis = NO_IDLE_EXPIRY_MILLIS,
                streamName = "test stream",
                openStream = {
                    flow<String> {
                        emit("a")
                        emit("b")
                        emit("c")
                        awaitCancellation()
                    }.produceIn(this)
                }
            ) { received.send(it) }
        }

        val messages = withTimeout(TEST_TIMEOUT_MILLIS) { List(3) { received.receive() } }

        assertEquals(listOf("a", "b", "c"), messages)
        consumer.cancelAndJoin()
    }

    /**
     * An expired stream is fully finished before its replacement is opened.
     *
     * Overlapping them is what would turn this fix into a worse bug: the Dashboard keys the task
     * stream by client, so a cancel landing after the replacement registered would leave the agent
     * holding a stream nothing writes to, with no deadline left to notice. The strict
     * open/close/open ordering below is the guarantee that cannot regress.
     */
    @Test
    fun anIdleStreamIsFullyFinishedBeforeItsReplacementOpens() = runBlocking {
        val events = Channel<String>(Channel.UNLIMITED)
        val opened = AtomicInteger()
        val consumer = launch {
            consumeWithIdleRestart<String>(
                idleTimeoutMillis = SHORT_IDLE_EXPIRY_MILLIS,
                streamName = "test stream",
                openStream = {
                    val index = opened.incrementAndGet()
                    events.trySend("open$index")
                    flow<String> {
                        try {
                            awaitCancellation()
                        } finally {
                            events.trySend("close$index")
                        }
                    }.produceIn(this)
                }
            ) { }
        }

        val observed = withTimeout(TEST_TIMEOUT_MILLIS) { List(6) { events.receive() } }

        assertEquals(
            listOf("open1", "close1", "open2", "close2", "open3", "close3"),
            observed
        )
        consumer.cancelAndJoin()
    }

    /**
     * The whole point: an expiry reaches the stream and nothing else.
     *
     * The sibling stands in for the metrics heartbeat and the sessions for a live terminal, NAT
     * tunnel or file transfer. They share one non-supervising scope with the consumer, exactly as
     * they do in the runtime, so if an expiry ever escaped the stream again they would be cancelled
     * with it and this test would see it.
     */
    @Test
    fun anExpiryLeavesSiblingWorkAndAlreadyStartedSessionsRunning() = runBlocking {
        val opens = Channel<Int>(Channel.UNLIMITED)
        val opened = AtomicInteger()
        val sessions = CopyOnWriteArrayList<Job>()
        val sharedJob = Job()
        val sharedScope = CoroutineScope(coroutineContext + sharedJob)

        try {
            val heartbeat = sharedScope.launch { awaitCancellation() }
            val consumer = sharedScope.launch {
                consumeWithIdleRestart(
                    idleTimeoutMillis = SHORT_IDLE_EXPIRY_MILLIS,
                    streamName = "test stream",
                    openStream = {
                        val index = opened.incrementAndGet()
                        opens.trySend(index)
                        flow<String> {
                            emit("task-$index")
                            awaitCancellation()
                        }.produceIn(this)
                    }
                ) {
                    // Launched into the shared scope, never the stream's own — the runtime does the
                    // same so a session outlives the stream that requested it.
                    sessions += sharedScope.launch { awaitCancellation() }
                }
            }

            withTimeout(TEST_TIMEOUT_MILLIS) { repeat(3) { opens.receive() } }

            assertTrue("The heartbeat must survive an idle task stream.", heartbeat.isActive)
            assertTrue("The consumer must keep running after a restart.", consumer.isActive)
            assertTrue("At least one session must have started.", sessions.isNotEmpty())
            assertTrue(
                "Sessions started before the expiry must still be running after it.",
                sessions.all(Job::isActive)
            )
        } finally {
            sharedJob.cancelAndJoin()
        }
    }

    /**
     * Only idleness is recoverable in place. A broken stream must still reach the connection loop,
     * which is the only thing that can rebuild the channel underneath it.
     */
    @Test
    fun aFailureThatIsNotIdlenessPropagatesToTheCaller() {
        var opens = 0

        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                consumeWithIdleRestart<String>(
                    idleTimeoutMillis = NO_IDLE_EXPIRY_MILLIS,
                    streamName = "test stream",
                    openStream = {
                        opens++
                        flow<String> { error("stream broke") }.produceIn(this)
                    },
                    onStreamStopped = { error("cleanup broke") }
                ) { }
            }
        }

        assertEquals("stream broke", failure.message)
        assertEquals("cleanup broke", failure.suppressed.single().message)
        assertEquals("A broken stream must not be retried in place.", 1, opens)
    }

    private fun assertCancellationAndCleanupFailure(original: CancellationException, failure: Throwable) {
        assertTrue("terminal failure must remain cancellation", failure is CancellationException)
        // Coroutine stacktrace recovery can copy a CancellationException and retain its original
        // as cause. Check identity inside a bounded chain, rather than across a suspension boundary.
        val causes = mutableListOf<Throwable>()
        var current: Throwable? = failure
        repeat(16) {
            val item = current ?: return@repeat
            if (causes.any { it === item }) return@repeat
            causes += item
            current = item.cause
        }
        assertTrue("original cancellation must be retained", causes.any { it === original })
        assertTrue("cleanup failure must remain available", causes.any { cause ->
            cause.suppressed.any { it is DashboardSessionTimeoutException }
        })
    }

    private companion object {
        /** Long enough that no test hits the deadline by accident. */
        const val NO_IDLE_EXPIRY_MILLIS = 60_000L

        /** Short enough to observe several restarts, long enough to survive a slow CI machine. */
        const val SHORT_IDLE_EXPIRY_MILLIS = 30L

        const val TEST_TIMEOUT_MILLIS = 10_000L
    }
}
