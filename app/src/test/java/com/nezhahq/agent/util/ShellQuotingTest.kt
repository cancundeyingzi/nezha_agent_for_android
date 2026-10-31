package com.nezhahq.agent.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shellEscape] is the only thing standing between dashboard-supplied paths and `sh`, and it is
 * called from nine places. It had no test until now: the incident its documentation records — a GPU
 * collector running `cat $path` unquoted because it kept a private copy of the helper — was fixed by
 * consolidating the copies, and the fix shipped without one.
 *
 * The assertions below are deliberately written against the *rendered shell word* rather than the
 * exact escape sequence, so they stay meaningful if the implementation ever switches strategies.
 */
class ShellQuotingTest {

    @Test
    fun `wraps a plain word in single quotes`() {
        assertEquals("'/sdcard/file.txt'", shellEscape("/sdcard/file.txt"))
    }

    @Test
    fun `closes reopens around a single quote`() {
        // The only sequence a single-quoted string cannot contain literally.
        assertEquals("'it'\\''s'", shellEscape("it's"))
        assertEquals("it's", unquote(shellEscape("it's")))
    }

    @Test
    fun `handles consecutive single quotes`() {
        assertEquals("''\\'''\\'''", shellEscape("''"))
        assertEquals("''", unquote(shellEscape("''")))
        assertEquals("a''b", unquote(shellEscape("a''b")))
    }

    @Test
    fun `escaped input cannot re-open a quote by itself`() {
        // A caller-supplied string that already looks like the escape sequence must survive intact
        // rather than terminating the word early.
        val attack = "'\\''"
        assertEquals(attack, unquote(shellEscape(attack)))
    }

    @Test
    fun `keeps newlines inside the quoted word`() {
        val value = "line1\nline2\n"
        assertEquals("'$value'", shellEscape(value))
        assertEquals(value, unquote(shellEscape(value)))
    }

    @Test
    fun `neutralises command substitution and expansion`() {
        listOf(
            "\$(rm -rf /)",
            "`rm -rf /`",
            "\${HOME}",
            "\$HOME",
            "~/secret"
        ).forEach { value ->
            assertEquals("'$value'", shellEscape(value))
            assertEquals(value, unquote(shellEscape(value)))
        }
    }

    @Test
    fun `neutralises command separators`() {
        listOf(
            "a; rm -rf /",
            "a && rm -rf /",
            "a || rm -rf /",
            "a | tee /tmp/x",
            "a > /tmp/x",
            "a & disown"
        ).forEach { value ->
            assertEquals("'$value'", shellEscape(value))
            assertEquals(value, unquote(shellEscape(value)))
        }
    }

    @Test
    fun `empty input stays a single empty word`() {
        // Must not collapse to nothing: an empty argument that disappears shifts every later
        // argument one position left, which silently changes what the command does.
        assertEquals("''", shellEscape(""))
        assertEquals("", unquote(shellEscape("")))
    }

    @Test
    fun `whitespace only input stays one word`() {
        assertEquals("'   '", shellEscape("   "))
        assertEquals("'\t'", shellEscape("\t"))
    }

    @Test
    fun `glob and bracket characters are not expanded`() {
        listOf("*", "?", "[a-z]", "/sys/class/*/name").forEach { value ->
            assertEquals("'$value'", shellEscape(value))
        }
    }

    @Test
    fun `very long input is escaped without truncation`() {
        val value = "a'b".repeat(20_000)
        val escaped = shellEscape(value)

        assertEquals(value, unquote(escaped))
        assertTrue(escaped.startsWith("'") && escaped.endsWith("'"))
    }

    /**
     * Reverses `sh` single-quote rules: strips the outer quotes and turns every `'\''` sequence back
     * into a literal quote. Anything that survives this round trip is what the shell would pass to
     * the program as one argv entry.
     */
    private fun unquote(escaped: String): String {
        if (escaped.length < 2 || escaped.first() != '\'' || escaped.last() != '\'') {
            throw AssertionError("not a single-quoted word: $escaped")
        }

        val body = escaped.substring(1, escaped.length - 1)
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\'') {
                // A quote may only appear as the four-character sequence '\'' : the word is closed,
                // an escaped quote is emitted, and the word is reopened. Anything else would have
                // ended the word and handed the rest of the string to the shell as syntax.
                if (!body.startsWith("'\\''", i)) {
                    throw AssertionError("quote at index $i does not open the '\\'' sequence")
                }
                out.append('\'')
                i += 4
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
