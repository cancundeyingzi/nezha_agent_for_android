package com.nezhahq.agent.mcp

import com.google.gson.JsonParser
import com.nezhahq.agent.core.mcp.ExecResult
import com.nezhahq.agent.core.mcp.FsReadResult
import com.nezhahq.agent.core.mcp.FsListResult
import com.nezhahq.agent.core.mcp.McpLimits
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.mcp.exec.McpExecBackend
import com.nezhahq.agent.mcp.transport.HelperBackend
import com.nezhahq.agent.mcp.transport.HelperLaunchSpec
import com.nezhahq.agent.mcp.transport.HelperLauncher
import com.nezhahq.agent.mcp.transport.HelperProcessLease
import com.nezhahq.agent.mcp.transport.OwnedMcpResult
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import proto.Nezha.Task
import proto.Nezha.TaskResult

class McpSessionTest {
    private val encoder = McpResultEncoder()
    private val enabled = RemoteCapabilities(shellEnabled = true, fileManagerEnabled = true)

    @Test
    fun twoExecAndTwoFileTasksAreAdmittedImmediatelyAndFifthIsBusyWithoutBlockingLegacyResults() = runBlocking {
        val starts = Channel<Task>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val session = McpSession(this, { enabled }, McpTaskBackend { task, _, reserve ->
            starts.send(task)
            release.await()
            result(task, reserve)
        })
        val legacy = Channel<TaskResult>(1)
        val received = Channel<TaskResult>(Channel.UNLIMITED)
        val collector = launch { session.outgoingWith(legacy).collect { received.send(it) } }
        val requests = listOf(task(1, 15, "中😀"), task(2, 15), task(3, 16), task(4, 17))
        try {
            requests.forEach { session.handle(it) }
            withTimeout(5_000) { repeat(4) { starts.receive() } }
            assertEquals(2, session.admission.activeExecTasks)
            assertEquals(2, session.admission.activeFileTasks)
            assertEquals(requests.sumOf { it.data.toByteArray(Charsets.UTF_8).size.toLong() },
                session.admission.retainedRequestBytes)
            session.handle(task(5, 17))
            val busy = withTimeout(5_000) { received.receive() }
            assertEquals(5L, busy.id)
            assertTrue(busy.successful)
            assertTrue(json(busy).get("error").asString.contains("busy"))
            assertTrue(starts.tryReceive().isFailure)
            legacy.send(TaskResult.newBuilder().setId(7).setType(7).build())
            assertEquals(7L, withTimeout(5_000) { received.receive() }.id)
            release.complete(Unit)
            withTimeout(5_000) { repeat(4) { received.receive() } }
            awaitCondition { session.admission.retainedRequestBytes == 0L && session.admission.retainedResultBytes == 0L }
        } finally {
            release.complete(Unit)
            session.close(); session.join(); collector.cancelAndJoin()
        }
    }

    @Test
    fun resultBudgetIsHeldThroughTheFinalCollectorEvenAfterProducerAndSessionCancellation() = runBlocking {
        val session = McpSession(this, { enabled }, McpTaskBackend { task, _, reserve ->
            result(task, reserve, McpLimits.MAX_TASK_RESULT_BYTES.toLong())
        })
        val enteredCollector = CompletableDeferred<Unit>()
        val collector = launch {
            session.outgoingWith(Channel()).collect {
                enteredCollector.complete(Unit)
                awaitCancellation()
            }
        }
        try {
            session.handle(task(1, 15))
            withTimeout(5_000) { enteredCollector.await() }
            assertEquals(McpLimits.MAX_TASK_RESULT_BYTES.toLong(), session.admission.retainedResultBytes)
            session.close()
            session.join()
            assertEquals("Collector still owns this result", McpLimits.MAX_TASK_RESULT_BYTES.toLong(),
                session.admission.retainedResultBytes)
            collector.cancelAndJoin()
            assertEquals(0L, session.admission.retainedResultBytes)
            assertEquals(0L, session.admission.retainedRequestBytes)
        } finally { session.close(); session.join(); collector.cancelAndJoin() }
    }

