package com.nezhahq.agent.mcp.exec

import com.nezhahq.agent.core.mcp.ExecRequest
import com.nezhahq.agent.core.mcp.ExecResult
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

/**
 * Standalone backend, deliberately not registered with AgentRuntime yet. Runtime still owns task
 * admission, its two live policy checks, selection/authorization and final send/drop ownership.
 * This backend checks the supplied capability snapshot before launching and never gains privilege.
 */
class McpExecBackend(
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
        fun elapsed() = ((nanoTime() - started).coerceAtLeast(0) / 1_000_000)
        suspend fun small(encode: () -> TaskResult): OwnedMcpResult = reserved(1024, reserveResult, encode)

        if (McpPolicy.family(task.type) != McpTaskFamily.EXEC) {
            return small { encoder.unsupported(task.id, task.type) }
        }
        McpPolicy.denial(task.type, capabilities)?.let { denial ->
            return small { encoder.businessError(task.id, task.type, denial) }
        }
        val parsed = when (val parsed = McpRequestParser.parse(task.type, task.data)) {
            is McpParseResult.Invalid -> return small { encoder.parseFailure(task.id, task.type, parsed.issue) }
            McpParseResult.Unsupported -> return small { encoder.unsupported(task.id, task.type) }
            is McpParseResult.Parsed -> parsed.request
        }
        val request = when (val prepared = McpRequestRules.prepare(parsed)) {
            is McpValidation.Invalid -> return small { encoder.businessError(task.id, task.type, prepared.error) }
            is McpValidation.Valid -> prepared.request as ExecRequest
        }
        ExecArguments.error(request)?.let { error ->
            return small { encoder.businessError(task.id, task.type, error) }
        }
        val timeLeft = (request.timeoutSeconds * 1000 - elapsed()).coerceAtLeast(0)
        val executionDeadline = HelperDeadline.afterMillis(
            minOf(timeLeft, deadline?.remainingMillis ?: timeLeft), nanoTime
        )
        return when (val outcome = transport.exchange(
            launcher, HelperCall(task.id, task.type, task.data, elapsed()), executionDeadline,
            request.maxOutputBytes.toInt(), reserveResult
        )) {
            is HelperExchangeResult.Completed -> outcome.owned
            is HelperExchangeResult.Failed -> {
                // Unlike a completed helper result there is no declared exact length. Reserve the
                // hard envelope cap BEFORE decoding streamed raw bytes and encoding fallback JSON.
                // Two admitted Exec tasks can therefore never manufacture unaccounted 3 MiB results.
                reserved(McpLimits.MAX_TASK_RESULT_BYTES.toLong(), reserveResult) {
                    val output = outcome.output?.snapshot()
                    encoder.encode(task.id, task.type, ExecResult(
                        exitCode = -1, stdout = output?.stdout.orEmpty(), stderr = output?.stderr.orEmpty(),
                        durationMs = elapsed(), stdoutTruncated = output?.stdoutTruncated ?: false,
                        stderrTruncated = output?.stderrTruncated ?: false,
                        timedOut = outcome.timedOut, error = outcome.error?.message
                    ))
                }
            }
        }
    }

    private suspend fun reserved(
        bytes: Long,
        reserveResult: suspend (Long) -> AutoCloseable,
        encode: () -> TaskResult
    ): OwnedMcpResult {
        val reservation = reserveResult(bytes)
        return try {
            currentCoroutineContext().ensureActive()
            val result = encode()
            check(result.serializedSize <= bytes && result.serializedSize <= McpLimits.MAX_TASK_RESULT_BYTES)
            currentCoroutineContext().ensureActive()
            OwnedMcpResult(result, reservation)
        } catch (failure: Throwable) {
            runCatching { reservation.close() }
            throw failure
        }
    }
}
