package com.nezhahq.agent.service

import com.nezhahq.agent.SilentLoggerRule
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.core.task.TaskTypes
import com.nezhahq.agent.executor.TaskExecutor
import com.nezhahq.agent.mcp.McpSession
import com.nezhahq.agent.mcp.McpTaskBackend
import com.nezhahq.agent.mcp.transport.OwnedMcpResult
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import proto.Nezha.Host
import proto.Nezha.Receipt
import proto.Nezha.State
import proto.Nezha.Task
import proto.Nezha.TaskResult

/** Exercises the production component graph, including the real MCP registration and budgets. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RuntimeComponentIntegrationTest {
    @get:Rule val logger = SilentLoggerRule()

    @Test fun idleReplacementKeepsMetricsLegacySessionsAndWorkersButJoinsOldMcpAndReleasesItsBudgets() = runBlocking {
        val legacyStarted = CompletableDeferred<Unit>()
        val legacyStopped = CompletableDeferred<Unit>()
        val shortStarted = CompletableDeferred<Unit>()
        val shortStopped = CompletableDeferred<Unit>()
        val releaseShort = CompletableDeferred<Unit>()
        val mcpStarted = CompletableDeferred<Unit>()
        val mcpCleanup = CompletableDeferred<Unit>()
        val releaseMcpCleanup = CompletableDeferred<Unit>()
        val resultHeld = CompletableDeferred<Unit>()
        val secondOpened = CompletableDeferred<Unit>()
        val legacyResults = Channel<TaskResult>(Channel.UNLIMITED)
        var opens = 0
        val components = Components(this, idleMillis = 500,
            backend = McpTaskBackend { task, _, reserve ->
                if (task.id == 3L) owned(task, reserve) else {
                    mcpStarted.complete(Unit)
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) {
                            mcpCleanup.complete(Unit)
                            releaseMcpCleanup.await()
                        }
                    }
                }
            },
            shortTask = { task, _ ->
                if (task.id == 2L) {
                    shortStarted.complete(Unit)
                    try { releaseShort.await() } finally { shortStopped.complete(Unit) }
                }
                successful(task)
            },
            legacySession = {
                legacyStarted.complete(Unit)
                try { awaitCancellation() } finally { legacyStopped.complete(Unit) }
            },
            tasks = { outgoing -> channelFlow {
                val generation = ++opens
                val collector = launch {
                    outgoing.collect { result ->
                        if (generation == 1 && result.id == 3L) {
                            resultHeld.complete(Unit)
                            awaitCancellation()
                        }
                        legacyResults.send(result)
                    }
                }
                try {
                    if (generation == 1) {
                        send(task(1, TaskTypes.TERMINAL))
                        send(task(2, TaskTypes.KEEPALIVE))
                        send(task(3, TaskTypes.EXEC))
                        send(task(4, TaskTypes.EXEC))
                    } else if (generation == 2) {
                        secondOpened.complete(Unit)
                        send(task(5, TaskTypes.KEEPALIVE))
                    }
                    awaitCancellation()
                } finally { collector.cancel() }
            } }
        )
        val running = components.start(this)
        try {
            withTimeout(10_000) {
                legacyStarted.await(); shortStarted.await(); mcpStarted.await(); resultHeld.await()
            }
            val old = components.registrations.single()
            assertEquals(1024L, old.admission.retainedResultBytes)
            withTimeout(10_000) { mcpCleanup.await() }
            assertEquals("replacement must wait for the old MCP backend's cleanup", 1, opens)
            assertFalse(legacyStopped.isCompleted)
            assertFalse(shortStopped.isCompleted)
            assertEquals(1, components.registries.single().activeCount())
            val tickDuringCleanup = components.stateCount
            withTimeout(10_000) { while (components.stateCount <= tickDuringCleanup) components.ticks.receive() }
            assertEquals(1, components.stateStarts)

            releaseMcpCleanup.complete(Unit)
            withTimeout(10_000) { secondOpened.await() }
            assertEquals(0L, old.admission.retainedRequestBytes)
            assertEquals(0L, old.admission.retainedResultBytes)
            assertEquals(0, old.admission.activeExecTasks)
            assertFalse(legacyStopped.isCompleted)
            assertFalse(shortStopped.isCompleted)
            releaseShort.complete(Unit)
            val delivered = withTimeout(10_000) { List(2) { legacyResults.receive().id }.toSet() }
            assertEquals(setOf(2L, 5L), delivered)
            assertEquals(1, components.connection.connects)
            assertEquals(0, components.connection.disconnects)
        } finally {
            releaseMcpCleanup.complete(Unit)
            releaseShort.complete(Unit)
            withTimeout(10_000) { running.cancelAndJoin() }
        }
        assertTrue(legacyStopped.isCompleted)
        assertTrue(components.taskScopes.all(Job::isCompleted))
        assertTrue(components.registries.all { it.activeCount() == 0 })
        assertEquals(components.stateStarts, components.stateStops)
    }

    @Test fun realTaskStreamFailureCancelsTheWholeAttemptBeforeReconnect() = runBlocking {
        val started = Channel<Long>(Channel.UNLIMITED)
        val ended = mutableSetOf<Long>()
        val failStream = CompletableDeferred<Unit>()
        val reconnected = CompletableDeferred<Unit>()
        var opens = 0
        suspend fun hold(id: Long): Nothing {
            started.send(id)
            try { awaitCancellation() } finally { ended += id }
        }
        val components = Components(this,
            backend = McpTaskBackend { task, _, _ -> hold(task.id) },
            shortTask = { task, _ -> hold(task.id) },
            legacySession = { hold(it.task.id) },
            tasks = { outgoing -> channelFlow {
                val generation = ++opens
                val collector = launch { outgoing.collect {} }
                try {
                    if (generation == 1) {
                        send(task(1, TaskTypes.TERMINAL))
                        send(task(2, TaskTypes.KEEPALIVE))
                        send(task(3, TaskTypes.EXEC))
                        failStream.await()
                        throw IOException("synthetic task stream failure")
                    }
                    reconnected.complete(Unit)
                    awaitCancellation()
                } finally { collector.cancel() }
            } }
        )
        val running = components.start(this)
        try {
            assertEquals(setOf(1L, 2L, 3L), withTimeout(10_000) { List(3) { started.receive() }.toSet() })
            failStream.complete(Unit)
            withTimeout(10_000) { reconnected.await() }
            assertEquals(setOf(1L, 2L, 3L), ended)
            assertTrue(components.taskScopes.first().isCompleted)
            assertEquals(0, components.registries.first().activeCount())
            assertEquals(0L, components.registrations.first().admission.retainedRequestBytes)
            assertEquals(2, components.stateStarts)
            assertEquals(1, components.stateStops)
            assertEquals(2, components.connection.connects)
            assertEquals(1, components.connection.disconnects)
            assertEquals(listOf(true), components.connection.preservedStates)
        } finally { withTimeout(10_000) { running.cancelAndJoin() } }
    }

    @Test fun capabilityRefreshRevokesOnlyAffectedMcpWorkAndRejectsNewLegacySessionsWithoutReconnect() = runBlocking {
        val incoming = Channel<Task>(Channel.UNLIMITED)
        val results = Channel<TaskResult>(Channel.UNLIMITED)
        val started = Channel<Long>(Channel.UNLIMITED)
        val stopped = mutableSetOf<Long>()
        suspend fun hold(id: Long): Nothing {
            started.send(id)
            try { awaitCancellation() } finally { stopped += id }
        }
        val components = Components(this,
            backend = McpTaskBackend { task, _, _ -> hold(task.id) },
            legacySession = { hold(it.task.id) },
            tasks = { outgoing -> channelFlow {
                val collector = launch { outgoing.collect { results.send(it) } }
                try { for (task in incoming) send(task) } finally { collector.cancel() }
            } }
        )
        val running = components.start(this)
        try {
            incoming.send(task(1, TaskTypes.TERMINAL))
            incoming.send(task(2, TaskTypes.FILE_MANAGER))
            incoming.send(task(3, TaskTypes.EXEC))
            incoming.send(task(4, TaskTypes.FS_READ))
            assertEquals(setOf(1L, 2L, 3L, 4L), withTimeout(10_000) { List(4) { started.receive() }.toSet() })

            components.dispatcher.updateCapabilities(ENABLED.copy(shellEnabled = false))
            assertEquals(setOf(3L), stopped)
            incoming.send(task(5, TaskTypes.TERMINAL))
            val shellResults = withTimeout(10_000) { List(2) { results.receive() }.associateBy { it.id } }
            assertTrue(shellResults.getValue(3).successful)
            assertTrue(shellResults.getValue(3).data.contains("Remote shell execution is disabled"))
            assertFalse(shellResults.getValue(5).successful)
            assertEquals(2, components.registries.single().activeCount())

            components.dispatcher.updateCapabilities(RemoteCapabilities())
            assertEquals(setOf(3L, 4L), stopped)
            incoming.send(task(6, TaskTypes.FILE_MANAGER))
            val fileResults = withTimeout(10_000) { List(2) { results.receive() }.associateBy { it.id } }
            assertTrue(fileResults.getValue(4).successful)
            assertTrue(fileResults.getValue(4).data.contains("Remote file manager is disabled"))
            assertFalse(fileResults.getValue(6).successful)
            assertTrue(started.tryReceive().isFailure)
            assertEquals(1, components.registrations.size)
            assertEquals(1, components.connection.connects)
            assertEquals(0, components.connection.disconnects)
            components.dispatcher.updateCapabilities(ENABLED)
            assertEquals("reenabling must not revive a revoked MCP task", setOf(3L, 4L), stopped)
            assertTrue(started.tryReceive().isFailure)
        } finally { withTimeout(10_000) { running.cancelAndJoin() } }
        assertEquals(setOf(1L, 2L, 3L, 4L), stopped)
        assertEquals(0, components.registries.single().activeCount())
    }

    @Test fun queuedShortTaskReadsTheGrantAtExecutionAfterCapabilityRefresh() = runBlocking {
        val blockersStarted = Channel<Unit>(Channel.UNLIMITED)
        val releaseWorkers = CompletableDeferred<Unit>()
        val commandQueued = CompletableDeferred<Unit>()
        val results = Channel<TaskResult>(Channel.UNLIMITED)
        val executionGrants = mutableListOf<RemoteCapabilities>()
        val components = Components(this,
            shortTask = { task, grant ->
                if (task.type == TaskTypes.KEEPALIVE) {
                    blockersStarted.send(Unit)
                    releaseWorkers.await()
                } else executionGrants += grant
                TaskExecutor.executeTask(task, grant)
            },
            tasks = { outgoing -> channelFlow {
                val collector = launch { outgoing.collect { results.send(it) } }
                try {
                    repeat(8) { send(task(it + 1L, TaskTypes.KEEPALIVE)) }
                    send(task(9, TaskTypes.COMMAND))
                    // A following task cannot be received until routing command 9 has completed.
                    send(task(10, TaskTypes.KEEPALIVE))
                    commandQueued.complete(Unit)
                    awaitCancellation()
                } finally { collector.cancel() }
            } }
        )
        val running = components.start(this)
        try {
            withTimeout(10_000) { repeat(8) { blockersStarted.receive() }; commandQueued.await() }
            components.dispatcher.updateCapabilities(RemoteCapabilities())
            releaseWorkers.complete(Unit)
            val command = withTimeout(10_000) {
                var result = results.receive()
                while (result.id != 9L) result = results.receive()
                result
            }
            assertFalse(command.successful)
            assertEquals(listOf(RemoteCapabilities()), executionGrants)
            assertEquals(1, components.connection.connects)
            assertEquals(0, components.connection.disconnects)
        } finally {
            releaseWorkers.complete(Unit)
            withTimeout(10_000) { running.cancelAndJoin() }
        }
    }

    private class Components(
        runtimeScope: CoroutineScope,
        idleMillis: Long = 60_000,
        backend: McpTaskBackend = McpTaskBackend { task, _, reserve -> owned(task, reserve) },
        shortTask: suspend (Task, RemoteCapabilities) -> TaskResult = { task, _ -> successful(task) },
        legacySession: suspend (StreamTaskRequest) -> Unit = { awaitCancellation() },
        tasks: (Flow<TaskResult>) -> Flow<Task>
    ) {
        val connection = RuntimeTestConnection()
        val registrations = mutableListOf<McpSession>()
        val registries = mutableListOf<StreamSessionRegistry>()
        val taskScopes = mutableListOf<Job>()
        val ticks = Channel<Unit>(Channel.UNLIMITED)
        var stateStarts = 0
        var stateStops = 0
        var stateCount = 0
        val reporter = MetricReporter(runtimeScope,
            collectHost = { Host.getDefaultInstance() },
            collectState = { stateCount++; ticks.send(Unit); State.getDefaultInstance() },
            fetchGeoIp = { null }, currentGeoIpReporter = { null }, stateReportIntervalMillis = 10)
        val dispatcher = TaskDispatcher(ENABLED, { scope, capabilities ->
            McpSession(scope, capabilities, backend).also { registrations += it }
        }, shortTask, idleMillis)
        private val supervisor = ConnectionSupervisor(connection, { _, _ -> },
            reportInitial = { reporter.reportInitial({}, {}) },
            reportState = { _, onReceipt ->
                reporter.reportState({ states -> flow {
                    stateStarts++
                    try { states.collect { emit(Receipt.getDefaultInstance()) } } finally { stateStops++ }
                } }, onReceipt)
            },
            dispatchTasks = {
                dispatcher.run(tasks) { scope, results ->
                    taskScopes += checkNotNull(scope.coroutineContext[Job])
                    val registry = StreamSessionRegistry(36, mapOf(
                        TaskTypes.TERMINAL to 2, TaskTypes.FILE_MANAGER to 2, TaskTypes.NAT to 32
                    )).also { registries += it }
                    StreamSessionManager(scope, results, legacySession,
                        parseRequest = { task -> when (task.type) {
                            TaskTypes.TERMINAL -> StreamTaskRequest.Terminal(task, task.data)
                            TaskTypes.NAT -> StreamTaskRequest.Nat(task, task.data, "localhost:8080")
                            TaskTypes.FILE_MANAGER -> StreamTaskRequest.FileManager(task, task.data)
                            else -> error("Unexpected stream type")
                        } }, registry = registry)
                }
            },
            reconnectBackoff = ReconnectBackoffPolicy(jitter = BackoffJitter { 0 })
        )

        fun start(scope: CoroutineScope): Job {
            connection.connect()
            return scope.launch { supervisor.run() }
        }
    }

    private companion object {
        val ENABLED = RemoteCapabilities(shellEnabled = true, fileManagerEnabled = true, natEnabled = true)
        fun task(id: Long, type: Long): Task = Task.newBuilder().setId(id).setType(type).setData("stream-$id").build()
        fun successful(task: Task): TaskResult = TaskResult.newBuilder().setId(task.id).setType(task.type).setSuccessful(true).build()
        suspend fun owned(task: Task, reserve: suspend (Long) -> AutoCloseable) = OwnedMcpResult(successful(task), reserve(1024))
    }
}
