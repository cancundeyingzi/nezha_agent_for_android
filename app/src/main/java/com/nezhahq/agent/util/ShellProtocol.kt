package com.nezhahq.agent.util

import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * How long one command may hold the shared privileged shell.
 *
 * This has to stay well under the dashboard's state-receipt timeout. The metrics loop reads `/proc`
 * through that shell every two seconds, so a command holding it for longer delays the next state
 * report by the same amount, and once the dashboard stops receiving them it drops the whole
 * connection. `RootShell.executeIsolated` exists for work that cannot fit in this budget.
 *
 * `ShellTimeoutBudgetTest` pins the relationship. The constant it compares against —
 * `DashboardSessionWatchdog.STATE_RECEIPT_TIMEOUT_MS` — now sits in this same package; it was moved
 * out of the service layer because it is a plain coroutine timeout with no service semantics.
 */
internal const val DEFAULT_SHELL_TIMEOUT_MS = 5_000L
internal const val MAX_SHELL_OUTPUT_BYTES = 4 * 1024 * 1024

/** Per-command framing values. The token is deliberately restricted to shell-safe ASCII. */
internal data class ShellMarker(val token: String) {
    init {
        require(token.length in 16..128 && token.all(::isShellSafeAscii)) {
            "Shell marker token must be 16..128 shell-safe ASCII characters"
        }
    }

    val prefix: String = "__NEZHA_CMD_DONE_${token}__:"
    val suffix: String = ":__NEZHA_CMD_END_${token}__\n"

    fun completionCommand(): String {
        return "printf '%s%d%s\\n' '$prefix' \"\$?\" '${suffix.dropLast(1)}'"
    }

    private companion object {
        fun isShellSafeAscii(character: Char): Boolean {
            return character in 'a'..'z' ||
                character in 'A'..'Z' ||
                character in '0'..'9' ||
                character == '_' ||
                character == '-'
        }
    }
}

internal data class ShellReadResult(
    val output: String,
    val exitCode: Int,
    val truncated: Boolean
)

internal class ShellProtocolException(message: String) : IOException(message)

/**
 * Reads the byte protocol without ever constructing an unbounded line or output buffer.
 * Bytes beyond [maxOutputBytes] are discarded, but input continues to be drained through
 * the complete marker so the persistent session remains aligned for the next command.
 */
internal class ShellProtocolReader(
    private val maxOutputBytes: Int = MAX_SHELL_OUTPUT_BYTES,
    private val readBufferBytes: Int = 8 * 1024
) {
    init {
        require(maxOutputBytes >= 0)
        require(readBufferBytes > 0)
    }

    fun read(input: InputStream, marker: ShellMarker): ShellReadResult {
        val prefix = marker.prefix.toByteArray(StandardCharsets.US_ASCII)
        val suffix = marker.suffix.toByteArray(StandardCharsets.US_ASCII)
        val failure = buildFailureTable(prefix)
        val output = LimitedByteCollector(maxOutputBytes)
        val buffer = ByteArray(readBufferBytes)

        var matchedPrefixBytes = 0
        var exitCode = 0
        var exitDigits = 0
        var firstExitDigit = -1
        var matchedSuffixBytes = -1

        while (true) {
            val count = input.read(buffer)
            if (count < 0) {
                throw if (matchedSuffixBytes >= 0 || exitDigits > 0) {
                    ShellProtocolException("EOF inside shell completion marker")
                } else {
                    ShellProtocolException("EOF before shell completion marker")
                }
            }
            if (count == 0) continue

            for (index in 0 until count) {
                val byte = buffer[index]

                if (matchedSuffixBytes >= 0) {
                    if (byte != suffix[matchedSuffixBytes]) {
                        throw ShellProtocolException("Malformed shell completion marker suffix")
                    }
                    matchedSuffixBytes++
                    if (matchedSuffixBytes == suffix.size) {
                        return ShellReadResult(output.toUtf8String(), exitCode, output.truncated)
                    }
                    continue
                }

                if (matchedPrefixBytes == prefix.size) {
                    val unsigned = byte.toInt() and 0xff
                    if (unsigned in '0'.code..'9'.code) {
                        if (exitDigits == 3) {
                            throw ShellProtocolException("Shell exit code has too many digits")
                        }
                        val digit = unsigned - '0'.code
                        if (exitDigits == 0) firstExitDigit = digit
                        exitCode = exitCode * 10 + digit
                        exitDigits++
                        continue
                    }

                    if (exitDigits == 0 || byte != suffix[0]) {
                        throw ShellProtocolException("Shell completion marker has an invalid exit code")
                    }
                    if ((exitDigits > 1 && firstExitDigit == 0) || exitCode !in 0..255) {
                        throw ShellProtocolException("Shell completion marker exit code is out of range")
                    }
                    matchedSuffixBytes = 1
                    continue
                }

                while (matchedPrefixBytes > 0 && byte != prefix[matchedPrefixBytes]) {
                    val fallback = failure[matchedPrefixBytes - 1]
                    output.append(prefix, 0, matchedPrefixBytes - fallback)
                    matchedPrefixBytes = fallback
                }

                if (byte == prefix[matchedPrefixBytes]) {
                    matchedPrefixBytes++
                } else {
                    output.append(byte)
                }
            }
        }
    }

    private fun buildFailureTable(pattern: ByteArray): IntArray {
        val failure = IntArray(pattern.size)
        var matched = 0
        for (index in 1 until pattern.size) {
            while (matched > 0 && pattern[index] != pattern[matched]) {
                matched = failure[matched - 1]
            }
            if (pattern[index] == pattern[matched]) matched++
            failure[index] = matched
        }
        return failure
    }

    private class LimitedByteCollector(private val limit: Int) {
        private var bytes = ByteArray(minOf(8 * 1024, limit))
        private var size = 0
        var truncated: Boolean = false
            private set

        fun append(byte: Byte) {
            if (size == limit) {
                truncated = true
                return
            }
            ensureCapacity(size + 1)
            bytes[size++] = byte
        }

        fun append(source: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            val retained = minOf(length, limit - size)
            if (retained > 0) {
                ensureCapacity(size + retained)
                source.copyInto(bytes, size, offset, offset + retained)
                size += retained
            }
            if (retained < length) truncated = true
        }

        fun toUtf8String(): String = String(bytes, 0, size, StandardCharsets.UTF_8)

        private fun ensureCapacity(required: Int) {
            if (required <= bytes.size) return
            var capacity = maxOf(1, bytes.size)
            while (capacity < required) capacity = minOf(limit, capacity * 2)
            bytes = bytes.copyOf(capacity)
        }
    }
}