    @Test
    fun cancellationDiscardsFourQueuedResultsAndTheFifthSuspendedSendWithoutLeakingEitherBudget() = runBlocking {
        val produced = Channel<Long>(Channel.UNLIMITED)
        val session = McpSession(this, { enabled }, McpTaskBackend { task, _, reserve ->
            result(task, reserve).also { produced.send(task.id) }
        })
        try {
            repeat(4) { index ->
                session.handle(task(index + 1L, 15))
                withTimeout(5_000) { produced.receive() }
                awaitCondition { session.admission.activeExecTasks == 0 }
            }
            assertEquals(4 * 1024L, session.admission.retainedResultBytes)
            session.handle(task(5, 15))
            withTimeout(5_000) { produced.receive() }
            assertEquals(1, session.admission.activeExecTasks)
            assertEquals(5 * 1024L, session.admission.retainedResultBytes)
            session.close(); session.join()
            assertEquals(0L, session.admission.retainedRequestBytes)
            assertEquals(0L, session.admission.retainedResultBytes)
        } finally { session.close(); session.join() }
    }

    @Test
    fun revocationBeforeTheLazyCoordinatorStartsNeverCallsTheBackendAndReenablingDoesNotReviveIt() = runBlocking {
        val dispatcher = PausedDispatcher()
        val caps = AtomicReference(enabled)
        val launches = AtomicInteger()
        val session = McpSession(CoroutineScope(coroutineContext + dispatcher), caps::get,
            McpTaskBackend { task, _, reserve -> launches.incrementAndGet(); result(task, reserve) })
        val received = Channel<TaskResult>(Channel.UNLIMITED)
        val collector = launch { session.outgoingWith(Channel()).collect { received.send(it) } }
        try {
            session.handle(task(1, 15))
            caps.set(enabled.copy(shellEnabled = false))
            session.revokeDisabled(caps.get())
            caps.set(enabled)
            dispatcher.drain()
            val denied = withTimeout(5_000) { received.receive() }
            assertTrue(denied.successful)
            assertTrue(json(denied).get("error").asString.contains("disabled"))
            assertEquals(0, launches.get())
            assertEquals(0L, session.admission.retainedRequestBytes)
        } finally { session.close(); dispatcher.drain(); session.join(); collector.cancelAndJoin() }
    }

    @Test
    fun backendStartChecksLiveCapabilitiesEvenAfterTheChildWasRegistered() = runBlocking {
        val dispatcher = PausedDispatcher()
        val caps = AtomicReference(enabled)
        val launches = AtomicInteger()
        val session = McpSession(CoroutineScope(coroutineContext + dispatcher), caps::get,
            McpTaskBackend { task, _, reserve -> launches.incrementAndGet(); result(task, reserve) })
        val received = Channel<TaskResult>(Channel.UNLIMITED)
        val collector = launch { session.outgoingWith(Channel()).collect { received.send(it) } }
        try {
            session.handle(task(1, 15))
            dispatcher.runOne() // Coordinator has registered a LAZY child; its body has not run.
            caps.set(enabled.copy(shellEnabled = false))
            dispatcher.drain()
            val denied = withTimeout(5_000) { received.receive() }
            assertTrue(denied.successful)
            assertTrue(json(denied).get("error").asString.contains("disabled"))
            assertEquals(0, launches.get())
        } finally { session.close(); dispatcher.drain(); session.join(); collector.cancelAndJoin() }
    }

    @Test
    fun cancellationBeforeCoordinatorRunsReleasesTheRequestLeaseThroughTheCompletionHook() = runBlocking {
        val dispatcher = PausedDispatcher()
        val session = McpSession(CoroutineScope(coroutineContext + dispatcher), { enabled },
            McpTaskBackend { _, _, _ -> error("cancelled task must never start") })
        session.handle(task(1, 15, "中😀"))
        assertEquals("中😀".toByteArray(Charsets.UTF_8).size.toLong(), session.admission.retainedRequestBytes)
        session.close()
        dispatcher.drain()
        session.join()
        assertEquals(0L, session.admission.retainedRequestBytes)
        assertEquals(0, session.admission.activeExecTasks)
        assertEquals(0L, session.admission.retainedResultBytes)
    }

