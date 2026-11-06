package com.nezhahq.agent.util

import com.nezhahq.agent.SilentLoggerRule
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class IsolatedShellCommandTest {
    @get:Rule val logger = SilentLoggerRule()

    @Test fun emptySuccessAndNonzeroExitRemainDistinguishable() = runBlocking {
        val success = run(FakeProcess(exit = 0))
        val failure = run(FakeProcess(exit = 72))
        assertTrue(success.successful)
        assertEquals("", success.output)
        assertFalse(failure.successful)
        assertEquals(72, failure.exitCode)
        assertTrue(failure.outputComplete)
    }

    @Test fun truncatedOutputIsFlaggedEvenWhenTheCommandExitsZero() = runBlocking {
        val result = run(FakeProcess(ByteArrayInputStream("123456".toByteArray())), maxBytes = 3)
        assertEquals("123", result.output)
        assertEquals(0, result.exitCode)
        assertTrue(result.truncated)
        assertTrue(result.outputComplete)
        assertFalse(result.successful)
    }

    @Test fun aReadFailureCannotMasqueradeAsSuccessfulEof() = runBlocking {
        val input = object : InputStream() {
            var read = false
            override fun read(): Int = if (!read) { read = true; 'x'.code } else throw IOException("synthetic")
        }
        val result = run(FakeProcess(input))
        assertEquals("x", result.output)
        assertEquals(0, result.exitCode)
        assertFalse(result.outputComplete)
        assertFalse(result.successful)
    }

    @Test fun timeoutClosesBlockedOutputAndDoesNotReportAnExitAsSuccess() = runBlocking {
        val input = BlockingInput()
        val process = FakeProcess(input)
        val result = withTimeout(5_000) { run(process, timeout = 100) }
        assertTrue(input.started.isCompleted)
        assertTrue(input.closed)
        assertTrue(process.destroyed.get())
        assertTrue(result.timedOut)
        assertNull(result.exitCode)
        assertFalse(result.successful)
    }

    @Test fun timeoutStillAppliesAfterOutputEofWhileWaitingForExit() = runBlocking {
        val process = FakeProcess(waitForDestroy = true)
        val result = withTimeout(5_000) { run(process, timeout = 100) }
        assertTrue(process.waitStarted.isCompleted)
        assertTrue(result.timedOut)
        assertFalse(result.successful)
        assertTrue(process.destroyed.get())
    }

    @Test fun callerCancellationClosesTheProcessAndIsNotConvertedToAResult() = runBlocking {
        val input = BlockingInput()
        val process = FakeProcess(input)
        val running = launch { run(process); fail("cancellation was swallowed") }
        try { withTimeout(5_000) { input.started.await() } }
        finally { withTimeout(5_000) { running.cancelAndJoin() } }
        assertTrue(input.closed)
        assertTrue(process.destroyed.get())
    }

    private suspend fun run(process: FakeProcess, timeout: Long = 5_000, maxBytes: Int = 100): ShellCommandResult {
        val closed = AtomicBoolean()
        val result = executeIsolatedShellCommand(process, "synthetic", timeout,
            { if (closed.compareAndSet(false, true)) ShellSession.destroyProcess(process) }, maxBytes)
        assertTrue(process.destroyed.get())
        return result
    }

    private class FakeProcess(
        private val input: InputStream = ByteArrayInputStream(byteArrayOf()),
        private val exit: Int = 0,
        private val waitForDestroy: Boolean = false
    ) : Process() {
        val destroyed = AtomicBoolean()
        val waitStarted = CompletableDeferred<Unit>()
        private val destroyedSignal = CountDownLatch(1)
        private val output = ByteArrayOutputStream()
        override fun getInputStream() = input
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = output
        override fun waitFor(): Int {
            waitStarted.complete(Unit)
            if (waitForDestroy) check(destroyedSignal.await(5, TimeUnit.SECONDS))
            return exit
        }
        override fun exitValue() = exit
        override fun destroy() { destroyed.set(true); destroyedSignal.countDown() }
    }

    private class BlockingInput : InputStream() {
        val started = CompletableDeferred<Unit>()
        private val released = CountDownLatch(1)
        @Volatile var closed = false
        override fun read(): Int {
            started.complete(Unit)
            check(released.await(5, TimeUnit.SECONDS))
            throw IOException("closed")
        }
        override fun close() { closed = true; released.countDown() }
    }
}
