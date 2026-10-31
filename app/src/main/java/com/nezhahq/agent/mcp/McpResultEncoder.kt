package com.nezhahq.agent.mcp

import com.google.gson.stream.JsonWriter
import com.google.protobuf.CodedOutputStream
import com.nezhahq.agent.core.mcp.ExecResult
import com.nezhahq.agent.core.mcp.FsListResult
import com.nezhahq.agent.core.mcp.FsReadResult
import com.nezhahq.agent.core.mcp.McpBusinessError
import com.nezhahq.agent.core.mcp.McpLimits
import com.nezhahq.agent.core.mcp.McpPolicy
import com.nezhahq.agent.core.mcp.McpResult
import com.nezhahq.agent.core.mcp.McpTaskFamily
import com.nezhahq.agent.core.task.TaskTypes
import proto.Nezha.TaskResult
import java.io.IOException
import java.io.Writer

/**
 * The envelope id/type stay Long bit patterns end-to-end (protobuf uint64), never JSON numbers.
 * A structured business outcome is successful=true even for denial, timeout, busy or nonzero exit.
 */
class McpResultEncoder(private val maxResultBytes: Int = McpLimits.MAX_TASK_RESULT_BYTES) {
    init {
        require(maxResultBytes in 1024..McpLimits.MAX_TASK_RESULT_BYTES) {
            "Result limit must be between 1024 bytes and the MCP maximum"
        }
    }

    fun encode(id: Long, type: Long, result: McpResult): TaskResult {
        if (McpPolicy.family(type) != result.family) return internalFailure(id, type)
        return try {
            encodeBounded(id, type, result)
        } catch (_: ResultTooLarge) {
            try {
                encodeBounded(id, type, tooLarge(result))
            } catch (_: Exception) {
                internalFailure(id, type)
            }
        } catch (_: Exception) {
            internalFailure(id, type)
        }
    }

    fun businessError(id: Long, type: Long, error: McpBusinessError): TaskResult {
        val result = when (McpPolicy.family(type)) {
            McpTaskFamily.EXEC -> ExecResult(error = error.message)
            McpTaskFamily.FS_LIST -> FsListResult(error = error.message)
            McpTaskFamily.FS_READ -> FsReadResult(error = error.message)
            null -> return unsupported(id, type)
        }
        return encode(id, type, result)
    }

    fun parseFailure(id: Long, type: Long, issue: McpParseIssue): TaskResult =
        envelope(id, type, false)
            .setData("invalid MCP request: ${issue.safeDescription}")
            .build()

    fun unsupported(id: Long, type: Long): TaskResult = envelope(id, type, false)
        .setData(TaskTypes.unsupportedMessage(type))
        .build()

    private fun encodeBounded(id: Long, type: Long, result: McpResult): TaskResult {
        val builder = envelope(id, type, true)
        // Reserving the maximum possible data-length varint is deliberately conservative.
        val dataOverhead = CodedOutputStream.computeTagSize(4) +
            CodedOutputStream.computeUInt32SizeNoTag(maxResultBytes)
        val writer = BoundedUtf8Writer(maxResultBytes - builder.build().serializedSize - dataOverhead)
        JsonWriter(writer).use { json -> writeResult(json, result) }
        val encoded = builder.setData(writer.content()).build()
        if (encoded.serializedSize > maxResultBytes) throw ResultTooLarge()
        return encoded
    }

