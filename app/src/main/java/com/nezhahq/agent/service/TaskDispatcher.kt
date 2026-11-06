package com.nezhahq.agent.service

import com.nezhahq.agent.core.mcp.McpPolicy
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.core.task.RemoteCapabilityPolicy
import com.nezhahq.agent.core.task.TaskTypes
import com.nezhahq.agent.executor.TaskExecutor
import com.nezhahq.agent.mcp.McpSession
import com.nezhahq.agent.util.DashboardSessionWatchdog
import com.nezhahq.agent.util.Logger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import proto.Nezha.Task
import proto.Nezha.TaskResult

/**
 * Owns the live capability grant and current MCP registration for one runtime.
 * Each run creates one task scope: workers, legacy results and traditional sessions survive its
 * replaceable RequestTask producer. MCP work and reservations belong only to that producer.
 */
internal class TaskDispatcher(
    initialCapabilities: RemoteCapabilities,
    private val createMcpSession: (CoroutineScope, () -> RemoteCapabilities) -> McpSession,
    private val executeShortTask: suspend (Task, RemoteCapabilities) -> TaskResult = { task, capabilities ->
        TaskExecutor.executeTask(task, capabilities = capabilities)
    },
    private val idleTimeoutMillis: Long = DashboardSessionWatchdog.TASK_IDLE_TIMEOUT_MS
) {
    private val capabilities = AtomicReference(initialCapabilities)
    private val currentMcpSession = AtomicReference<McpSession?>()

    suspend fun updateCapabilities(updated: RemoteCapabilities) {
        capabilities.set(updated)
        currentMcpSession.get()?.revokeDisabled(updated)
    }

    suspend fun run(
        openStream: (Flow<TaskResult>) -> Flow<Task>,
        createStreamSessions: (CoroutineScope, SendChannel<TaskResult>) -> StreamSessionManager
    ): Unit = coroutineScope {
        val results = Channel<TaskResult>(TASK_RESULT_BUFFER_CAPACITY)
        val shortTasks = Channel<Task>(SHORT_TASK_QUEUE_CAPACITY)
        val commands = Channel<Task>(COMMAND_QUEUE_CAPACITY)
        val streamSessions = createStreamSessions(this, results)
        val workers = List(SHORT_TASK_WORKER_COUNT) {
            launch {
                for (task in shortTasks) results.send(executeShortTask(task, capabilities.get()))
            }
        } + List(COMMAND_WORKER_COUNT) {
            launch {
                for (task in commands) results.send(executeShortTask(task, capabilities.get()))
            }
        }
        var registration: McpSession? = null
        try {
            consumeWithIdleRestart(
                idleTimeoutMillis = idleTimeoutMillis,
                streamName = "RequestTask stream",
                onStreamStarted = { streamScope ->
                    createMcpSession(streamScope, capabilities::get).also {
                        registration = it
                        currentMcpSession.set(it)
                    }
                },
                openStream = {
                    // This producer and its outgoing flow capture the same registration. Neither
                    // routes late work through the mutable pointer used for capability updates.
                    val mcp = checkNotNull(registration)
                    openStream(mcp.outgoingWith(results))
                        .buffer(TASK_INPUT_BUFFER_CAPACITY)
                        .produceIn(this)
                },
                onStreamStopped = {
                    registration?.let { stopped ->
                        currentMcpSession.compareAndSet(stopped, null)
                        stopped.close()
                    }
                    registration = null
                },
                onIdleRestart = { Logger.i("AgentRuntime: 任务流空闲，仅重建任务流") }
            ) { task ->
                route(task, shortTasks, commands, results, streamSessions, checkNotNull(registration))
            }
        } finally {
            workers.forEach(Job::cancel)
            shortTasks.close()
            commands.close()
            results.close()
        }
    }

    private suspend fun route(
        task: Task,
        shortTasks: SendChannel<Task>,
        commands: SendChannel<Task>,
        results: SendChannel<TaskResult>,
        streamSessions: StreamSessionManager,
        mcp: McpSession
    ) {
        if (McpPolicy.family(task.type) != null) {
            mcp.handle(task)
            return
        }
        RemoteCapabilityPolicy.denialReason(task.type, capabilities.get())?.let { reason ->
            results.send(buildFailedTaskResult(task, reason))
            return
        }
        if (task.type in TaskTypes.STREAM_TASKS) {
            streamSessions.launch(task)
        } else if (task.type == TaskTypes.COMMAND) {
            enqueueShortTaskWithBackpressure(task, commands, results, queueName = "command")
        } else {
            enqueueShortTaskWithBackpressure(task, shortTasks, results)
        }
    }

    private companion object {
        const val SHORT_TASK_WORKER_COUNT = 8
        const val SHORT_TASK_QUEUE_CAPACITY = 64
        const val COMMAND_WORKER_COUNT = 2
        const val COMMAND_QUEUE_CAPACITY = 16
        const val TASK_INPUT_BUFFER_CAPACITY = 0
        const val TASK_RESULT_BUFFER_CAPACITY = 8
    }
}
