package com.nezhahq.agent.mcp

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.nezhahq.agent.core.mcp.ExecRequest
import com.nezhahq.agent.core.mcp.FsListRequest
import com.nezhahq.agent.core.mcp.FsReadRequest
import com.nezhahq.agent.core.mcp.McpLimits
import com.nezhahq.agent.core.mcp.McpPolicy
import com.nezhahq.agent.core.mcp.McpRequest
import com.nezhahq.agent.core.mcp.McpTaskFamily
import java.io.IOException
import java.io.StringReader

/** Safe field labels only. Never expose a JsonReader path, map key, value or exception message. */
enum class McpField(val wireName: String) {
    CMD("cmd"), ARGS("args"), CWD("cwd"), ENV("env"), STDIN("stdin"),
    TIMEOUT_SECONDS("timeout_seconds"), MAX_OUTPUT_BYTES("max_output_bytes"),
    PATH("path"), SHOW_HIDDEN("show_hidden"), OFFSET("offset"), LENGTH("length"),
    ENCODING("encoding")
}

enum class McpParseCategory(val description: String) {
    INVALID_JSON("invalid JSON syntax"),
    WRONG_TOP_LEVEL("expected a JSON object"),
    INVALID_TYPE("invalid field type"),
    INVALID_INTEGER("expected an integer without a fraction or exponent"),
    INTEGER_OUT_OF_RANGE("integer out of range"),
    DUPLICATE_FIELD("duplicate field"),
    REQUEST_TOO_LARGE("request exceeds byte limit"),
    NESTING_TOO_DEEP("JSON nesting exceeds depth limit"),
    TRAILING_JSON("trailing JSON is not allowed")
}

data class McpParseIssue(val category: McpParseCategory, val field: McpField? = null) {
    val safeDescription: String get() = this.field?.let {
        "${it.wireName}: ${category.description}"
    } ?: category.description
}

sealed interface McpParseResult {
    /** Raw range-checked DTO; run McpRequestRules.prepare before execution. */
    data class Parsed(val request: McpRequest, val utf8Bytes: Long) : McpParseResult
    data class Invalid(val issue: McpParseIssue) : McpParseResult
    data object Unsupported : McpParseResult
}

/** Strict streaming adapters. No Gson object tree, reflection, Double conversion or Android API. */
object McpRequestParser {
    private const val GSON_NUMBER_BUFFER_CHARS = 1024

