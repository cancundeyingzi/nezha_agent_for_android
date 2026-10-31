package com.nezhahq.agent.mcp

import com.google.gson.JsonParser
import com.nezhahq.agent.SilentLoggerRule
import com.nezhahq.agent.core.mcp.*
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.core.task.TaskTypes
import com.nezhahq.agent.executor.TaskExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import proto.Nezha.Task
import proto.Nezha.TaskResult
import java.nio.charset.StandardCharsets

class McpResultEncoderTest {
    @get:Rule val silentLogger = SilentLoggerRule()
    private val encoder = McpResultEncoder()

    @Test
    fun fixedExecFixtureKeepsActualNonzeroExitAndExactInt64WithoutChangingSuccess() {
        val result = encoder.encode(Long.MIN_VALUE, 15, ExecResult(
            exitCode = 7, stdout = "你好😀\n\u0001", stderr = "denied\n",
            durationMs = 9_007_199_254_740_993L
        ))
        assertTrue(result.successful)
        assertEquals(fixture("exec-result.json"), result.data)
        val reparsed = TaskResult.parseFrom(result.toByteArray())
        assertEquals(Long.MIN_VALUE, reparsed.id)
        assertEquals(15L, reparsed.type)
        assertEquals(9_007_199_254_740_993L, json(result).get("duration_ms").asLong)
        assertFalse(json(result).has("error"))
    }

    @Test
    fun timeoutIsAValidOutcomeAndKeepsExitDurationAndOutput() {
        val result = encoder.encode(-1, 15, ExecResult(
            exitCode = 143, stdout = "partial", stderr = "err", durationMs = 30_000,
            stdoutTruncated = true, timedOut = true
        ))
        assertTrue(result.successful)
        assertEquals(-1L, TaskResult.parseFrom(result.toByteArray()).id)
        val data = json(result)
        assertEquals(143, data.get("exit_code").asInt)
        assertEquals("partial", data.get("stdout").asString)
        assertEquals(30_000L, data.get("duration_ms").asLong)
        assertTrue(data.get("timed_out").asBoolean)
        assertTrue(data.get("stdout_truncated").asBoolean)
        assertFalse(data.has("error"))
    }

    @Test
    fun permissionBusyNotfoundAndValidationFailuresAreStructuredSuccessfulResults() {
        val denied = McpPolicy.denial(15, RemoteCapabilities())!!
        for (type in listOf(15L, 16L, 17L)) {
            for (error in listOf(denied,
                McpBusinessError(McpErrorCode.BUSY, "busy"),
                McpBusinessError(McpErrorCode.NOT_FOUND, "not found"),
                McpBusinessError(McpErrorCode.CAPABILITY_UNAVAILABLE, "capability unavailable"),
                McpBusinessError(McpErrorCode.INVALID_ARGUMENT, "unknown encoding"))) {
                val result = encoder.businessError(12, type, error)
                assertTrue(result.successful)
                assertEquals(error.message, json(result).get("error").asString)
            }
        }
    }

    @Test
    fun oldTask4PermissionSemanticsStayDistinctFromTask15() = runBlocking {
        val old = TaskExecutor.executeTask(
            Task.newBuilder().setId(Long.MIN_VALUE).setType(4).setData("private command").build(),
            RemoteCapabilities()
        )
        val mcp = encoder.businessError(Long.MIN_VALUE, 15, McpPolicy.denial(15, RemoteCapabilities())!!)
        assertFalse(old.successful)
        assertTrue(mcp.successful)
        assertEquals(old.data, json(mcp).get("error").asString)
        assertEquals(old.id, mcp.id)
        assertFalse(silentLogger.messages.any { it.contains("private command") })
    }

    @Test
    fun unsupportedAndTypeFailuresAreFalseAndPreserveUint64BitPatterns() {
        listOf(12L, 13L, 14L, 18L, 19L, 20L, Long.MIN_VALUE, -1L).forEach { type ->
            val result = encoder.unsupported(Long.MIN_VALUE, type)
            val wire = TaskResult.parseFrom(result.toByteArray())
            assertFalse(wire.successful)
            assertEquals(type, wire.type)
            assertEquals(Long.MIN_VALUE, wire.id)
            assertEquals(TaskTypes.unsupportedMessage(type), wire.data)
        }
        val issue = (McpRequestParser.parse(15, """{"env":{"private-key":1}}""") as McpParseResult.Invalid).issue
        val failed = encoder.parseFailure(-1, 15, issue)
        assertFalse(failed.successful)
        assertEquals("invalid MCP request: env: invalid field type", failed.data)
        assertFalse(failed.data.contains("private-key"))
    }

