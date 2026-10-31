package com.nezhahq.agent.mcp

import com.nezhahq.agent.core.mcp.*
import com.nezhahq.agent.core.task.TaskTypes
import org.junit.Assert.*
import org.junit.Test
import java.nio.charset.StandardCharsets

class McpRequestParserTest {
    @Test
    fun fixedExecFixtureKeepsArraysStringsAndUint32BeforeBusinessClamping() {
        val request = parsed(TaskTypes.EXEC, fixture("exec-request.json")) as ExecRequest
        assertEquals("/system/bin/sh", request.cmd)
        assertEquals(listOf("-c", "printf '%s' \"\$GREETING\""), request.args)
        assertEquals("/data/local/tmp", request.cwd)
        assertEquals(mapOf("GREETING" to "你好😀"), request.env)
        assertEquals("input\n\u0001", request.stdin)
        assertEquals(McpLimits.UINT32_MAX, request.timeoutSeconds)
        assertEquals(McpLimits.UINT32_MAX, request.maxOutputBytes)
        val prepared = (McpRequestRules.prepare(request) as McpValidation.Valid).request as ExecRequest
        assertEquals(300L, prepared.timeoutSeconds)
        assertEquals(1_048_576L, prepared.maxOutputBytes)
    }

    @Test
    fun allRequestDefaultsRemainTypedAndZeroIsHandledByBusinessRules() {
        assertEquals(ExecRequest("x"), parsed(15, """{"cmd":"x"}"""))
        assertEquals(FsListRequest("/"), parsed(16, """{"path":"/"}"""))
        assertEquals(FsReadRequest("/a"), parsed(17, """{"path":"/a"}"""))
        val zero = parsed(15, """{"cmd":"x","timeout_seconds":0,"max_output_bytes":0}""") as ExecRequest
        assertEquals(0L, zero.timeoutSeconds)
        assertEquals(ExecRequest("x"), (McpRequestRules.prepare(zero) as McpValidation.Valid).request)
        val readZero = parsed(17, """{"path":"/a","length":0,"encoding":""}""")
        assertEquals(FsReadRequest("/a"), (McpRequestRules.prepare(readZero) as McpValidation.Valid).request)
        assertTrue(McpRequestRules.prepare(parsed(15, "{}")) is McpValidation.Invalid)
        assertTrue(McpRequestRules.prepare(parsed(16, "{}")) is McpValidation.Invalid)
    }

    @Test
    fun everyKnownFieldRejectsNullAndCoercibleWrongTypes() {
        val cases = listOf(
            Triple(15L, "cmd", "5"), Triple(15L, "args", "\"x\""),
            Triple(15L, "cwd", "false"), Triple(15L, "env", "[]"),
            Triple(15L, "stdin", "5"), Triple(15L, "timeout_seconds", "\"30\""),
            Triple(15L, "max_output_bytes", "true"), Triple(16L, "path", "5"),
            Triple(16L, "show_hidden", "\"false\""), Triple(17L, "path", "{}"),
            Triple(17L, "offset", "\"0\""), Triple(17L, "length", "false"),
            Triple(17L, "encoding", "5")
        )
        for ((type, field, wrong) in cases) {
            listOf("null", wrong).forEach { value ->
                val issue = invalid(type, "{\"$field\":$value}")
                assertEquals(field, McpParseCategory.INVALID_TYPE, issue.category)
                assertEquals(field, issue.field?.wireName)
            }
        }
        listOf("""{"args":[null]}""", """{"args":[1]}""", """{"env":{"k":1}}""",
            """{"env":{"k":null}}""").forEach { invalid(15, it) }
    }

    @Test
    fun uint32IsStrictlyParsedBeforeAnyClamp() {
        for (field in listOf("timeout_seconds", "max_output_bytes")) {
            for (literal in listOf("-1", "-0", "1.0", "1e2", "1E2", "4294967296",
                "9223372036854775807", "18446744073709551616")) {
                invalid(15, "{\"cmd\":\"x\",\"$field\":$literal}")
            }
            val result = parsed(15, "{\"cmd\":\"x\",\"$field\":4294967295}") as ExecRequest
            assertEquals(McpLimits.UINT32_MAX, if (field == "timeout_seconds") result.timeoutSeconds else result.maxOutputBytes)
        }
        invalid(15, """{"cmd":"x","timeout_seconds":01}""")
        invalid(15, """{"cmd":"x","timeout_seconds":+1}""")
    }

    @Test
    fun int64BoundariesAreExactAndNegativeFilePositionsAreBusinessErrors() {
        val maximum = parsed(17, """{"path":"/a","offset":9223372036854775807,"length":9007199254740993}""") as FsReadRequest
        assertEquals(Long.MAX_VALUE, maximum.offset)
        assertEquals(9_007_199_254_740_993L, maximum.length)
        assertTrue(McpRequestRules.prepare(maximum) is McpValidation.Valid)
        val minimum = parsed(17, """{"path":"/a","offset":-9223372036854775808,"length":-1}""") as FsReadRequest
        assertEquals(Long.MIN_VALUE, minimum.offset)
        assertTrue(McpRequestRules.prepare(minimum) is McpValidation.Invalid)
        listOf("9223372036854775808", "-9223372036854775809", "0.0", "1e0").forEach {
            invalid(17, "{\"path\":\"/a\",\"offset\":$it}")
        }
    }

