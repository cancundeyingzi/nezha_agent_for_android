package com.nezhahq.agent.executor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The directory listing protocol spends one byte on each entry's name length, so names really are
 * capped at 255 *bytes*. The cap itself is fine; cutting at an arbitrary byte was not, because a
 * 3-byte CJK character straddling the limit reached the dashboard as an undecodable fragment.
 */
class Utf8TruncationTest {

    /** Matches `FileManager.MAX_ENTRY_NAME_BYTES`. */
    private val protocolLimit = 255

    @Test
    fun `names within the limit are untouched`() {
        assertArrayEquals("report.txt".toByteArray(), truncateUtf8("report.txt", protocolLimit))
        // 85 three-byte characters land exactly on the limit.
        val exact = "文".repeat(85)
        assertEquals(protocolLimit, exact.toByteArray().size)
        assertArrayEquals(exact.toByteArray(), truncateUtf8(exact, protocolLimit))
    }

    @Test
    fun `ascii is cut at exactly the limit`() {
        val truncated = truncateUtf8("a".repeat(300), protocolLimit)

        assertEquals(protocolLimit, truncated.size)
        assertEquals("a".repeat(protocolLimit), String(truncated, Charsets.UTF_8))
    }

    @Test
    fun `a multibyte character straddling the limit is dropped whole`() {
        // "a" + 85 CJK characters = 256 bytes, so the last character crosses the boundary.
        val name = "a" + "文".repeat(85)
        assertEquals(256, name.toByteArray().size)

        val truncated = truncateUtf8(name, protocolLimit)

        assertEquals(253, truncated.size)
        assertEquals("a" + "文".repeat(84), String(truncated, Charsets.UTF_8))
        assertNoReplacementCharacters(truncated)
    }

    @Test
    fun `a surrogate pair is never split`() {
        // U+1F600 is four UTF-8 bytes; cutting inside it would also cut inside a Kotlin surrogate
        // pair, which is the case a naive character-count truncation still gets wrong.
        val truncated = truncateUtf8("xxx😀", maxBytes = 5)

        assertArrayEquals("xxx".toByteArray(), truncated)
        assertNoReplacementCharacters(truncated)
    }

    @Test
    fun `two byte characters are handled as well`() {
        // "é" is two bytes; with an odd limit the last one has to go.
        val truncated = truncateUtf8("é".repeat(4), maxBytes = 5)

        assertEquals(4, truncated.size)
        assertEquals("é".repeat(2), String(truncated, Charsets.UTF_8))
    }

    @Test
    fun `a name that is entirely one oversized character becomes empty`() {
        // Better an empty name than a byte the dashboard cannot decode.
        assertArrayEquals(ByteArray(0), truncateUtf8("文", maxBytes = 2))
    }

    @Test
    fun `non positive limits yield nothing instead of indexing out of bounds`() {
        assertArrayEquals(ByteArray(0), truncateUtf8("abc", maxBytes = 0))
        assertArrayEquals(ByteArray(0), truncateUtf8("abc", maxBytes = -1))
        // An empty name is already within any limit.
        assertArrayEquals(ByteArray(0), truncateUtf8("", maxBytes = 0))
    }

    @Test
    fun `the result always fits the protocol length byte and decodes cleanly`() {
        // Mixed-width names at every offset around the limit: each must stay decodable and fit.
        for (padding in 0..6) {
            val name = "a".repeat(padding) + "文字😀".repeat(40)
            val truncated = truncateUtf8(name, protocolLimit)

            assertTrue("length must fit one byte", truncated.size <= protocolLimit)
            assertNoReplacementCharacters(truncated)
            assertTrue(
                "truncation must keep a prefix of the original",
                name.startsWith(String(truncated, Charsets.UTF_8))
            )
        }
    }

    private fun assertNoReplacementCharacters(bytes: ByteArray) {
        // U+FFFD is what the decoder substitutes for a broken sequence, i.e. the exact symptom.
        assertFalse(
            "truncation split a character: ${bytes.toList()}",
            String(bytes, Charsets.UTF_8).contains(Char(0xFFFD))
        )
    }
}