    private fun writeResult(json: JsonWriter, result: McpResult) {
        json.beginObject()
        when (result) {
            is ExecResult -> {
                json.name("exit_code").value(result.exitCode)
                json.name("stdout").value(result.stdout)
                json.name("stderr").value(result.stderr)
                json.name("duration_ms").value(result.durationMs)
                if (result.stdoutTruncated) json.name("stdout_truncated").value(true)
                if (result.stderrTruncated) json.name("stderr_truncated").value(true)
                if (result.timedOut) json.name("timed_out").value(true)
            }
            is FsListResult -> {
                json.name("entries").beginArray()
                for (entry in result.entries) {
                    json.beginObject()
                    json.name("name").value(entry.name)
                    json.name("type").value(entry.type.wireName)
                    json.name("size").value(entry.size)
                    json.name("mode").value(entry.mode)
                    json.name("mtime").value(entry.mtime)
                    if (entry.isSymlink) json.name("is_symlink").value(true)
                    entry.linkTarget?.takeIf { it.isNotEmpty() }?.let {
                        json.name("link_target").value(it)
                    }
                    json.endObject()
                }
                json.endArray()
                if (result.truncated) json.name("truncated").value(true)
                if (result.total != 0L) json.name("total").value(result.total)
            }
            is FsReadResult -> {
                json.name("content").value(result.content)
                json.name("encoding").value(result.encoding)
                json.name("size").value(result.size)
                result.sha256?.takeIf { it.isNotEmpty() }?.let { json.name("sha256").value(it) }
                if (result.truncated) json.name("truncated").value(true)
            }
        }
        result.error?.takeIf { it.isNotEmpty() }?.let { json.name("error").value(it) }
        json.endObject()
    }

    private fun tooLarge(result: McpResult): McpResult = when (result) {
        is ExecResult -> result.copy(
            stdout = "",
            stderr = "",
            stdoutTruncated = result.stdoutTruncated || result.stdout.isNotEmpty(),
            stderrTruncated = result.stderrTruncated || result.stderr.isNotEmpty(),
            error = OUTPUT_TOO_LARGE
        )
        is FsListResult -> FsListResult(
            entries = emptyList(), total = result.total, truncated = true, error = OUTPUT_TOO_LARGE
        )
        is FsReadResult -> FsReadResult(
            encoding = if (result.encoding == "base64") "base64" else "utf8",
            truncated = true, error = OUTPUT_TOO_LARGE
        )
    }

    private fun internalFailure(id: Long, type: Long): TaskResult = envelope(id, type, false)
        .setData(INTERNAL_ENCODING_FAILURE)
        .build()

    private fun envelope(id: Long, type: Long, successful: Boolean): TaskResult.Builder =
        TaskResult.newBuilder().setId(id).setType(type).setSuccessful(successful)

    companion object {
        const val OUTPUT_TOO_LARGE = "output/result too large"
        const val INTERNAL_ENCODING_FAILURE = "{\"error\":\"agent: failed to encode result\"}"
    }
}

private class ResultTooLarge : IOException()

/**
 * Counts bytes while JsonWriter escapes strings. At most one capped JSON document is retained,
 * even if a 2 MiB command output consists entirely of characters that expand sixfold to \\uXXXX.
 */
internal class BoundedUtf8Writer(private val maxBytes: Int) : Writer() {
    private val buffer = StringBuilder(minOf(1024, maxBytes.coerceAtLeast(0)))
    private var bytes = 0L
    private var previousHigh = false
    val utf8Bytes: Long get() = bytes

    override fun write(chars: CharArray, offset: Int, length: Int) {
        checkBounds(chars.size, offset, length)
        for (index in offset until offset + length) appendChar(chars[index])
    }

    override fun write(value: String, offset: Int, length: Int) {
        checkBounds(value.length, offset, length)
        for (index in offset until offset + length) appendChar(value[index])
    }

    override fun write(char: Int) = appendChar(char.toChar())
    override fun flush() = Unit
    override fun close() = Unit
    fun content(): String = buffer.toString()

    private fun appendChar(char: Char) {
        val next = bytes + utf8ByteIncrement(char, previousHigh)
        if (next > maxBytes) throw ResultTooLarge()
        buffer.append(char)
        bytes = next
        previousHigh = char.isHighSurrogate()
    }

    private fun checkBounds(size: Int, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > size - length) throw IndexOutOfBoundsException()
    }
}