    @Test
    fun unknownEncodingIsNotAParseErrorAndDoesNotEchoItsValue() {
        val request = parsed(17, """{"path":"/a","encoding":"secret-value"}""")
        val error = (McpRequestRules.prepare(request) as McpValidation.Invalid).error
        assertEquals("unknown encoding", error.message)
        assertFalse(error.message.contains("secret-value"))
    }

    @Test
    fun unknownValuesAreSkippedWithARealContainerDepthLimit() {
        val future = """{"cmd":"x","future":{"null":null,"array":[true,1.5,1e2,-2.5E-2,{},[]]},"path":42}"""
        assertEquals(ExecRequest("x"), parsed(15, future))
        val within = "{\"cmd\":\"x\",\"future\":" + "[".repeat(63) + "0" + "]".repeat(63) + "}"
        assertEquals(ExecRequest("x"), parsed(15, within))
        val tooDeep = "{\"cmd\":\"x\",\"future\":" + "[".repeat(64) + "0" + "]".repeat(64) + "}"
        assertEquals(McpParseCategory.NESTING_TOO_DEEP, invalid(15, tooDeep).category)
    }

    @Test
    fun unknownLongNumbersAndExponentsAreSkippedAtAnyDepthWithoutChangingByteAccounting() {
        for (length in listOf(1023, 1024, 1025, 20_000)) {
            val integer = "1".repeat(length)
            val exponent = "1e+" + "9".repeat(length)
            val data = "{\"cmd\":\"中文\",\"future\":$integer,\"nested\":[{\"value\":$exponent},$integer]}"
            val result = McpRequestParser.parse(15, data) as McpParseResult.Parsed
            assertEquals(ExecRequest("中文"), result.request)
            assertEquals(data.toByteArray(StandardCharsets.UTF_8).size.toLong(), result.utf8Bytes)
            assertEquals(FsListRequest("/a"), parsed(16, "{\"path\":\"/a\",\"future\":$integer}"))
            assertEquals(FsReadRequest("/a"), parsed(17, "{\"path\":\"/a\",\"future\":$exponent}"))
        }
        val prefix = "{\"cmd\":\"x\",\"future\":"
        val exact = prefix + "1".repeat(McpLimits.MAX_REQUEST_BYTES.toInt() - prefix.length - 1) + "}"
        val result = McpRequestParser.parse(15, exact) as McpParseResult.Parsed
        assertEquals(McpLimits.MAX_REQUEST_BYTES, result.utf8Bytes)
        assertEquals(ExecRequest("x"), result.request)
        assertEquals(McpParseCategory.REQUEST_TOO_LARGE, invalid(15, exact + " ").category)
    }

    @Test
    fun longNumberAdapterCannotTurnKnownFieldsIntoAcceptedDefaultsOrStrings() {
        val cases = listOf(
            15L to "cmd", 15L to "args", 15L to "cwd", 15L to "env", 15L to "stdin",
            15L to "timeout_seconds", 15L to "max_output_bytes", 16L to "path",
            16L to "show_hidden", 17L to "path", 17L to "offset", 17L to "length", 17L to "encoding"
        )
        for (number in listOf("1".repeat(1024), "1e" + "9".repeat(1024))) {
            for ((type, field) in cases) {
                assertEquals(field, invalid(type, "{\"$field\":$number}").field?.wireName)
            }
            assertEquals(McpField.ARGS, invalid(15, "{\"cmd\":\"x\",\"args\":[$number]}").field)
            assertEquals(McpField.ENV, invalid(15, "{\"cmd\":\"x\",\"env\":{\"key\":$number}}").field)
        }
        val digits = "1".repeat(1024)
        assertEquals(ExecRequest(digits, listOf(digits), env = mapOf("key" to digits)), parsed(15,
            "{\"cmd\":\"$digits\",\"args\":[\"$digits\"],\"env\":{\"key\":\"$digits\"}}"
        ))
    }

    @Test
    fun longNumberAdapterStillRejectsInvalidLexemesStructureAndTrailingDocuments() {
        val digits = "1".repeat(1024)
        listOf("0$digits", "${digits}e", "${digits}.", "${digits}e+", "${digits}x",
            "1e${digits}+").forEach {
            assertEquals(McpParseCategory.INVALID_JSON, invalid(15, "{\"cmd\":\"x\",\"future\":$it}").category)
        }
        val valid = "{\"cmd\":\"x\",\"future\":$digits}"
        listOf("$valid {}", "$valid $digits").forEach {
            assertEquals(McpParseCategory.TRAILING_JSON, invalid(15, it).category)
        }
        invalid(15, "{\"cmd\":\"x\",\"future\":[$digits null]}")
        invalid(15, "{\"cmd\":\"x\",\"future\":{$digits:null}}")
        val tooDeep = "{\"cmd\":\"x\",\"future\":" + "[".repeat(64) + digits + "]".repeat(64) + "}"
        assertEquals(McpParseCategory.NESTING_TOO_DEEP, invalid(15, tooDeep).category)
    }