    @Test
    fun runningRevocationJoinsOnlyTheDisabledBackendAndReturnsOneDenialAfterCleanup() = runBlocking {
        val caps = AtomicReference(enabled)
        val starts = Channel<Long>(Channel.UNLIMITED)
        val cleanupEntered = CompletableDeferred<Unit>()
        val cleanupAllowed = CompletableDeferred<Unit>()
        val fileAllowed = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val session = McpSession(this, caps::get, McpTaskBackend { task, _, reserve ->
            calls.incrementAndGet()
            starts.send(task.id)
            if (task.type == 15L) {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleanupEntered.complete(Unit); cleanupAllowed.await() } }
            } else { fileAllowed.await(); result(task, reserve) }
        })
        val received = Channel<TaskResult>(Channel.UNLIMITED)
        val collector = launch { session.outgoingWith(Channel()).collect { received.send(it) } }
        var revocation: kotlinx.coroutines.Job? = null
        try {
            session.handle(task(1, 15)); session.handle(task(2, 17))
            withTimeout(5_000) { repeat(2) { starts.receive() } }
            caps.set(enabled.copy(shellEnabled = false))
            revocation = launch { session.revokeDisabled(caps.get()) }
            withTimeout(5_000) { cleanupEntered.await() }
            assertFalse(revocation.isCompleted)
            assertTrue(received.tryReceive().isFailure)
            assertEquals(1, session.admission.activeFileTasks)
            caps.set(enabled)
            cleanupAllowed.complete(Unit)
            withTimeout(5_000) { revocation.join() }
            val denied = withTimeout(5_000) { received.receive() }
            assertEquals(1L, denied.id)
            assertTrue(denied.successful)
            assertTrue(json(denied).get("error").asString.contains("disabled"))
            fileAllowed.complete(Unit)
            assertEquals(2L, withTimeout(5_000) { received.receive() }.id)
            awaitCondition { session.admission.retainedResultBytes == 0L && session.admission.retainedRequestBytes == 0L }
            assertTrue(received.tryReceive().isFailure)
            assertEquals(2, calls.get())
        } finally {
            cleanupAllowed.complete(Unit); fileAllowed.complete(Unit)
            session.close(); session.join(); revocation?.join(); collector.cancelAndJoin()
        }
    }

    @Test
    fun oldSessionLateResultCannotReachTheNewSessionAndStillReleasesItsOwnership() = runBlocking {
        val resultCreated = CompletableDeferred<Unit>()
        val releaseLate = CompletableDeferred<Unit>()
        val old = McpSession(this, { enabled }, McpTaskBackend { task, _, reserve ->
            val owned = result(task, reserve)
            try {
                withContext(NonCancellable) { resultCreated.complete(Unit); releaseLate.await() }
            } catch (_: CancellationException) {
                // Deliberately return late from this fake backend to exercise the holder fallback.
            }
            owned
        })
        val replacement = McpSession(this, { enabled }, McpTaskBackend { task, _, reserve -> result(task, reserve) })
        val received = Channel<TaskResult>(Channel.UNLIMITED)
        val collector = launch { replacement.outgoingWith(Channel()).collect { received.send(it) } }
        try {
            old.handle(task(1, 15))
            withTimeout(5_000) { resultCreated.await() }
            old.close()
            replacement.handle(task(2, 15))
            assertEquals(2L, withTimeout(5_000) { received.receive() }.id)
            releaseLate.complete(Unit)
            withTimeout(5_000) { old.join() }
            assertTrue(received.tryReceive().isFailure)
            assertEquals(0L, old.admission.retainedRequestBytes)
            assertEquals(0L, old.admission.retainedResultBytes)
        } finally {
            releaseLate.complete(Unit); old.close(); replacement.close()
            old.join(); replacement.join(); collector.cancelAndJoin()
        }
    }

    @Test
    fun oldSessionBudgetWaitersAndQueuedResultsAreDroppedBeforeReplacementResults() = runBlocking {
        val starts = Channel<Long>(Channel.UNLIMITED)
        val old = McpSession(this, { enabled }, McpTaskBackend { task, _, reserve ->
            starts.send(task.id)
            result(task, reserve, McpLimits.MAX_TASK_RESULT_BYTES.toLong())
        })
        val replacement = McpSession(this, { enabled }, McpTaskBackend { task, _, reserve -> result(task, reserve) })
        val received = Channel<TaskResult>(Channel.UNLIMITED)
        val collector = launch { replacement.outgoingWith(Channel()).collect { received.send(it) } }
        try {
            repeat(2) { index ->
                old.handle(task(index + 1L, 15))
                withTimeout(5_000) { starts.receive() }
                awaitCondition { old.admission.activeExecTasks == 0 }
            }
            old.handle(task(3, 15)); old.handle(task(4, 17))
            withTimeout(5_000) { repeat(2) { starts.receive() } }
            assertEquals(McpLimits.RESULT_BUDGET_BYTES, old.admission.retainedResultBytes)
            old.close(); withTimeout(5_000) { old.join() }
            assertEquals(0L, old.admission.retainedRequestBytes)
            assertEquals(0L, old.admission.retainedResultBytes)
            replacement.handle(task(5, 17))
            assertEquals(5L, withTimeout(5_000) { received.receive() }.id)
            assertTrue(received.tryReceive().isFailure)
        } finally { old.close(); replacement.close(); old.join(); replacement.join(); collector.cancelAndJoin() }
    }

    @Test
    fun revocationDoesNotWaitForTheFullResultBudgetAndKeepsAlreadyPublishedNormalResults() = runBlocking {
        val caps = AtomicReference(enabled)
        val starts = Channel<Long>(Channel.UNLIMITED)
        val session = McpSession(this, caps::get, McpTaskBackend { task, _, reserve ->
            starts.send(task.id)
            result(task, reserve, McpLimits.MAX_TASK_RESULT_BYTES.toLong())
        })
        val received = Channel<TaskResult>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.LAZY) {
            session.outgoingWith(Channel()).collect { received.send(it) }
        }
        try {
            repeat(2) { index ->
                session.handle(task(index + 1L, 15))
                withTimeout(5_000) { starts.receive() }
                awaitCondition { session.admission.activeExecTasks == 0 }
            }
            session.handle(task(3, 15))
            withTimeout(5_000) { starts.receive() }
            assertEquals(McpLimits.RESULT_BUDGET_BYTES, session.admission.retainedResultBytes)
            caps.set(enabled.copy(shellEnabled = false))
            withTimeout(5_000) { session.revokeDisabled(caps.get()) }
            assertEquals("Denial is still waiting for result space", 1, session.admission.activeExecTasks)
            collector.start()
            val results = withTimeout(5_000) { List(3) { received.receive() } }.associateBy { it.id }
            assertFalse(json(results.getValue(1)).has("error"))
            assertFalse(json(results.getValue(2)).has("error"))
            assertTrue(results.getValue(3).successful)
            assertTrue(json(results.getValue(3)).get("error").asString.contains("disabled"))
            awaitCondition { session.admission.retainedRequestBytes == 0L && session.admission.retainedResultBytes == 0L }
            assertTrue(received.tryReceive().isFailure)
        } finally { session.close(); session.join(); collector.cancelAndJoin() }
    }

    @Test
    fun fileRevocationCancelsBothFileFamiliesWhileExecKeepsRunning() = runBlocking {
        val caps = AtomicReference(enabled)
        val starts = Channel<Long>(Channel.UNLIMITED)
        val cleaning = Channel<Long>(Channel.UNLIMITED)
        val finishCleanup = CompletableDeferred<Unit>()
        val finishExec = CompletableDeferred<Unit>()
        val session = McpSession(this, caps::get, McpTaskBackend { task, _, reserve ->
            starts.send(task.id)
            if (task.type == 15L) {
                finishExec.await()
                result(task, reserve)
            } else {
                try { awaitCancellation() }
                finally { withContext(NonCancellable) { cleaning.send(task.id); finishCleanup.await() } }
            }
        })
        val received = Channel<TaskResult>(Channel.UNLIMITED)
        val collector = launch { session.outgoingWith(Channel()).collect { received.send(it) } }
        var revocation: kotlinx.coroutines.Job? = null
        try {
            session.handle(task(1, 16)); session.handle(task(2, 17)); session.handle(task(3, 15))
            withTimeout(5_000) { repeat(3) { starts.receive() } }
            caps.set(enabled.copy(fileManagerEnabled = false))
            revocation = launch { session.revokeDisabled(caps.get()) }
            withTimeout(5_000) { repeat(2) { cleaning.receive() } }
            assertFalse(revocation.isCompleted)
            assertEquals(1, session.admission.activeExecTasks)
            finishCleanup.complete(Unit)
            withTimeout(5_000) { revocation.join() }
            val denials = withTimeout(5_000) { List(2) { received.receive() } }
            assertEquals(setOf(16L, 17L), denials.map { it.type }.toSet())
            denials.forEach { assertTrue(it.successful); assertTrue(json(it).get("error").asString.contains("disabled")) }
            finishExec.complete(Unit)
            assertEquals(3L, withTimeout(5_000) { received.receive() }.id)
            awaitCondition { session.admission.retainedRequestBytes == 0L && session.admission.retainedResultBytes == 0L }
        } finally {
            finishCleanup.complete(Unit); finishExec.complete(Unit)
            session.close(); session.join(); revocation?.join(); collector.cancelAndJoin()
        }
    }

    @Test
    fun denialAndBusyResponsesReserveSmallWireBudgetBeforeTheyCanBeCollected() = runBlocking {
        val caps = AtomicReference(RemoteCapabilities())
        val starts = Channel<Long>(Channel.UNLIMITED)
        val session = McpSession(this, caps::get, McpTaskBackend { task, _, _ ->
            starts.send(task.id)
            awaitCancellation()
        })
        try {
            session.handle(task(1, 15))
            assertEquals(1024L, session.admission.retainedResultBytes)
            caps.set(enabled)
            session.handle(task(2, 15)); session.handle(task(3, 15))
            withTimeout(5_000) { repeat(2) { starts.receive() } }
            session.handle(task(4, 15))
            assertEquals(2048L, session.admission.retainedResultBytes)
            assertEquals(2, session.admission.activeExecTasks)
        } finally { session.close(); session.join() }
        assertEquals(0L, session.admission.retainedRequestBytes)
        assertEquals(0L, session.admission.retainedResultBytes)
    }

    @Test
    fun parseInternalUnsupportedAndBusinessOutcomesKeepTheirWireSemanticsAndAreAllCharged() = runBlocking {
        val launcher = object : HelperLauncher {
            override val backend = HelperBackend.APPLICATION
            override suspend fun launch(spec: HelperLaunchSpec): HelperProcessLease = error("must not launch")
        }
        val exec = McpExecBackend(launcher)
        val session = McpSession(this, { enabled }, McpTaskBackend { task, snapshot, reserve ->
            when (task.id) {
                1L -> exec.execute(task, snapshot, reserveResult = reserve)
                2L -> error("private exception")
                else -> result(task, reserve)
            }
        })
        val legacy = Channel<TaskResult>()
        val received = Channel<TaskResult>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.LAZY) { session.outgoingWith(legacy).collect { received.send(it) } }
        try {
            session.handle(task(1, 15, "{broken"))
            awaitCondition { session.admission.activeExecTasks == 0 }
            assertEquals(1024L, session.admission.retainedResultBytes)
            session.handle(task(2, 15))
            awaitCondition { session.admission.activeExecTasks == 0 }
            assertEquals(2048L, session.admission.retainedResultBytes)
            session.handle(task(3, 18))
            assertEquals(3072L, session.admission.retainedResultBytes)
            session.handle(task(4, 15))
            awaitCondition { session.admission.activeExecTasks == 0 }
            assertEquals(4096L, session.admission.retainedResultBytes)
            collector.start()
            val parse = withTimeout(5_000) { received.receive() }
            assertFalse(parse.successful)
            assertTrue(parse.data.startsWith("invalid MCP request"))
            val internal = withTimeout(5_000) { received.receive() }
            assertFalse(internal.successful)
            assertEquals(McpResultEncoder.INTERNAL_ENCODING_FAILURE, internal.data)
            assertFalse(withTimeout(5_000) { received.receive() }.successful)
            val business = withTimeout(5_000) { received.receive() }
            assertTrue(business.successful)
            assertEquals(7, json(business).get("exit_code").asInt)
            assertTrue(json(business).get("timed_out").asBoolean)
            assertFalse(json(business).has("error"))
            awaitCondition { session.admission.retainedResultBytes == 0L && session.admission.retainedRequestBytes == 0L }
        } finally { session.close(); session.join(); collector.cancelAndJoin() }
    }

    @Test
    fun disabledCapabilityIsCheckedBeforeOversizeUtf8AndOversizeGrantedRequestNeverStarts() = runBlocking {
        val caps = AtomicReference(RemoteCapabilities())
        val calls = AtomicInteger()
        val session = McpSession(this, caps::get, McpTaskBackend { task, _, reserve ->
            calls.incrementAndGet(); result(task, reserve)
        })
        val received = Channel<TaskResult>(Channel.UNLIMITED)
        val collector = launch { session.outgoingWith(Channel()).collect { received.send(it) } }
        try {
            val oversized = task(1, 15, "中".repeat((McpLimits.MAX_REQUEST_BYTES / 3 + 1).toInt()))
            session.handle(oversized)
            assertTrue(withTimeout(5_000) { received.receive() }.successful)
            caps.set(enabled)
            session.handle(oversized.toBuilder().setId(2).build())
            val tooLarge = withTimeout(5_000) { received.receive() }
            assertFalse(tooLarge.successful)
            assertTrue(tooLarge.data.contains("byte limit"))
            assertEquals(0, calls.get())
            assertEquals(0L, session.admission.retainedRequestBytes)
        } finally { session.close(); session.join(); collector.cancelAndJoin() }
    }

    @Test
    fun closedLegacyBranchDoesNotStarveMcpAndClosedMcpBranchStillPassesLegacyResults() = runBlocking {
        val session = McpSession(this, { enabled }, McpTaskBackend { task, _, reserve -> result(task, reserve) })
        val closedLegacy = Channel<TaskResult>().also { it.close() }
        val received = Channel<TaskResult>(Channel.UNLIMITED)
        val collector = launch { session.outgoingWith(closedLegacy).collect { received.send(it) } }
        try {
            session.handle(task(1, 15))
            assertEquals(1L, withTimeout(5_000) { received.receive() }.id)
            session.close(); session.join()
            withTimeout(5_000) { collector.join() }
            val legacy = Channel<TaskResult>(1)
            legacy.send(TaskResult.newBuilder().setId(7).build()); legacy.close()
            session.outgoingWith(legacy).collect { received.send(it) }
            assertEquals(7L, received.receive().id)
        } finally { session.close(); session.join(); collector.cancelAndJoin() }
    }

    private fun task(id: Long, type: Long, data: String = "{}") =
        Task.newBuilder().setId(id).setType(type).setData(data).build()

    private suspend fun result(task: Task, reserve: suspend (Long) -> AutoCloseable, bytes: Long = 1024): OwnedMcpResult {
        val lease = reserve(bytes)
        return OwnedMcpResult(when (task.type) {
            15L -> encoder.encode(task.id, task.type, ExecResult(exitCode = 7, timedOut = true))
            16L -> encoder.encode(task.id, task.type, FsListResult())
            else -> encoder.encode(task.id, task.type, FsReadResult("hello", "utf8", 5))
        }, lease)
    }

    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) yield()
    }

    private fun json(result: TaskResult) = JsonParser.parseString(result.data).asJsonObject

    private class PausedDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun runOne() { tasks.removeFirst().run() }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
}
