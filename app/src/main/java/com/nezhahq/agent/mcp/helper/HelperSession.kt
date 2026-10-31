package com.nezhahq.agent.mcp.helper

import com.nezhahq.agent.core.mcp.ExecRequest
import com.nezhahq.agent.core.mcp.McpBusinessError
import com.nezhahq.agent.core.mcp.McpErrorCode
import com.nezhahq.agent.core.mcp.FsListRequest
import com.nezhahq.agent.core.mcp.FsReadRequest
import com.nezhahq.agent.core.mcp.McpLimits
import com.nezhahq.agent.core.mcp.McpRequest
import com.nezhahq.agent.core.mcp.McpRequestRules
import com.nezhahq.agent.core.mcp.McpResult
import com.nezhahq.agent.core.mcp.McpTaskFamily
import com.nezhahq.agent.core.mcp.McpValidation
import com.nezhahq.agent.mcp.McpParseResult
import com.nezhahq.agent.mcp.McpRequestParser
import com.nezhahq.agent.mcp.McpResultEncoder
import com.nezhahq.agent.mcp.exec.ExecEnvironment
import com.nezhahq.agent.mcp.exec.ExecOutput
import com.nezhahq.agent.mcp.exec.HelperExecRunner
import com.nezhahq.agent.mcp.fs.FsAccess
import com.nezhahq.agent.mcp.fs.HelperFsRunner
import com.nezhahq.agent.mcp.transport.HelperDeadline
import com.nezhahq.agent.mcp.transport.HelperProtocol
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Android isolation is injected so protocol ordering can be checked on a real JVM. */
interface HelperIsolation {
    fun establish(nonce: ByteArray): HelperProtocol.Hello
    /** Idempotent. A production implementation does not return: it SIGKILLs its owned group. */
    fun terminate()
}

data class HelperExecutionContext(val deadline: HelperDeadline, val elapsedBeforeRequest: Long)

fun interface HelperRequestHandler {
    fun execute(request: McpRequest, context: HelperExecutionContext, emit: (ExecOutput) -> Unit): McpResult
}

/** One request and one terminal. Fs handlers can reuse this session without changing the transport. */
class HelperSession(
    private val isolation: HelperIsolation,
    private val handlers: Map<McpTaskFamily, HelperRequestHandler>,
    private val encoder: McpResultEncoder = McpResultEncoder()
) {
    fun run(input: InputStream, output: OutputStream, nonce: ByteArray, lifetimeMillis: Int) {
        require(lifetimeMillis in 1..HelperProtocol.MAX_LIFETIME_MILLIS)
        val lifetime = HelperDeadline.afterMillis(lifetimeMillis.toLong())
        val writer = HelperProtocol.Writer(output)
        var watchdog: Thread? = null
        try {
            // Includes isolation setup and its bounded /proc check, before any user request exists.
            watchdog = Thread({
                try {
                    Thread.sleep(lifetimeMillis.toLong() + WATCHDOG_GRACE_MILLIS)
                    isolation.terminate()
                } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }, "mcp-helper-watchdog").apply { isDaemon = true; start() }
            val hello = try { isolation.establish(nonce) } catch (_: Exception) {
                writer.frame(HelperProtocol.Kind.UNAVAILABLE, ByteArray(0))
                return
            }
            if (!hello.verified(nonce, hello.uid)) {
                writer.frame(HelperProtocol.Kind.UNAVAILABLE, ByteArray(0))
                return
            }
            // A finite independent watchdog also covers stalled parsing, files, native startup,
            // output writes and parent/Binder failure. Control EOF has a separate read owner.
            writer.frame(HelperProtocol.Kind.HELLO, HelperProtocol.encodeHello(hello))
            val reader = HelperProtocol.Reader(input)
            val header = reader.header(setOf(HelperProtocol.Kind.REQUEST))
            val request = HelperProtocol.decodeRequest(reader.payload(header))
            // Main is finished reading. This worker now exclusively owns the control input;
            // target stdin is a completely different pipe and cannot hold this EOF hostage.
            Thread({
                try { input.read() } catch (_: Exception) { /* Transport failure is revocation. */ }
                isolation.terminate()
            }, "mcp-helper-control").apply { isDaemon = true; start() }

            val result = when (val parsed = McpRequestParser.parse(request.type, request.data)) {
                is McpParseResult.Invalid -> encoder.parseFailure(request.id, request.type, parsed.issue)
                McpParseResult.Unsupported -> encoder.unsupported(request.id, request.type)
                is McpParseResult.Parsed -> when (val prepared = McpRequestRules.prepare(parsed.request)) {
                    is McpValidation.Invalid -> encoder.businessError(request.id, request.type, prepared.error)
                    is McpValidation.Valid -> {
                        val handler = handlers[prepared.request.family]
                        if (handler == null) encoder.businessError(request.id, request.type, McpBusinessError(
                            McpErrorCode.CAPABILITY_UNAVAILABLE, "MCP helper handler is unavailable"
                        )) else {
                            val preparedRequest = prepared.request
                            val maximum = if (preparedRequest is ExecRequest) preparedRequest.timeoutSeconds * 1000
                                else McpLimits.DEFAULT_FILE_TIMEOUT_SECONDS * 1000
                            val remaining = minOf(lifetime.remainingMillis, request.remainingMillis.toLong(), maximum)
                            val context = HelperExecutionContext(
                                HelperDeadline.afterMillis(remaining), request.elapsedMillis.toLong()
                            )
                            val outcome = try {
                                handler.execute(prepared.request, context) { chunk ->
                                    when (chunk) {
                                        is ExecOutput.Bytes -> writer.frame(
                                            if (chunk.stderr) HelperProtocol.Kind.STDERR else HelperProtocol.Kind.STDOUT,
                                            chunk.bytes
                                        )
                                        is ExecOutput.Truncated -> writer.frame(
                                            HelperProtocol.Kind.TRUNCATED,
                                            byteArrayOf(if (chunk.stderr) 2 else 1)
                                        )
                                    }
                                }
                            } catch (_: Exception) {
                                // A protocol/output failure must not make execution appear successful.
                                return
                            }
                            encoder.encode(request.id, request.type, outcome)
                        }
                    }
                }
            }
            writer.frame(HelperProtocol.Kind.RESULT, result.toByteArray())
        } finally {
            // Write/flush the complete terminal BEFORE killing the session. The outer helper
            // exit (usually SIGKILL/137) is never the user process exit_code.
            watchdog?.interrupt()
            isolation.terminate()
        }
    }

    companion object {
        const val WATCHDOG_GRACE_MILLIS = 2_000L

        fun execHandlers(defaultDirectory: File, temporaryDirectory: File): Map<McpTaskFamily, HelperRequestHandler> =
            mapOf(McpTaskFamily.EXEC to HelperRequestHandler { request, context, emit ->
                HelperExecRunner(ExecEnvironment(defaultDirectory, temporaryDirectory)).execute(
                    request as ExecRequest, context.deadline, context.elapsedBeforeRequest, emit
                )
            })

        fun handlers(defaultDirectory: File, temporaryDirectory: File, files: FsAccess): Map<McpTaskFamily, HelperRequestHandler> {
            val fs = HelperFsRunner(files)
            return execHandlers(defaultDirectory, temporaryDirectory) + mapOf(
                McpTaskFamily.FS_LIST to HelperRequestHandler { request, context, _ ->
                    fs.list(request as FsListRequest, context)
                },
                McpTaskFamily.FS_READ to HelperRequestHandler { request, context, _ ->
                    fs.read(request as FsReadRequest, context)
                }
            )
        }
    }
}