    @Test
    fun repeatedKnownFieldsAndEnvKeysAreRejectedButUnknownFieldsAreForwardCompatible() {
        assertEquals(McpParseCategory.DUPLICATE_FIELD, invalid(15, """{"cmd":"a","cmd":"b"}""").category)
        assertEquals(McpParseCategory.DUPLICATE_FIELD, invalid(15, """{"cmd":"a","c\u006dd":"b"}""").category)
        assertEquals(McpField.ENV, invalid(15, """{"env":{"k":"a","k":"b"}}""").field)
        assertEquals(ExecRequest("x"), parsed(15, """{"cmd":"x","future":1,"future":2}"""))
    }

    @Test
    fun topLevelSyntaxAndTrailingDocumentsAreStrict() {
        listOf("null", "[]", "true", "1", "\"x\"").forEach {
            assertEquals(McpParseCategory.WRONG_TOP_LEVEL, invalid(15, it).category)
        }
        listOf("", "{", "{cmd:'x'}", "{\"cmd\":\"x\",}", "/*comment*/{\"cmd\":\"x\"}",
            "{\"cmd\":\"a\nb\"}", "{\"cmd\":\"a\\'b\"}", "{\"cmd\":\"a\\\nb\"}").forEach {
            invalid(15, it)
        }
        listOf("TRUE", "TrUe", "FALSE", "NULL").forEach {
            invalid(16, "{\"path\":\"/a\",\"show_hidden\":$it}")
            invalid(15, "{\"cmd\":\"x\",\"unknown\":$it}")
        }
        listOf("{} {}", "{} null").forEach {
            assertEquals(McpParseCategory.TRAILING_JSON, invalid(15, it).category)
        }
        listOf("{} trailing", "{} //comment").forEach { invalid(15, it) }
        assertEquals(ExecRequest("x"), parsed(15, " \r\n{\"cmd\":\"x\"}\t"))
    }

    @Test
    fun escapedUnicodeControlsAndUtf8RequestBytesArePreserved() {
        val data = """{"cmd":"中文😀","stdin":"\u0000\b\f\n\r\t\"\\\/"}"""
        val parsed = McpRequestParser.parse(15, data) as McpParseResult.Parsed
        val request = parsed.request as ExecRequest
        assertEquals("中文😀", request.cmd)
        assertEquals("\u0000\b\u000c\n\r\t\"\\/", request.stdin)
        assertEquals(data.toByteArray(StandardCharsets.UTF_8).size.toLong(), parsed.utf8Bytes)
        val prefix = "{\"cmd\":\"x\",\"stdin\":\""
        val suffix = "\"}"
        val exact = prefix + "a".repeat(McpLimits.MAX_REQUEST_BYTES.toInt() - prefix.length - suffix.length) + suffix
        assertTrue(McpRequestParser.parse(15, exact) is McpParseResult.Parsed)
        assertEquals(McpParseCategory.REQUEST_TOO_LARGE, invalid(15, exact + " ").category)
        val multi = prefix + "中".repeat(400_000) + suffix
        assertTrue(multi.length < McpLimits.MAX_REQUEST_BYTES)
        assertEquals(McpParseCategory.REQUEST_TOO_LARGE, invalid(15, multi).category)
    }

    @Test
    fun errorsExposeOnlyStaticFieldAndCategoryLabels() {
        val raw = """{"cmd":"private command","env":{"private-key":"private value","another":1}}"""
        val issue = invalid(15, raw)
        assertEquals("env: invalid field type", issue.safeDescription)
        assertFalse(issue.safeDescription.contains("private"))
        listOf(4L, 12L, 13L, 14L, 18L, 19L, 20L, Long.MIN_VALUE).forEach {
            assertSame(McpParseResult.Unsupported, McpRequestParser.parse(it, "not JSON"))
        }
    }

    @Test
    fun fixedFsFixturesMatchWireFieldNames() {
        assertEquals(FsListRequest("/data/local/tmp", true), parsed(16, fixture("fs-list-request.json")))
        assertEquals(FsReadRequest("/data/local/tmp/hello", 2, 5, "base64"), parsed(17, fixture("fs-read-request.json")))
    }

    private fun parsed(type: Long, data: String): McpRequest =
        (McpRequestParser.parse(type, data) as McpParseResult.Parsed).request

    private fun invalid(type: Long, data: String): McpParseIssue {
        val result = McpRequestParser.parse(type, data)
        assertTrue("Expected invalid request", result is McpParseResult.Invalid)
        return (result as McpParseResult.Invalid).issue
    }

    private fun fixture(name: String): String = javaClass.getResourceAsStream("/mcp/r1/$name")!!
        .bufferedReader().use { it.readText().trim() }
}