    @Test
    fun fsFixturesUseExpectedEnumsLongsAndSegmentHash() {
        val list = encoder.encode(16, 16, FsListResult(listOf(
            FsEntry("hello", FsEntryType.FILE, 5, "0644", 1_700_000_000),
            FsEntry("link", FsEntryType.SYMLINK, 5, "0777", 1_700_000_001, true, "hello")
        ), total = 2))
        assertEquals(fixture("fs-list-result.json"), list.data)
        val hash = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        val read = encoder.encode(17, 17, FsReadResult("hello", "utf8", 5, hash))
        assertEquals(fixture("fs-read-result.json"), read.data)
        val base64 = encoder.encode(17, 17, FsReadResult("aGVsbG8=", "base64", 5, hash))
        assertEquals(5L, json(base64).get("size").asLong)
        assertEquals(hash, json(base64).get("sha256").asString)
        val boundary = encoder.encode(16, 16, FsListResult(listOf(
            FsEntry("special", FsEntryType.SPECIAL, Long.MAX_VALUE, "0000", Long.MIN_VALUE),
            FsEntry("dir", FsEntryType.DIR, 0, "0755", 0)
        ), total = Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, json(boundary).get("total").asLong)
        assertEquals(Long.MIN_VALUE, json(boundary).getAsJsonArray("entries")[0].asJsonObject.get("mtime").asLong)
    }

    @Test
    fun finalProtobufLimitIncludesEnvelopeAndJsonExpansion() {
        val large = "\u0001".repeat(McpLimits.MAX_EXEC_OUTPUT_BYTES.toInt())
        val result = encoder.encode(Long.MIN_VALUE, 15, ExecResult(
            exitCode = 9, stdout = large, stderr = large, durationMs = 30_123, timedOut = true
        ))
        assertTrue(result.successful)
        assertTrue(result.serializedSize <= McpLimits.MAX_TASK_RESULT_BYTES)
        val wire = TaskResult.parseFrom(result.toByteArray())
        val data = json(wire)
        assertEquals(McpResultEncoder.OUTPUT_TOO_LARGE, data.get("error").asString)
        assertEquals(9, data.get("exit_code").asInt)
        assertEquals(30_123L, data.get("duration_ms").asLong)
        assertTrue(data.get("timed_out").asBoolean)
        assertTrue(data.get("stdout_truncated").asBoolean)
        assertTrue(data.get("stderr_truncated").asBoolean)
        assertEquals("", data.get("stdout").asString)
    }

    @Test
    fun nearLimitAsciiResultIsKeptAndAnOversizedDocumentBecomesAShortError() {
        val retained = "x".repeat(McpLimits.MAX_TASK_RESULT_BYTES - 256)
        val within = encoder.encode(-1, 15, ExecResult(stdout = retained))
        assertEquals(retained, json(within).get("stdout").asString)
        assertFalse(json(within).has("error"))
        assertTrue(within.toByteArray().size <= McpLimits.MAX_TASK_RESULT_BYTES)
        val over = encoder.encode(-1, 15, ExecResult(stdout = "x".repeat(McpLimits.MAX_TASK_RESULT_BYTES)))
        assertEquals(McpResultEncoder.OUTPUT_TOO_LARGE, json(over).get("error").asString)
        assertTrue(over.serializedSize < 1024)
    }

    @Test
    fun oversizeFsResultsCannotLookLikeSuccessfulEmptyDirectoryOrContent() {
        val small = McpResultEncoder(1024)
        val name = "\u0001".repeat(1024)
        val list = small.encode(16, 16, FsListResult(
            listOf(FsEntry(name, FsEntryType.FILE, 7, "0644", 0)), total = 37
        ))
        assertTrue(list.successful)
        assertEquals(37L, json(list).get("total").asLong)
        assertTrue(json(list).get("truncated").asBoolean)
        assertEquals(McpResultEncoder.OUTPUT_TOO_LARGE, json(list).get("error").asString)
        val read = small.encode(17, 17, FsReadResult(name, "base64", 1024, "original-segment-hash"))
        assertTrue(read.successful)
        assertEquals(0L, json(read).get("size").asLong)
        assertEquals("", json(read).get("content").asString)
        assertFalse(json(read).has("sha256"))
        assertTrue(json(read).get("truncated").asBoolean)
        assertEquals(McpResultEncoder.OUTPUT_TOO_LARGE, json(read).get("error").asString)
        assertTrue(read.serializedSize <= 1024)
    }

    @Test
    fun encodingFailureAndWrongResultFamilyReturnFixedLegalJson() {
        val brokenEntries = object : AbstractList<FsEntry>() {
            override val size: Int get() = 1
            override fun get(index: Int): FsEntry = error("private failure details")
        }
        val result = encoder.encode(-1, 16, FsListResult(brokenEntries))
        assertFalse(result.successful)
        assertEquals(McpResultEncoder.INTERNAL_ENCODING_FAILURE, result.data)
        assertNotNull(json(result).get("error"))
        assertFalse(result.data.contains("private"))
        assertEquals(McpResultEncoder.INTERNAL_ENCODING_FAILURE, encoder.encode(1, 17, ExecResult()).data)
    }

    @Test
    fun boundedWriterCountsUtf8AcrossWritesAndStopsBeforeRetainingBeyondBudget() {
        val writer = BoundedUtf8Writer(8)
        writer.write("中", 0, 1)
        writer.write("\uD83D", 0, 1)
        writer.write("\uDE00", 0, 1)
        writer.write('x'.code)
        assertEquals(8L, writer.utf8Bytes)
        assertEquals(8, writer.content().toByteArray(StandardCharsets.UTF_8).size)
        try {
            writer.write('x'.code)
            fail("Expected bounded writer failure")
        } catch (_: java.io.IOException) { }
        assertEquals("中😀x", writer.content())
        val controls = encoder.encode(1, 15, ExecResult(stdout = "\u0000\n\r\t\b\u000c\"\\"))
        assertEquals("\u0000\n\r\t\b\u000c\"\\", json(controls).get("stdout").asString)
    }

    private fun json(result: TaskResult) = JsonParser.parseString(result.data).asJsonObject
    private fun fixture(name: String): String = javaClass.getResourceAsStream("/mcp/r1/$name")!!
        .bufferedReader().use { it.readText().trim() }
}
