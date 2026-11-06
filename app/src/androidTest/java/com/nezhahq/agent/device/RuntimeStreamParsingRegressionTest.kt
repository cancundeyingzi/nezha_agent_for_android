package com.nezhahq.agent.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nezhahq.agent.core.task.TaskTypes
import com.nezhahq.agent.service.StreamSessionManager
import com.nezhahq.agent.service.StreamSessionRegistry
import com.nezhahq.agent.service.StreamTaskRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import proto.Nezha.Task
import proto.Nezha.TaskResult

@RunWith(AndroidJUnit4::class)
class RuntimeStreamParsingRegressionTest {
    @get:Rule val evidence = DeviceEvidenceRule()

    @Test fun androidJsonParserAdmitsAllLegacyFamiliesAndRejectsInvalidRequestsWithoutLeakingQuota() = runBlocking {
        val parent = Job(coroutineContext[Job])
        val registry = StreamSessionRegistry(3, mapOf(
            TaskTypes.TERMINAL to 1, TaskTypes.NAT to 1, TaskTypes.FILE_MANAGER to 1
        ))
        val results = Channel<TaskResult>(Channel.UNLIMITED)
        val started = Channel<StreamTaskRequest>(Channel.UNLIMITED)
        // Only execution is replaced. The default production JSONObject parser and admission run
        // in the optimized application; no terminal, forwarding socket or dashboard is opened.
        val manager = StreamSessionManager(CoroutineScope(coroutineContext + parent), results,
            runSession = { started.send(it); awaitCancellation() }, registry = registry)
        try {
            manager.launch(task(1, TaskTypes.TERMINAL, """{"StreamID":"terminal"}"""))
            manager.launch(task(2, TaskTypes.NAT, """{"StreamID":"nat","Host":"127.0.0.1:8080"}"""))
            manager.launch(task(3, TaskTypes.FILE_MANAGER, """{"StreamID":"files"}"""))
            val sessions = withTimeout(10_000) { List(3) { started.receive() }.associateBy { it.task.id } }
            assertTrue(sessions.getValue(1) is StreamTaskRequest.Terminal)
            assertEquals("terminal", sessions.getValue(1).streamId)
            assertEquals("127.0.0.1:8080", (sessions.getValue(2) as StreamTaskRequest.Nat).host)
            assertTrue(sessions.getValue(3) is StreamTaskRequest.FileManager)
            assertEquals(3, registry.activeCount())

            val invalid = listOf(
                task(4, TaskTypes.TERMINAL, "{"),
                task(5, TaskTypes.TERMINAL, """{"StreamID":" "}"""),
                task(6, TaskTypes.TERMINAL, """{"StreamID":"${"😀".repeat(65)}"}"""),
                task(7, TaskTypes.NAT, """{"StreamID":"bad-nat","Host":"127.0.0.1:0"}"""),
                task(8, TaskTypes.NAT, """{"StreamID":"bad-host","Host":"${"x".repeat(1025)}"}""")
            )
            invalid.forEach { request ->
                manager.launch(request)
                val rejected = withTimeout(10_000) { results.receive() }
                assertEquals(request.id, rejected.id)
                assertEquals(request.type, rejected.type)
                assertFalse(rejected.successful)
                assertTrue(rejected.data, rejected.data.startsWith("Invalid stream task:"))
            }
            manager.launch(task(9, TaskTypes.FILE_MANAGER, """{"StreamID":"terminal"}"""))
            assertTrue(withTimeout(10_000) { results.receive() }.data.contains("already active"))
            manager.launch(task(10, TaskTypes.NAT, """{"StreamID":"extra","Host":"127.0.0.1:8080"}"""))
            assertTrue(withTimeout(10_000) { results.receive() }.data.contains("3 active stream sessions"))
            assertTrue(started.tryReceive().isFailure)
            assertEquals(3, registry.activeCount())
        } finally { withTimeout(10_000) { parent.cancelAndJoin() } }
        assertEquals(0, registry.activeCount())
    }

    private fun task(id: Long, type: Long, data: String): Task =
        Task.newBuilder().setId(id).setType(type).setData(data).build()
}
