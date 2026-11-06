package com.nezhahq.agent.service

import kotlinx.coroutines.channels.SendChannel
import proto.Nezha.Task
import proto.Nezha.TaskResult

internal enum class ShortTaskAdmission {
    ENQUEUED,
    REJECTED_QUEUE_FULL
}

/**
 * Enqueues one short task without creating an unbounded collection of suspended senders.
 *
 * When the worker queue is full, the caller itself applies backpressure while reporting the
 * rejection. This keeps memory bounded and lets gRPC flow control slow the Dashboard down.
 */
internal suspend fun enqueueShortTaskWithBackpressure(
    task: Task,
    shortTaskQueue: SendChannel<Task>,
    resultChannel: SendChannel<TaskResult>,
    queueName: String = "short-task"
): ShortTaskAdmission {
    val admission = shortTaskQueue.trySend(task)
    if (admission.isSuccess) return ShortTaskAdmission.ENQUEUED
    if (admission.isClosed) admission.getOrThrow()

    resultChannel.send(
        buildFailedTaskResult(
            task,
            "Task dropped: local $queueName queue is full."
        )
    )
    return ShortTaskAdmission.REJECTED_QUEUE_FULL
}

internal fun buildFailedTaskResult(task: Task, message: String): TaskResult =
    TaskResult.newBuilder()
        .setId(task.id)
        .setType(task.type)
        .setSuccessful(false)
        .setData(message)
        .build()
