package com.nezhahq.agent.service

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract that keeps an idle task stream from taking a whole connection with it.
 */
class IdleRestartingStreamTest {

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
                    }
                ) { }
            }
        }

        assertEquals("stream broke", failure.message)
        assertEquals("A broken stream must not be retried in place.", 1, opens)
    }

    private companion object {
        /** Long enough that no test hits the deadline by accident. */
        const val NO_IDLE_EXPIRY_MILLIS = 60_000L

        /** Short enough to observe several restarts, long enough to survive a slow CI machine. */
        const val SHORT_IDLE_EXPIRY_MILLIS = 30L

        const val TEST_TIMEOUT_MILLIS = 10_000L
    }
}
