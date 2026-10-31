package com.nezhahq.agent.executor

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The software line discipline stands in for a PTY that Android will not give us, so every rule it
 * implements — echo, erase, interrupt, ESC filtering, the overflow cut-off — is a decision no kernel
 * is double-checking. These tests drive the state machine directly; [TerminalManager] only executes
 * the effects it returns.
 */
class LineDisciplineTest {

    private val prompt = "nezha:/ $ "

    // ── ESC / CSI 序列 ────────────────────────────────────────────────────────

    @Test
    fun `arrow keys are swallowed whole`() {
        val discipline = discipline()

        // ESC [ A — nothing may reach the line buffer or the terminal: without a PTY there is no
        // cursor to move, and letting the bytes through would put "[A" into the command.
        assertEquals(noEffects, discipline.type("$ESC[A"))
        assertEquals("", discipline.currentLine)
    }

    @Test
    fun `parameterised CSI sequences are swallowed until their final byte`() {
        val discipline = discipline()

        // ESC [ 1 ; 5 C (ctrl+right): the parameter bytes are all below 0x40, only 'C' terminates.
        assertEquals(noEffects, discipline.type("$ESC[1;5C"))
        assertEquals("", discipline.currentLine)

        // The very next ordinary byte must be handled normally again.
        assertEquals(listOf(echo("a")), discipline.type("a"))
        assertEquals("a", discipline.currentLine)
    }

    @Test
    fun `ESC followed by a non-CSI byte only consumes that byte`() {
        val discipline = discipline()

        // Only CSI is modelled; SS3 sequences (ESC O P) are not, so the byte after ESC O is treated
        // as ordinary input. Asserted so a future PTY-less rework notices it is a deliberate limit.
        assertEquals(noEffects, discipline.type("${ESC}O"))
        assertEquals(listOf(echo("P")), discipline.type("P"))
    }

    // ── 退格 ──────────────────────────────────────────────────────────────────

    @Test
    fun `backspace on an empty line does nothing`() {
        val discipline = discipline()

        // Erasing past the start of the line would rub out the prompt itself.
        assertEquals(noEffects, discipline.type(DEL))
        assertEquals(noEffects, discipline.type(BS))
        assertEquals("", discipline.currentLine)
    }

    @Test
    fun `backspace erases one character at a time`() {
        val discipline = discipline()
        discipline.type("ab")

        assertEquals(listOf(echo("\b \b")), discipline.type(DEL))
        assertEquals("a", discipline.currentLine)

        assertEquals(listOf(echo("\b \b")), discipline.type(BS))
        assertEquals("", discipline.currentLine)

        assertEquals(noEffects, discipline.type(DEL))
    }

    // ── Ctrl+C ────────────────────────────────────────────────────────────────

    @Test
    fun `ctrl C clears the line and defers where the interrupt goes`() {
        val discipline = discipline()
        discipline.type("half-typed")

        assertEquals(
            listOf(echo("^C\r\n"), LineDisciplineEffect.Interrupt),
            discipline.type(CTRL_C)
        )
        assertEquals("", discipline.currentLine)
    }

    @Test
    fun `ctrl C is forwarded to the shell while a command is running`() {
        assertEquals(
            LineDisciplineEffect.SendInterrupt,
            discipline().resolveInterrupt(awaitingPrompt = true)
        )
    }

    @Test
    fun `ctrl C redraws the prompt when no command is running`() {
        // Nothing is waiting on the shell, so forwarding 0x03 would be swallowed and the user would
        // be left staring at a bare line with no prompt.
        assertEquals(
            echo(prompt),
            discipline().resolveInterrupt(awaitingPrompt = false)
        )
    }

    // ── Ctrl+U / Ctrl+D ───────────────────────────────────────────────────────

    @Test
    fun `ctrl U erases the whole line`() {
        val discipline = discipline()
        discipline.type("abc")

        assertEquals(listOf(echo("\b \b\b \b\b \b")), discipline.type(CTRL_U))
        assertEquals("", discipline.currentLine)
        assertEquals(noEffects, discipline.type(CTRL_U))
    }

    @Test
    fun `ctrl D only reacts on an empty line`() {
        val discipline = discipline()

        assertEquals(
            listOf(echo("\r\n[使用 exit 命令退出]\r\n"), echo(prompt)),
            discipline.type(CTRL_D)
        )

        discipline.type("x")
        assertEquals(noEffects, discipline.type(CTRL_D))
    }

    // ── Enter 的三条分支 ───────────────────────────────────────────────────────

    @Test
    fun `enter hands an ordinary command to the shell without drawing a prompt`() {
        val discipline = discipline()
        discipline.type("ls -l")

        // The prompt is drawn by the reader coroutine once the command's output stops, which is why
        // AwaitPrompt has to be set before the command is written.
        assertEquals(
            listOf(
                echo("\r\n"),
                LineDisciplineEffect.AwaitPrompt,
                LineDisciplineEffect.SendToShell("ls -l\n")
            ),
            discipline.type("\r")
        )
        assertEquals("", discipline.currentLine)
    }

    @Test
    fun `enter on an empty line just redraws the prompt`() {
        assertEquals(listOf(echo("\r\n"), echo(prompt)), discipline().type("\r"))
    }

    @Test
    fun `enter routes @agent commands to the virtual handler with the prefix stripped`() {
        val discipline = discipline()
        discipline.type("@agent apps installed")

        assertEquals(
            listOf(
                echo("\r\n"),
                LineDisciplineEffect.RunAgentCommand("apps installed"),
                echo(prompt)
            ),
            discipline.type("\r")
        )
    }

