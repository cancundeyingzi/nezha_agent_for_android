package com.nezhahq.agent.service

import com.nezhahq.agent.SilentLoggerRule
import com.nezhahq.agent.core.task.TaskTypes
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import proto.Nezha.Task
import proto.Nezha.TaskResult

class StreamSessionManagerTest {
    @get:Rule val logger = SilentLoggerRule()

    @Test fun cancellationBeforeTheSessionBodyStartsReleasesItsLeaseEvenWithAnAlreadyCancelledParent() = runBlocking {
        for (cancelBeforeAdmission in listOf(false, true)) {
            val dispatcher = PausedDispatcher()
            val parent = Job()
            val registry = registry()
            val results = Channel<TaskResult>(Channel.UNLIMITED)
            var executions = 0
            val manager = StreamSessionManager(CoroutineScope(coroutineContext + parent + dispatcher), results,
                runSession = { executions++; awaitCancellation() }, parseRequest = ::parse, registry = registry)
            try {
                if (cancelBeforeAdmission) parent.cancel()
                manager.launch(task(1, TaskTypes.TERMINAL, "one"))
                parent.cancel()
                dispatcher.drain()
                withTimeout(5_000) { parent.join() }
                assertEquals(0, executions)
                assertEquals(0, registry.activeCount())
                assertTrue(results.tryReceive().isFailure)
            } finally {
                parent.cancel()
                dispatcher.drain()
                withTimeout(5_000) { parent.join() }
            }
        }
    }

    @Test fun duplicateAndTypeAndTotalLimitsRejectWithoutStartingWorkAndCompletionAllowsReadmission() = runBlocking {
        val parent = Job(coroutineContext[Job])
        val registry = registry()
        val results = Channel<TaskResult>(Channel.UNLIMITED)
        val started = Channel<Long>(Channel.UNLIMITED)
        val completeTerminal = CompletableDeferred<Unit>()
        val manager = StreamSessionManager(CoroutineScope(coroutineContext + parent), results,
            runSession = { request ->
                started.send(request.task.id)
                if (request.task.id == 1L) completeTerminal.await() else awaitCancellation()
            }, parseRequest = ::parse, registry = registry)
        try {
            manager.launch(task(1, TaskTypes.TERMINAL, "one"))
            assertEquals(1L, withTimeout(5_000) { started.receive() })
            manager.launch(task(2, TaskTypes.NAT, "one"))
            manager.launch(task(3, TaskTypes.TERMINAL, "two"))
            val rejected = List(2) { results.receive() }
            assertEquals(listOf(2L, 3L), rejected.map { it.id })
            assertTrue(rejected.all { !it.successful })
            assertTrue(rejected[0].data.contains("already active"))
            assertTrue(rejected[1].data.contains("for task type"))
            assertTrue(started.tryReceive().isFailure)

            manager.launch(task(4, TaskTypes.NAT, "nat"))
            assertEquals(4L, withTimeout(5_000) { started.receive() })
            manager.launch(task(5, TaskTypes.FILE_MANAGER, "files"))
            assertTrue(results.receive().data.contains("2 active stream sessions"))
            assertEquals(2, registry.activeCount())
            completeTerminal.complete(Unit)
            awaitCount(registry, 1)
            manager.launch(task(6, TaskTypes.TERMINAL, "one"))
            assertEquals(6L, withTimeout(5_000) { started.receive() })
            assertEquals(2, registry.activeCount())
        } finally {
            completeTerminal.complete(Unit)
            withTimeout(5_000) { parent.cancelAndJoin() }
        }
        assertEquals(0, registry.activeCount())
    }

    @Test fun sessionExceptionReleasesTheSlotAndInvalidInputNeverAcquiresOne() = runBlocking {
        val registry = registry()
        val results = Channel<TaskResult>(Channel.UNLIMITED)
        var executions = 0
        val manager = StreamSessionManager(this, results,
            runSession = { executions++; throw IllegalStateException("synthetic failure") },
            parseRequest = { if (it.data == "invalid") error("synthetic parse rejection") else parse(it) },
            registry = registry)
        manager.launch(task(1, TaskTypes.TERMINAL, "one"))
        awaitCount(registry, 0)
        manager.launch(task(2, TaskTypes.TERMINAL, "one"))
        awaitCount(registry, 0)
        assertEquals(2, executions)
        manager.launch(task(3, TaskTypes.TERMINAL, "invalid"))
        val failure = results.receive()
        assertEquals(3L, failure.id)
        assertFalse(failure.successful)
        assertTrue(failure.data.startsWith("Invalid stream task:"))
        assertEquals(2, executions)
        assertEquals(0, registry.activeCount())
        assertTrue(logger.messages.any { it.contains("TaskID=1") })
    }

    private suspend fun awaitCount(registry: StreamSessionRegistry, count: Int) = withTimeout(5_000) {
        while (registry.activeCount() != count) yield()
    }

    private fun registry() = StreamSessionRegistry(2, mapOf(
        TaskTypes.TERMINAL to 1, TaskTypes.NAT to 2, TaskTypes.FILE_MANAGER to 1
    ))

    private fun task(id: Long, type: Long, stream: String): Task =
        Task.newBuilder().setId(id).setType(type).setData(stream).build()

    private fun parse(task: Task): StreamTaskRequest = when (task.type) {
        TaskTypes.TERMINAL -> StreamTaskRequest.Terminal(task, task.data)
        TaskTypes.NAT -> StreamTaskRequest.Nat(task, task.data, "localhost:8080")
        TaskTypes.FILE_MANAGER -> StreamTaskRequest.FileManager(task, task.data)
        else -> error("Unexpected stream type")
    }

    private class PausedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }
}
