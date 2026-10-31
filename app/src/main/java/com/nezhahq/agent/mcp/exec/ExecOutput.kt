package com.nezhahq.agent.mcp.exec

import com.nezhahq.agent.mcp.transport.HelperProtocol
import com.nezhahq.agent.mcp.transport.ProtocolFailure
import com.nezhahq.agent.core.mcp.McpLimits
import java.io.ByteArrayOutputStream

sealed interface ExecOutput {
    data class Bytes(val stderr: Boolean, val bytes: ByteArray) : ExecOutput
    data class Truncated(val stderr: Boolean) : ExecOutput
}

/** Retention is by original bytes. Decode once at the end, replacing malformed UTF-8 consistently. */
class ExecOutputCapture(private val limit: Int) {
    private val stdout = ByteArrayOutputStream(minOf(limit, HelperProtocol.OUTPUT_CHUNK_BYTES))
    private val stderr = ByteArrayOutputStream(minOf(limit, HelperProtocol.OUTPUT_CHUNK_BYTES))
    private var stdoutTruncated = false
    private var stderrTruncated = false

    init { require(limit in 1..McpLimits.MAX_EXEC_OUTPUT_BYTES.toInt()) }

    @Synchronized
    fun accept(output: ExecOutput) {
        when (output) {
            is ExecOutput.Bytes -> {
                val target = if (output.stderr) stderr else stdout
                if (output.bytes.size > limit - target.size()) throw ProtocolFailure()
                target.write(output.bytes)
            }
            is ExecOutput.Truncated -> if (output.stderr) stderrTruncated = true else stdoutTruncated = true
        }
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        stdout.toByteArray().toString(Charsets.UTF_8), stderr.toByteArray().toString(Charsets.UTF_8),
        stdoutTruncated, stderrTruncated
    )

    data class Snapshot(val stdout: String, val stderr: String, val stdoutTruncated: Boolean, val stderrTruncated: Boolean)
}
