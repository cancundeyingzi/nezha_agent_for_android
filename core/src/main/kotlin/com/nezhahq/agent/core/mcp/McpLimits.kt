package com.nezhahq.agent.core.mcp

import java.util.concurrent.atomic.AtomicBoolean

object McpLimits {
    const val UINT32_MAX = 4_294_967_295L
    const val DEFAULT_EXEC_TIMEOUT_SECONDS = 30L
    const val MAX_EXEC_TIMEOUT_SECONDS = 300L
    const val DEFAULT_EXEC_MAX_OUTPUT_BYTES = 65_536L
    const val MAX_EXEC_OUTPUT_BYTES = 1_048_576L
    const val MAX_FS_READ_BYTES = 1_048_576L
    const val MAX_FS_LIST_ENTRIES = 5_000
    const val MAX_REQUEST_BYTES = 1_048_576L
    const val MAX_JSON_DEPTH = 64
    const val MAX_TASK_RESULT_BYTES = 3 * 1024 * 1024
    const val REQUEST_BUDGET_BYTES = 4L * 1024 * 1024
    const val RESULT_BUDGET_BYTES = 6L * 1024 * 1024
    const val MAX_EXEC_TASKS = 2
    const val MAX_FILE_TASKS = 2
}

/** An owner may close this lease from completion, cancellation and session teardown safely. */
class McpLease internal constructor(private val release: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    val isClosed: Boolean get() = closed.get()

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

sealed interface McpBudgetResult {
    data class Reserved(val lease: McpLease) : McpBudgetResult
    data object Busy : McpBudgetResult
    data object InvalidSize : McpBudgetResult
}

/** Wire-byte accounting. Nonblocking, overflow-safe, with no Android/coroutine dependencies. */
class McpByteBudget(val capacityBytes: Long) {
    init { require(capacityBytes >= 0) { "Byte budget must not be negative" } }

    private var retainedBytes = 0L
    val usedBytes: Long get() = synchronized(this) { retainedBytes }

    @Synchronized
    fun tryReserve(bytes: Long): McpBudgetResult {
        if (bytes < 0) return McpBudgetResult.InvalidSize
        if (bytes > capacityBytes - retainedBytes) return McpBudgetResult.Busy
        retainedBytes += bytes
        return McpBudgetResult.Reserved(McpLease {
            synchronized(this) { retainedBytes -= bytes }
        })
    }
}

enum class McpBusyReason { FAMILY_LIMIT, REQUEST_BUDGET, RESULT_BUDGET }

sealed interface McpAdmissionResult {
    data class Admitted(val lease: McpLease) : McpAdmissionResult
    data class Busy(val reason: McpBusyReason) : McpAdmissionResult
    data object InvalidRequestSize : McpAdmissionResult
}

/**
 * Acquire on task intake, before dispatch to dedicated MCP workers. Never make an old short-task
 * worker wait on these quotas. The task lease owns its request reservation through final cleanup.
 * Queued/retained result messages require a separate result lease until send/drop completes.
 */
class McpTaskAdmission {
    private val requests = McpByteBudget(McpLimits.REQUEST_BUDGET_BYTES)
    private val results = McpByteBudget(McpLimits.RESULT_BUDGET_BYTES)
    private var execCount = 0
    private var fileCount = 0

    val retainedRequestBytes: Long get() = requests.usedBytes
    val retainedResultBytes: Long get() = results.usedBytes
    val activeExecTasks: Int get() = synchronized(this) { execCount }
    val activeFileTasks: Int get() = synchronized(this) { fileCount }

    @Synchronized
    fun tryAcquire(family: McpTaskFamily, requestBytes: Long): McpAdmissionResult {
        if (requestBytes !in 0..McpLimits.MAX_REQUEST_BYTES) {
            return McpAdmissionResult.InvalidRequestSize
        }
        val exec = family == McpTaskFamily.EXEC
        if ((exec && execCount >= McpLimits.MAX_EXEC_TASKS) ||
            (!exec && fileCount >= McpLimits.MAX_FILE_TASKS)) {
            return McpAdmissionResult.Busy(McpBusyReason.FAMILY_LIMIT)
        }
        val reservation = requests.tryReserve(requestBytes)
        if (reservation !is McpBudgetResult.Reserved) {
            return McpAdmissionResult.Busy(McpBusyReason.REQUEST_BUDGET)
        }
        if (exec) execCount++ else fileCount++
        return McpAdmissionResult.Admitted(McpLease {
            synchronized(this) {
                reservation.lease.close()
                if (exec) execCount-- else fileCount--
            }
        })
    }

    fun tryReserveResult(serializedBytes: Long): McpBudgetResult =
        if (serializedBytes !in 0..McpLimits.MAX_TASK_RESULT_BYTES.toLong()) {
            McpBudgetResult.InvalidSize
        } else {
            results.tryReserve(serializedBytes)
        }
}
