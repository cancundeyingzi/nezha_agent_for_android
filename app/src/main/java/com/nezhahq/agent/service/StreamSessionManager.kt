package com.nezhahq.agent.service

import com.nezhahq.agent.core.task.TaskTypes
import com.nezhahq.agent.executor.requireValidNatTarget
import com.nezhahq.agent.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import org.json.JSONObject
import proto.Nezha.Task
import proto.Nezha.TaskResult

/** One per outer task scope. This class borrows that scope and never creates its own Job. */
internal class StreamSessionManager(
    private val sessionScope: CoroutineScope,
    private val results: SendChannel<TaskResult>,
    private val runSession: suspend (StreamTaskRequest) -> Unit,
    private val parseRequest: (Task) -> StreamTaskRequest = ::parseStreamTask,
    private val registry: StreamSessionRegistry = StreamSessionRegistry(
        maxTotal = 36,
        maxByTaskType = mapOf(
            TaskTypes.TERMINAL to 2,
            TaskTypes.NAT to 32,
            TaskTypes.FILE_MANAGER to 2
        )
    )
) {
    suspend fun launch(task: Task) {
        val request = try {
            parseRequest(task)
        } catch (exception: Exception) {
            results.send(buildFailedTaskResult(task, "Invalid stream task: ${exception.message ?: "unknown error"}"))
            return
        }
        val lease = when (val admission = registry.tryAcquire(task.type, request.streamId)) {
            is StreamSessionAdmission.Accepted -> admission.lease
            is StreamSessionAdmission.Rejected -> {
                results.send(buildFailedTaskResult(task, admission.reason))
                return
            }
        }
        // No suspension between acquisition and completion ownership, even when the parent scope
        // is already cancelled and the body never starts.
        val session = sessionScope.launch {
            try {
                runSession(request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                val name = when (request) {
                    is StreamTaskRequest.Terminal -> "终端"
                    is StreamTaskRequest.Nat -> "NAT 内网穿透"
                    is StreamTaskRequest.FileManager -> "文件管理器"
                }
                Logger.e("$name 任务执行失败 (TaskID=${task.id}, Error=${exception.javaClass.simpleName})")
            }
        }
        session.invokeOnCompletion { lease.close() }
    }
}

internal sealed interface StreamTaskRequest {
    val task: Task
    val streamId: String

    data class Terminal(override val task: Task, override val streamId: String) : StreamTaskRequest
    data class Nat(override val task: Task, override val streamId: String, val host: String) : StreamTaskRequest
    data class FileManager(override val task: Task, override val streamId: String) : StreamTaskRequest
}

/** The Android JSON parser stays at the boundary; JVM lifecycle tests inject already parsed requests. */
private fun parseStreamTask(task: Task): StreamTaskRequest {
    val json = JSONObject(task.data)
    val streamId = json.getString("StreamID")
    require(streamId.isNotBlank()) { "StreamID must not be blank." }
    require(streamId.toByteArray(Charsets.UTF_8).size <= MAX_STREAM_ID_BYTES) {
        "StreamID exceeds the $MAX_STREAM_ID_BYTES-byte limit."
    }
    return when (task.type) {
        TaskTypes.TERMINAL -> StreamTaskRequest.Terminal(task, streamId)
        TaskTypes.NAT -> {
            val host = json.getString("Host")
            require(host.isNotBlank()) { "NAT Host must not be blank." }
            require(host.toByteArray(Charsets.UTF_8).size <= MAX_NAT_HOST_BYTES) {
                "NAT Host exceeds the $MAX_NAT_HOST_BYTES-byte limit."
            }
            requireValidNatTarget(host)
            StreamTaskRequest.Nat(task, streamId, host)
        }
        TaskTypes.FILE_MANAGER -> StreamTaskRequest.FileManager(task, streamId)
        else -> error("Task type ${task.type} is not a stream task.")
    }
}

private const val MAX_STREAM_ID_BYTES = 256
private const val MAX_NAT_HOST_BYTES = 1024
