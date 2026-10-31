package com.nezhahq.agent.mcp.fs

import com.nezhahq.agent.core.mcp.McpBusinessError
import com.nezhahq.agent.core.mcp.McpErrorCode
import com.nezhahq.agent.core.mcp.McpLimits
import com.nezhahq.agent.core.mcp.McpPolicy
import com.nezhahq.agent.core.mcp.McpRequestRules
import com.nezhahq.agent.core.mcp.McpTaskFamily
import com.nezhahq.agent.core.mcp.McpValidation
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.mcp.McpParseResult
import com.nezhahq.agent.mcp.McpRequestParser
import com.nezhahq.agent.mcp.McpResultEncoder
import com.nezhahq.agent.mcp.transport.HelperCall
import com.nezhahq.agent.mcp.transport.HelperDeadline
import com.nezhahq.agent.mcp.transport.HelperExchangeResult
import com.nezhahq.agent.mcp.transport.HelperLauncher
import com.nezhahq.agent.mcp.transport.McpHelperTransport
import com.nezhahq.agent.mcp.transport.OwnedMcpResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import proto.Nezha.Task
import proto.Nezha.TaskResult

/** Runtime supplies the selected launcher, capability snapshot and registration's result budget. */
class McpFsBackend(
    private val launcher: HelperLauncher,
    private val transport: McpHelperTransport = McpHelperTransport(),
    private val encoder: McpResultEncoder = McpResultEncoder(),
    private val nanoTime: () -> Long = System::nanoTime
) {
    suspend fun execute(
        task: Task,
        capabilities: RemoteCapabilities,
        deadline: HelperDeadline? = null,
        reserveResult: suspend (serializedBytes: Long) -> AutoCloseable
    ): OwnedMcpResult {
        currentCoroutineContext().ensureActive()
        val started = nanoTime()
        fun elapsed() = (nanoTime() - started).coerceAtLeast(0) / 1_000_000
        suspend fun small(encode: () -> TaskResult) = reserved(reserveResult, encode)
        val family = McpPolicy.family(task.type)
        if (family != McpTaskFamily.FS_LIST && family != McpTaskFamily.FS_READ) {
            return small { encoder.unsupported(task.id, task.type) }
        }
        McpPolicy.denial(task.type, capabilities)?.let {
            return small { encoder.businessError(task.id, task.type, it) }
        }
        val parsed = when (val parsed = McpRequestParser.parse(task.type, task.data)) {
            is McpParseResult.Invalid -> return small { encoder.parseFailure(task.id, task.type, parsed.issue) }
            McpParseResult.Unsupported -> return small { encoder.unsupported(task.id, task.type) }
            is McpParseResult.Parsed -> parsed.request
        }
        when (val prepared = McpRequestRules.prepare(parsed)) {
            is McpValidation.Invalid -> return small { encoder.businessError(task.id, task.type, prepared.error) }
            is McpValidation.Valid -> Unit // The helper independently validates/cleans the original JSON again.
        }
        val timeLeft = (McpLimits.DEFAULT_FILE_TIMEOUT_SECONDS * 1000 - elapsed()).coerceAtLeast(0)
        val executionDeadline = HelperDeadline.afterMillis(
            minOf(timeLeft, deadline?.remainingMillis ?: timeLeft), nanoTime
        )
        return when (val outcome = transport.exchange(
            launcher, HelperCall(task.id, task.type, task.data, elapsed()), executionDeadline,
            outputLimit = 0, reserveResult = reserveResult
        )) {
            is HelperExchangeResult.Completed -> outcome.owned
            is HelperExchangeResult.Failed -> small {
                encoder.businessError(task.id, task.type, outcome.error ?: McpBusinessError(
                    McpErrorCode.INTERNAL,
                    if (outcome.timedOut) FsError.TIMED_OUT.safeMessage else FsError.IO.safeMessage
                ))
            }
        }
    }

    private suspend fun reserved(
        reserveResult: suspend (Long) -> AutoCloseable,
        encode: () -> TaskResult
    ): OwnedMcpResult {
        // Fixed safe errors are smaller than this; reserve before any result string/envelope exists.
        val reservation = reserveResult(SMALL_RESULT_BYTES)
        return try {
            currentCoroutineContext().ensureActive()
            val result = encode()
            check(result.serializedSize <= SMALL_RESULT_BYTES)
            currentCoroutineContext().ensureActive()
            OwnedMcpResult(result, reservation)
        } catch (failure: Throwable) {
            runCatching { reservation.close() }
            throw failure
        }
    }

    companion object { private const val SMALL_RESULT_BYTES = 1024L }
}
