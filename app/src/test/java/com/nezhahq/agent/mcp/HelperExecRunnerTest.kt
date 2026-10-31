package com.nezhahq.agent.mcp

import com.nezhahq.agent.core.mcp.ExecRequest
import com.nezhahq.agent.core.mcp.ExecResult
import com.nezhahq.agent.core.mcp.McpTaskFamily
import com.nezhahq.agent.mcp.exec.*
import com.nezhahq.agent.mcp.helper.*
import com.nezhahq.agent.mcp.transport.HelperDeadline
import com.nezhahq.agent.mcp.transport.HelperProtocol
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import proto.Nezha.TaskResult

class HelperExecRunnerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun realProcessDoesNotInterpretArgumentMetacharacters() {
        val literal = "a b; & | > < $(touch forbidden) `echo forbidden`"
        val withQuotes = literal + " \"quoted\""
        assertEquals(listOf("example", withQuotes), environment().prepare(request().copy(args = listOf(withQuotes))).command)
        val result = realFixture("args", extra = listOf(literal))
        assertEquals(literal, result.stdout)
        assertNull(result.error)
        assertEquals(0, result.exitCode)
    }

    @Test fun realProcessDrainsBothPipesIndependentlyAfterEachRetentionLimit() {
        val result = realFixture("dual", maxOutput = 32_768)
        assertEquals("o".repeat(32_768), result.stdout)
        assertEquals("e".repeat(32_768), result.stderr)
        assertTrue(result.stdoutTruncated)
        assertTrue(result.stderrTruncated)
        assertFalse(result.timedOut)
        assertNull(result.error)
    }

    @Test fun stdinIsClosedAtEofIncludingEmptyAndNulContainingInput() {
        assertEquals("0", realFixture("stdin").stdout)
        assertEquals("5", realFixture("stdin", stdin = "ab\u0000cd").stdout)
    }

    @Test fun targetThatDoesNotReadStdinCannotPinDeadlineOrMainThread() {
        val started = System.nanoTime()
        val result = realFixture("no-stdin", stdin = "x".repeat(1_000_000), millis = 500)
        assertTrue(result.timedOut)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2500)
        assertNull(result.error)
    }

    @Test fun nonzeroExitIsAnOrdinaryResultWithTrueUserExitCode() {
        val result = realFixture("nonzero")
        assertEquals(7, result.exitCode)
        assertEquals("out", result.stdout)
        assertEquals("err", result.stderr)
        assertNull(result.error)
        assertFalse(result.timedOut)
    }

    @Test fun deliberateEarlyStdinCloseDoesNotReplaceTheRealExitWithPipeFailure() {
        val result = realFixture("close-stdin", stdin = "x".repeat(1_000_000))
        assertEquals(13, result.exitCode)
        assertNull(result.error)
        assertFalse(result.timedOut)
    }

    @Test fun launchAndDirectoryFailuresDoNotExposeRawExceptionOrPaths() {
        val environment = environment()
        val runner = HelperExecRunner(environment, ExecProcessStarter { throw IOException("secret path/value") })
        val result = runner.execute(request(), HelperDeadline.afterMillis(1000), 0) { }
        assertEquals(-1, result.exitCode)
        assertEquals("exec process could not be started", result.error)
        assertFalse(result.error!!.contains("secret"))
        val badDirectory = runner.execute(
            request().copy(cwd = File(temporary.root, "missing-private-path").path), HelperDeadline.afterMillis(1000), 0
        ) { }
        assertEquals("cwd is not an accessible directory", badDirectory.error)
    }

    @Test fun environmentIsRebuiltAndExplicitValuesAreValidatedWithoutEchoingThem() {
        val prepared = environment().prepare(request().copy(env = mapOf("EXPLICIT" to "value")))
        assertEquals("value", prepared.environment["EXPLICIT"])
        assertEquals(setOf("PATH", "ANDROID_ROOT", "ANDROID_DATA", "TMPDIR", "EXPLICIT"), prepared.environment.keys)
        assertFalse(prepared.environment.containsKey("CLASSPATH"))
        assertFalse(prepared.environment.containsKey("HOME"))
        for (invalid in listOf(
            request().copy(env = mapOf("bad=private" to "secret")),
            request().copy(args = listOf("private\u0000secret")),
            request().copy(env = mapOf("VALID" to "private\u0000secret"))
        )) {
            val failure = assertThrows(ExecPreparationFailure::class.java) { environment().prepare(invalid) }
            assertFalse(failure.safeMessage.contains("private"))
            assertFalse(failure.safeMessage.contains("secret"))
        }
    }

    @Test fun mainExitIsObservedWithoutWaitingForDescendantHeldFdEof() {
        val held = HeldPipe()
        val process = ControlledProcess(held, ByteArrayInputStream(ByteArray(0)), 23)
        try {
            val start = System.nanoTime()
            val result = HelperExecRunner(environment(), ExecProcessStarter { process }, drainGraceMillis = 80)
                .execute(request(), HelperDeadline.afterMillis(2000), 0) { }
            val millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertEquals(23, result.exitCode)
            assertFalse(result.timedOut)
            assertTrue(result.stdoutTruncated)
            assertTrue(millis < 1000)
        } finally { held.close() }
    }

    @Test fun rawByteRetentionAndUtf8ReplacementAreTheSameInRunnerAndParentCapture() {
        val raw = byteArrayOf(0xe2.toByte(), 0x82.toByte(), 0xac.toByte(), 0xff.toByte())
        val process = ControlledProcess(ByteArrayInputStream(raw), ByteArrayInputStream("error".toByteArray()), 0)
        val parent = ExecOutputCapture(2)
        val result = HelperExecRunner(environment(), ExecProcessStarter { process })
            .execute(request().copy(maxOutputBytes = 2), HelperDeadline.afterMillis(1000), 0, parent::accept)
        assertEquals("\ufffd", result.stdout)
        assertEquals("er", result.stderr)
        assertTrue(result.stdoutTruncated)
        assertTrue(result.stderrTruncated)
        assertEquals(result.stdout, parent.snapshot().stdout)
        assertEquals(result.stderr, parent.snapshot().stderr)
    }

    @Test fun timeoutRetainsOutputWithoutTurningItIntoBusinessError() {
        val process = ControlledProcess(ByteArrayInputStream("partial".toByteArray()), ByteArrayInputStream(ByteArray(0)), null)
        val result = HelperExecRunner(environment(), ExecProcessStarter { process })
            .execute(request(), HelperDeadline.afterMillis(40), 7) { }
        assertTrue(result.timedOut)
        assertEquals("partial", result.stdout)
        assertNull(result.error)
        assertTrue(result.durationMs >= 47)
    }

    @Test fun outputIoFailureIsStableAndDoesNotExposeExceptionValues() {
        val process = ControlledProcess(object : InputStream() {
            override fun read(): Int = throw IOException("private-target-output")
        }, ByteArrayInputStream(ByteArray(0)), 0)
        val result = HelperExecRunner(environment(), ExecProcessStarter { process })
            .execute(request(), HelperDeadline.afterMillis(1000), 0) { }
        assertEquals("exec output IO failed", result.error)
        assertFalse(result.error!!.contains("private"))
        assertFalse(result.timedOut)
    }

    @Test fun helperSendsCompleteTerminalBeforeNormalGroupCleanupAndParsesAgain() {
        val data = "{\"cmd\":\"example\",\"args\":[\";&\"]}"
        val requestBytes = ByteArrayOutputStream().also {
            HelperProtocol.Writer(it).frame(HelperProtocol.Kind.REQUEST,
                HelperProtocol.encodeRequest(HelperProtocol.Request(Long.MIN_VALUE, 15, 1000, 0, data)))
        }.toByteArray()
        val input = HeldPipe(requestBytes)
        val output = ByteArrayOutputStream()
        val calls = AtomicInteger()
        val terminated = AtomicBoolean(false)
        val isolation = object : HelperIsolation {
            override fun establish(nonce: ByteArray) = HelperProtocol.Hello(1, nonce, 10213, 10213, 123, 123)
            override fun terminate() {
                if (terminated.compareAndSet(false, true)) {
                    val reader = HelperProtocol.Reader(ByteArrayInputStream(output.toByteArray()))
                    reader.payload(reader.header(setOf(HelperProtocol.Kind.HELLO)))
                    val terminal = TaskResult.parseFrom(reader.payload(reader.header(setOf(HelperProtocol.Kind.RESULT))))
                    assertEquals(Long.MIN_VALUE, terminal.id)
                    assertTrue(terminal.successful)
                    assertTrue(terminal.data.contains("\"exit_code\":19"))
                    input.close()
                }
            }
        }
        HelperSession(isolation, mapOf(McpTaskFamily.EXEC to HelperRequestHandler { request, _, _ ->
            calls.incrementAndGet()
            assertEquals(listOf(";&"), (request as ExecRequest).args)
            ExecResult(exitCode = 19)
        })).run(input, output, ByteArray(32), 1000)
        assertEquals(1, calls.get())
        assertTrue(terminated.get())
    }

    @Test fun controlEofRevokesHelperEvenWhileHandlerIsBlocked() {
        val request = ByteArrayOutputStream().also {
            HelperProtocol.Writer(it).frame(HelperProtocol.Kind.REQUEST,
                HelperProtocol.encodeRequest(HelperProtocol.Request(1, 15, 1000, 0, "{\"cmd\":\"blocked\"}")))
        }.toByteArray()
        val input = HeldPipe(request)
        val entered = CountDownLatch(1)
        val revoked = CountDownLatch(1)
        val isolation = object : HelperIsolation {
            override fun establish(nonce: ByteArray) = HelperProtocol.Hello(1, nonce, 10213, 10213, 123, 123)
            override fun terminate() { revoked.countDown() }
        }
        val thread = Thread({
            HelperSession(isolation, mapOf(McpTaskFamily.EXEC to HelperRequestHandler { _, _, _ ->
                entered.countDown()
                check(revoked.await(1, TimeUnit.SECONDS))
                ExecResult()
            })).run(input, ByteArrayOutputStream(), ByteArray(32), 1000)
        }, "test-helper-session").apply { isDaemon = true; start() }
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        input.close()
        assertTrue(revoked.await(1, TimeUnit.SECONDS))
        thread.join(1000)
        assertFalse(thread.isAlive)
    }

    @Test fun isolationFailureNeverReadsOrDispatchesUserRequest() {
        val terminated = AtomicInteger()
        val output = ByteArrayOutputStream()
        val isolation = object : HelperIsolation {
            override fun establish(nonce: ByteArray): HelperProtocol.Hello = throw IOException("isolation failed")
            override fun terminate() { terminated.incrementAndGet() }
        }
        HelperSession(isolation, mapOf(McpTaskFamily.EXEC to HelperRequestHandler { _, _, _ ->
            error("Must not execute")
        })).run(object : InputStream() { override fun read(): Int = error("Must not read") }, output, ByteArray(32), 1000)
        assertEquals(1, terminated.get())
        val reader = HelperProtocol.Reader(ByteArrayInputStream(output.toByteArray()))
        assertEquals(0, reader.header(setOf(HelperProtocol.Kind.UNAVAILABLE)).length)
    }

    private fun realFixture(
        mode: String, stdin: String = "", extra: List<String> = emptyList(), maxOutput: Long = 65_536, millis: Long = 5000
    ): ExecResult {
        val java = File(System.getProperty("java.home"), "bin/java" + if (File.separatorChar == '\\') ".exe" else "")
        val classes = File(requireNotNull(ExecFixture::class.java.protectionDomain).codeSource.location.toURI()).path
        var process: Process? = null
        return try {
            val request = ExecRequest(java.path, listOf("-cp", classes, ExecFixture::class.java.name, mode) + extra,
                stdin = stdin, maxOutputBytes = maxOutput)
            HelperExecRunner(environment(), ExecProcessStarter {
                SystemExecProcessStarter.start(it).also { child -> process = child }
            }).execute(request, HelperDeadline.afterMillis(millis), 0) { }
        } finally {
            // The production group owner is HelperMain. A JVM test owns its real child instead.
            process?.destroyForcibly()
            process?.waitFor(1, TimeUnit.SECONDS)
        }
    }

    private fun request() = ExecRequest("example")
    private fun environment() = ExecEnvironment(temporary.root, temporary.root)

    private class ControlledProcess(private val out: InputStream, private val err: InputStream, private val exit: Int?) : Process() {
        override fun getInputStream() = out
        override fun getErrorStream() = err
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun exitValue() = exit ?: throw IllegalThreadStateException()
        override fun waitFor(): Int = exitValue()
        override fun destroy() { out.close(); err.close() }
    }

    private class HeldPipe(prefix: ByteArray = ByteArray(0)) : InputStream() {
        private val prefix = ByteArrayInputStream(prefix)
        private val gate = java.lang.Object()
        private var closed = false
        override fun read(): Int {
            val byte = prefix.read()
            if (byte >= 0) return byte
            synchronized(gate) { while (!closed) try { gate.wait() } catch (_: InterruptedException) { } }
            return -1
        }
        override fun close() { synchronized(gate) { closed = true; gate.notifyAll() } }
    }
}