    @Test
    fun `bare @agent maps to an empty subcommand`() {
        val discipline = discipline()
        discipline.type("  @agent  ")

        assertEquals(
            listOf(echo("\r\n"), LineDisciplineEffect.RunAgentCommand(""), echo(prompt)),
            discipline.type("\r")
        )
    }

    @Test
    fun `LF alone is ignored so CRLF is not two enters`() {
        val discipline = discipline()
        discipline.type("id")

        assertEquals(
            listOf(
                echo("\r\n"),
                LineDisciplineEffect.AwaitPrompt,
                LineDisciplineEffect.SendToShell("id\n")
            ),
            discipline.type("\r\n")
        )
    }

    // ── 超长行 ────────────────────────────────────────────────────────────────

    @Test
    fun `overflowing input is dropped silently and rejected at enter`() {
        val discipline = discipline(maxCommandLength = 4)

        // Only the first four characters echo; erroring per character would spray the terminal
        // while a long paste is still arriving.
        assertEquals(listOf(echo("a"), echo("b"), echo("c"), echo("d")), discipline.type("abcdefg"))
        assertEquals("abcd", discipline.currentLine)

        assertEquals(
            listOf(
                echo("\r\n"),
                echo("[Command rejected: maximum length is 4 characters]\r\n"),
                echo(prompt)
            ),
            discipline.type("\r")
        )
        assertEquals("", discipline.currentLine)
    }

    @Test
    fun `the overflow flag is cleared by enter so the next line is accepted`() {
        val discipline = discipline(maxCommandLength = 4)
        discipline.type("abcdefg\r")

        discipline.type("ok")
        assertEquals(acceptedCommand("ok"), discipline.type("\r"))
    }

    @Test
    fun `ctrl C clears the overflow flag`() {
        val discipline = discipline(maxCommandLength = 4)
        discipline.type("abcdefg")
        discipline.type(CTRL_C)

        discipline.type("ok")
        assertEquals(acceptedCommand("ok"), discipline.type("\r"))
    }

    @Test
    fun `ctrl U clears the overflow flag`() {
        val discipline = discipline(maxCommandLength = 4)
        discipline.type("abcdefg")
        discipline.type(CTRL_U)

        discipline.type("ok")
        assertEquals(acceptedCommand("ok"), discipline.type("\r"))
    }

    @Test
    fun `the production 8 KiB limit is the cut-off point`() {
        // Guards the constant TerminalManager wires up; the tests above deliberately shrink it.
        val discipline = discipline(maxCommandLength = 8 * 1024)
        discipline.type("x".repeat(8 * 1024))

        assertEquals(8 * 1024, discipline.currentLine.length)
        assertEquals(noEffects, discipline.type("x"))
    }

    // ── LF → CRLF 翻译 ─────────────────────────────────────────────────────────

    @Test
    fun `lone LF becomes CRLF`() {
        assertTranslated("a\nb\n", "a\r\nb\r\n")
    }

    @Test
    fun `existing CRLF is not translated twice`() {
        // A second \r would be emitted for every line a CRLF-emitting shell already got right.
        assertTranslated("a\r\nb\r\n", "a\r\nb\r\n")
    }

    @Test
    fun `mixed line endings are normalised`() {
        assertTranslated("a\r\nb\nc", "a\r\nb\r\nc")
    }

    @Test
    fun `output without LF is copied unchanged`() {
        assertTranslated("plain output", "plain output")
    }

    @Test
    fun `only the first length bytes are considered`() {
        // readLoop hands in a reused 4 KiB buffer, so anything past `length` is stale data from the
        // previous read and must not leak into the terminal.
        val buffer = "ab\n".toByteArray() + "STALE\n".toByteArray()

        assertArrayEquals("ab".toByteArray(), translateLfToCrlf(buffer, 2))
        assertArrayEquals("ab\r\n".toByteArray(), translateLfToCrlf(buffer, 3))
    }

    @Test
    fun `an LF at the very start of a chunk is translated`() {
        // The translator only sees this buffer, so a leading \n counts as lone even if the previous
        // chunk ended with \r. That is the safe direction: an extra \r is a no-op on the terminal.
        assertTranslated("\nx", "\r\nx")
    }

    @Test
    fun `empty output stays empty`() {
        assertArrayEquals(ByteArray(0), translateLfToCrlf(ByteArray(0), 0))
    }

    @Test
    fun `translation does not alias the source buffer`() {
        val buffer = "no newline".toByteArray()

        assertTrue(
            "must return a copy, readLoop reuses the buffer",
            translateLfToCrlf(buffer, buffer.size) !== buffer
        )
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────────

    private fun discipline(maxCommandLength: Int = 8 * 1024) =
        LineDiscipline(prompt, maxCommandLength)

    private fun echo(text: String) = LineDisciplineEffect.Echo(text)

    private fun acceptedCommand(command: String) = listOf(
        echo("\r\n"),
        LineDisciplineEffect.AwaitPrompt,
        LineDisciplineEffect.SendToShell("$command\n")
    )

    /** Feeds [text] one byte at a time, exactly as `TerminalManager.handleInput` does. */
    private fun LineDiscipline.type(text: String): List<LineDisciplineEffect> =
        text.flatMap { onByte(it.code.toByte()) }

    private fun assertTranslated(input: String, expected: String) {
        val bytes = input.toByteArray()
        assertArrayEquals(expected.toByteArray(), translateLfToCrlf(bytes, bytes.size))
    }

    private companion object {
        /** Control bytes spelled out by code point so they stay legible in the source. */
        val ESC = Char(0x1B).toString()
        val BS = Char(0x08).toString()
        val DEL = Char(0x7F).toString()
        val CTRL_C = Char(0x03).toString()
        val CTRL_D = Char(0x04).toString()
        val CTRL_U = Char(0x15).toString()

        val noEffects = emptyList<LineDisciplineEffect>()
    }
}