    fun parse(taskType: Long, data: String): McpParseResult {
        val family = McpPolicy.family(taskType) ?: return McpParseResult.Unsupported
        val bytes = utf8BytesUpTo(data, McpLimits.MAX_REQUEST_BYTES)
        if (bytes > McpLimits.MAX_REQUEST_BYTES) {
            return McpParseResult.Invalid(McpParseIssue(McpParseCategory.REQUEST_TOO_LARGE))
        }
        var field: McpField? = null
        return try {
            // Gson 2.10.1's non-lenient reader still accepts raw controls, some non-JSON
            // escapes and case-insensitive keywords. Check lexemes before streaming parsing.
            val readerInput = checkedReaderInput(data)
            JsonReader(StringReader(readerInput)).use { reader ->
                reader.isLenient = false
                if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                    fail(McpParseCategory.WRONG_TOP_LEVEL)
                }
                val values = RequestValues()
                val seen = mutableSetOf<McpField>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    field = knownField(family, name)
                    val known = field
                    if (known == null) {
                        skipUnknown(reader, 2)
                    } else {
                        if (!seen.add(known)) fail(McpParseCategory.DUPLICATE_FIELD, known)
                        when (known) {
                            McpField.CMD -> values.cmd = string(reader, known)
                            McpField.ARGS -> values.args = strings(reader, known)
                            McpField.CWD -> values.cwd = string(reader, known)
                            McpField.ENV -> values.env = stringMap(reader, known)
                            McpField.STDIN -> values.stdin = string(reader, known)
                            McpField.TIMEOUT_SECONDS -> values.timeout = integer(reader, known, true)
                            McpField.MAX_OUTPUT_BYTES -> values.output = integer(reader, known, true)
                            McpField.PATH -> values.path = string(reader, known)
                            McpField.SHOW_HIDDEN -> {
                                expect(reader, JsonToken.BOOLEAN, known)
                                values.showHidden = reader.nextBoolean()
                            }
                            McpField.OFFSET -> values.offset = integer(reader, known, false)
                            McpField.LENGTH -> values.length = integer(reader, known, false)
                            McpField.ENCODING -> values.encoding = string(reader, known)
                        }
                    }
                }
                reader.endObject()
                field = null
                // A strict JsonReader may throw rather than return the second token. Once the
                // root ended, either case is specifically an unwanted trailing document.
                try {
                    if (reader.peek() != JsonToken.END_DOCUMENT) fail(McpParseCategory.TRAILING_JSON)
                } catch (_: IOException) {
                    fail(McpParseCategory.TRAILING_JSON)
                }
                McpParseResult.Parsed(values.request(family), bytes)
            }
        } catch (failure: ParseFailure) {
            McpParseResult.Invalid(failure.issue)
        } catch (_: IOException) {
            McpParseResult.Invalid(McpParseIssue(McpParseCategory.INVALID_JSON, field))
        } catch (_: IllegalStateException) {
            McpParseResult.Invalid(McpParseIssue(McpParseCategory.INVALID_JSON, field))
        } catch (_: NumberFormatException) {
            McpParseResult.Invalid(McpParseIssue(McpParseCategory.INTEGER_OUT_OF_RANGE, field))
        }
    }

    private fun knownField(family: McpTaskFamily, name: String): McpField? = when (family) {
        McpTaskFamily.EXEC -> when (name) {
            "cmd" -> McpField.CMD
            "args" -> McpField.ARGS
            "cwd" -> McpField.CWD
            "env" -> McpField.ENV
            "stdin" -> McpField.STDIN
            "timeout_seconds" -> McpField.TIMEOUT_SECONDS
            "max_output_bytes" -> McpField.MAX_OUTPUT_BYTES
            else -> null
        }
        McpTaskFamily.FS_LIST -> when (name) {
            "path" -> McpField.PATH
            "show_hidden" -> McpField.SHOW_HIDDEN
            else -> null
        }
        McpTaskFamily.FS_READ -> when (name) {
            "path" -> McpField.PATH
            "offset" -> McpField.OFFSET
            "length" -> McpField.LENGTH
            "encoding" -> McpField.ENCODING
            else -> null
        }
    }

    private fun string(reader: JsonReader, field: McpField): String {
        expect(reader, JsonToken.STRING, field)
        return reader.nextString()
    }

    private fun strings(reader: JsonReader, field: McpField): List<String> {
        expect(reader, JsonToken.BEGIN_ARRAY, field)
        val result = mutableListOf<String>()
        reader.beginArray()
        while (reader.hasNext()) result += string(reader, field)
        reader.endArray()
        return result
    }

    private fun stringMap(reader: JsonReader, field: McpField): Map<String, String> {
        expect(reader, JsonToken.BEGIN_OBJECT, field)
        val result = linkedMapOf<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (result.containsKey(key)) fail(McpParseCategory.DUPLICATE_FIELD, field)
            result[key] = string(reader, field)
        }
        reader.endObject()
        return result
    }

    private fun integer(reader: JsonReader, field: McpField, unsigned32: Boolean): Long {
        expect(reader, JsonToken.NUMBER, field)
        val literal = reader.nextString()
        if (!literal.matches(Regex("-?(0|[1-9][0-9]*)"))) {
            fail(McpParseCategory.INVALID_INTEGER, field)
        }
        val value = literal.toLongOrNull() ?: fail(McpParseCategory.INTEGER_OUT_OF_RANGE, field)
        if (unsigned32 && (literal.startsWith('-') || value > McpLimits.UINT32_MAX)) {
            fail(McpParseCategory.INTEGER_OUT_OF_RANGE, field)
        }
        return value
    }

    private fun expect(reader: JsonReader, token: JsonToken, field: McpField) {
        if (reader.peek() != token) fail(McpParseCategory.INVALID_TYPE, field)
    }

    private fun skipUnknown(reader: JsonReader, depth: Int) {
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                checkDepth(depth)
                reader.beginArray()
                while (reader.hasNext()) skipUnknown(reader, depth + 1)
                reader.endArray()
            }
            JsonToken.BEGIN_OBJECT -> {
                checkDepth(depth)
                reader.beginObject()
                while (reader.hasNext()) {
                    reader.nextName()
                    skipUnknown(reader, depth + 1)
                }
                reader.endObject()
            }
            else -> reader.skipValue()
        }
    }

    private fun checkDepth(depth: Int) {
        if (depth > McpLimits.MAX_JSON_DEPTH) fail(McpParseCategory.NESTING_TOO_DEEP)
    }

    /**
     * Validate the complete original lexemes. Gson 2.10.1's 1024-char number buffer cannot peek a
     * longer valid number in strict mode. Only the reader copy replaces those tokens with null:
     * unknown values can then be skipped, while every known field rejects null explicitly. Raw
     * UTF-8 accounting above always uses [data], never this shorter adapter input.
     */
    private fun checkedReaderInput(data: String): String {
        var quoted = false
        var index = 0
        var adapted: StringBuilder? = null
        var copiedUntil = 0
        while (index < data.length) {
            val char = data[index++]
            if (char == '"') {
                quoted = !quoted
            } else if (quoted) {
                if (char < ' ') fail(McpParseCategory.INVALID_JSON)
                if (char == '\\') {
                    if (index >= data.length) fail(McpParseCategory.INVALID_JSON)
                    when (data[index++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> repeat(4) {
                            if (index >= data.length || data[index++] !in "0123456789abcdefABCDEF") {
                                fail(McpParseCategory.INVALID_JSON)
                            }
                        }
                        else -> fail(McpParseCategory.INVALID_JSON)
                    }
                }
            } else if (char !in "{}[],: \t\r\n") {
                val start = index - 1
                while (index < data.length && data[index] !in "{}[],:\" \t\r\n") index++
                val end = index
                val keyword = when (char) {
                    't' -> "true"
                    'f' -> "false"
                    'n' -> "null"
                    else -> null
                }
                if (keyword != null) {
                    if (end - start != keyword.length || !data.regionMatches(start, keyword, 0, keyword.length)) {
                        fail(McpParseCategory.INVALID_JSON)
                    }
                } else if (!isJsonNumber(data, start, end)) {
                    fail(McpParseCategory.INVALID_JSON)
                } else if (end - start >= GSON_NUMBER_BUFFER_CHARS) {
                    val output = adapted ?: StringBuilder(minOf(data.length, 1024)).also { adapted = it }
                    output.append(data, copiedUntil, start).append("null")
                    copiedUntil = end
                }
            }
        }
        return adapted?.append(data, copiedUntil, data.length)?.toString() ?: data
    }

    private fun isJsonNumber(data: String, start: Int, end: Int): Boolean {
        var index = start
        if (index < end && data[index] == '-') index++
        if (index >= end) return false
        if (data[index] == '0') {
            index++
        } else {
            if (data[index] !in '1'..'9') return false
            while (index < end && data[index] in '0'..'9') index++
        }
        if (index < end && data[index] == '.') {
            index++
            val fractionStart = index
            while (index < end && data[index] in '0'..'9') index++
            if (index == fractionStart) return false
        }
        if (index < end && data[index] in "eE") {
            index++
            if (index < end && data[index] in "+-") index++
            val exponentStart = index
            while (index < end && data[index] in '0'..'9') index++
            if (index == exponentStart) return false
        }
        return index == end
    }

    private fun fail(category: McpParseCategory, field: McpField? = null): Nothing =
        throw ParseFailure(McpParseIssue(category, field))

    private class ParseFailure(val issue: McpParseIssue) : RuntimeException()

    private class RequestValues {
        var cmd = ""
        var args: List<String> = emptyList()
        var cwd: String? = null
        var env: Map<String, String> = emptyMap()
        var stdin = ""
        var timeout = McpLimits.DEFAULT_EXEC_TIMEOUT_SECONDS
        var output = McpLimits.DEFAULT_EXEC_MAX_OUTPUT_BYTES
        var path = ""
        var showHidden = false
        var offset = 0L
        var length = McpLimits.MAX_FS_READ_BYTES
        var encoding = "utf8"

        fun request(family: McpTaskFamily): McpRequest = when (family) {
            McpTaskFamily.EXEC -> ExecRequest(cmd, args, cwd, env, stdin, timeout, output)
            McpTaskFamily.FS_LIST -> FsListRequest(path, showHidden)
            McpTaskFamily.FS_READ -> FsReadRequest(path, offset, length, encoding)
        }
    }
}

/** Matches the UTF-8 replacement behavior used by Protobuf for isolated Java surrogates. */
internal fun utf8BytesUpTo(value: CharSequence, limit: Long): Long {
    var bytes = 0L
    var previousHigh = false
    for (char in value) {
        bytes += utf8ByteIncrement(char, previousHigh)
        if (bytes > limit) return bytes
        previousHigh = char.isHighSurrogate()
    }
    return bytes
}

internal fun utf8ByteIncrement(char: Char, previousHigh: Boolean): Int = when {
    char.isLowSurrogate() && previousHigh -> 3 // high surrogate already counted one replacement byte
    char.isSurrogate() -> 1
    char.code < 0x80 -> 1
    char.code < 0x800 -> 2
    else -> 3
}
