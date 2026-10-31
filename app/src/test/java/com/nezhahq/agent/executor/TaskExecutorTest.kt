package com.nezhahq.agent.executor

import com.nezhahq.agent.SilentLoggerRule
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.core.task.RemoteCapabilityPolicy
import com.nezhahq.agent.core.task.TaskTypes
import com.nezhahq.agent.util.readLimitedUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import proto.Nezha.Task
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TaskExecutorTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()
    @After
    fun clearInterruptedStatus() {
        Thread.interrupted()
    }

    @Test
    fun keepaliveReturnsSuccessfulEmptyResult() = runBlocking {
        val task = Task.newBuilder().setId(7L).setType(TaskTypes.KEEPALIVE).build()

        val result = TaskExecutor.executeTask(task, RemoteCapabilities())

        assertEquals(7L, result.id)
        assertEquals(TaskTypes.KEEPALIVE, result.type)
        assertTrue(result.successful)
        assertEquals("", result.data)
    }

    @Test
    fun unsupportedMcpTypesReturnExplicitFailure() = runBlocking {
        val task = Task.newBuilder().setId(16L).setType(TaskTypes.FS_LIST).build()

        val result = TaskExecutor.executeTask(task, RemoteCapabilities())

        assertEquals(TaskTypes.FS_LIST, result.type)
        assertFalse(result.successful)
        assertEquals(TaskTypes.unsupportedMessage(TaskTypes.FS_LIST), result.data)
    }

    @Test
    fun disabledRemoteShellRejectsInteractiveTerminal() = runBlocking {
        val task = Task.newBuilder().setId(8L).setType(TaskTypes.TERMINAL).build()

        val result = TaskExecutor.executeTask(task, RemoteCapabilities())

        assertEquals(8L, result.id)
        assertEquals(TaskTypes.TERMINAL, result.type)
        assertFalse(result.successful)
        assertEquals("Remote shell execution is disabled on Android Agent.", result.data)
    }

    @Test
    fun tcpPingReportsAnUnparsableTargetInsteadOfProbingPortZero() = runBlocking {
        // Port 0 cannot be connected to. The old parser left port at 0 for every plain-string
        // target and reported the resulting failure as if the host were down.
        val task = Task.newBuilder()
            .setId(42L)
            .setType(TaskTypes.TCP_PING)
            .setData("example.com:0")
            .build()

        val result = TaskExecutor.executeTask(task, RemoteCapabilities())

        assertFalse(result.successful)
        assertEquals(0f, result.delay, 0f)
        assertTrue("the dashboard needs a diagnostic, not a blank failure", result.data.isNotEmpty())
    }

    @Test
    fun tcpPingTargetNeverReachesTheLog() = runBlocking {
        // Unbracketed IPv6 is rejected, which is the cheapest way to reach the failure path without
        // opening a socket.
        val task = Task.newBuilder()
            .setId(4242L)
            .setType(TaskTypes.TCP_PING)
            .setData("2001:db8::1")
            .build()

        TaskExecutor.executeTask(task, RemoteCapabilities())

        assertTrue(silentLogger.messages.any { it.contains("TaskID=4242") })
        assertTrue(
            "a probe target can name an internal host; the log view and logcat are both readable",
            silentLogger.messages.none { it.contains("2001:db8") }
        )
    }

    @Test
    fun streamTaskConstantsPreserveExistingRouting() {
        assertTrue(TaskTypes.TERMINAL in TaskTypes.STREAM_TASKS)
        assertTrue(TaskTypes.NAT in TaskTypes.STREAM_TASKS)
        assertTrue(TaskTypes.FILE_MANAGER in TaskTypes.STREAM_TASKS)
        assertFalse(TaskTypes.KEEPALIVE in TaskTypes.STREAM_TASKS)
    }

    @Test
    fun compatibleWaitReturnsExitCodeWithoutPlatformTimedWait() {
        var now = 0L
        val process = FakeProcess()
        val compatibility = ProcessCompatibility(
            sdkInt = 23,
            destroyForcibly = { error("unexpected termination") },
            nanoTime = { now },
            sleep = { millis ->
                now += TimeUnit.MILLISECONDS.toNanos(millis)
                process.complete(7)
            }
        )

        assertEquals(ProcessWaitResult.Exited(7), compatibility.waitFor(process, 100))
        assertEquals(0, process.timedWaitCalls)
    }

    @Test
    fun compatibleWaitTimesOutByPollingExitValue() {
        var now = 0L
        val sleeps = mutableListOf<Long>()
        val process = FakeProcess()
        val compatibility = ProcessCompatibility(
            sdkInt = 25,
            destroyForcibly = { error("unexpected termination") },
            nanoTime = { now },
            sleep = { millis ->
                sleeps += millis
                now += TimeUnit.MILLISECONDS.toNanos(millis)
            }
        )

        assertSame(ProcessWaitResult.TimedOut, compatibility.waitFor(process, 25))
        assertEquals(listOf(10L, 10L, 5L), sleeps)
        assertEquals(0, process.timedWaitCalls)
    }

    @Test
    fun compatibleWaitRestoresInterruptedStatusAndRethrows() {
        val compatibility = ProcessCompatibility(
            sdkInt = 25,
            destroyForcibly = { error("unexpected termination") },
            sleep = { throw InterruptedException("stop") }
        )
        try {
            compatibility.waitFor(FakeProcess(), 100)
            throw AssertionError("Expected InterruptedException")
        } catch (_: InterruptedException) {
            assertTrue(Thread.currentThread().isInterrupted)
        }
    }

    @Test
    fun terminationSelectsSdkStrategyAndClosesStreams() {
        val legacy = FakeProcess()
        var destroyCalls = 0
        var forceCalls = 0
        ProcessCompatibility(25, { forceCalls++ }, destroy = { destroyCalls++ }).terminate(legacy)
        assertEquals(1, destroyCalls)
        assertEquals(0, forceCalls)
        assertTrue(legacy.allStreamsClosed)

        val modern = FakeProcess()
        ProcessCompatibility(26, { forceCalls++ }, destroy = { destroyCalls++ }).terminate(modern)
        assertEquals(1, destroyCalls)
        assertEquals(1, forceCalls)
        assertTrue(modern.allStreamsClosed)
    }

    @Test
    fun orchestratorNormalExitOnlyClosesStreams() = runBlocking {
        val harness = ExecutionHarness(FakeProcess(TestInputStream("ok".toByteArray())))
        harness.process.complete(0)

        assertEquals(ProcessExecutionResult.Exited(0, "ok"), harness.run())
        assertEquals(0, harness.destroyCalls.get())
        assertEquals(0, harness.forceDestroyCalls.get())
        assertTrue(harness.process.allStreamsClosed)
    }

    @Test
    fun orchestratorTimeoutTerminates() = runBlocking {
        val clock = TestClock()
        val harness = ExecutionHarness(clock = clock, delayMillis = { clock.now = 100 })

        assertEquals(ProcessExecutionResult.TimedOut(""), harness.run())
        assertEquals(1, harness.destroyCalls.get())
        assertTrue(harness.process.allStreamsClosed)
    }

    @Test
    fun orchestratorRejectsExitAfterAbsoluteDeadline() = runBlocking {
        val clock = TestClock()
        lateinit var process: FakeProcess
        process = FakeProcess(TestInputStream(onEof = {
            clock.now = 101
            process.complete(0)
        }))
        val harness = ExecutionHarness(process, clock)

        assertEquals(ProcessExecutionResult.TimedOut(""), harness.run())
        assertEquals(1, harness.destroyCalls.get())
    }

    @Test
    fun orchestratorCancellationTerminatesBlockedReadAndRethrows() = runBlocking {
        val input = BlockingInputStream()
        val harness = ExecutionHarness(FakeProcess(input))
        val cancellationRethrown = AtomicBoolean()
        val job = launch(Dispatchers.IO) {
            try {
                harness.run()
            } catch (cancelled: CancellationException) {
                cancellationRethrown.set(true)
                throw cancelled
            }
        }

        assertTrue(input.awaitReadStarted(1, TimeUnit.SECONDS))
        job.cancel()
        try {
            withTimeout(1_000) { job.join() }
        } finally {
            input.release()
        }
        assertTrue(job.isCancelled)
        assertTrue(cancellationRethrown.get())
        assertEquals(1, harness.destroyCalls.get())
        assertTrue(harness.process.allStreamsClosed)
    }

    @Test
    fun boundedOutputKeepsOneMiBAndDrainsToEof() {
        val limit = 1024 * 1024
        val input = TestInputStream(ByteArray(limit + 64 * 1024) { 'x'.code.toByte() })

        val output = readLimitedUtf8(input, limit)

        assertEquals(limit, output.toByteArray().size)
        assertEquals(limit + 64 * 1024, input.bytesRead)
    }

    @Test
    fun boundedOutputDecodesUtf8AcrossReadBoundaries() {
        val text = "ab€tail"
        assertEquals(text, readLimitedUtf8(TestInputStream(text.toByteArray(), 3), 8192))
    }

    private class ExecutionHarness(
        val process: FakeProcess = FakeProcess(),
        private val clock: TestClock = TestClock(),
        delayMillis: suspend (Long) -> Unit = { awaitCancellation() }
    ) {
        val destroyCalls = AtomicInteger()
        val forceDestroyCalls = AtomicInteger()
        private val compatibility = ProcessCompatibility(
            sdkInt = 25,
            destroyForcibly = { forceDestroyCalls.incrementAndGet() },
            nanoTime = { clock.now },
            destroy = {
                destroyCalls.incrementAndGet()
                it.destroy()
            }
        )
        private val orchestrator = ProcessExecutionOrchestrator(
            compatibility,
            nanoTime = { clock.now },
            delayMillis = delayMillis
        )

        suspend fun run(): ProcessExecutionResult = orchestrator.executeUntil(process, 100, 1024)
    }

    private class TestClock(@Volatile var now: Long = 0)

    private class FakeProcess(stdoutDelegate: InputStream = TestInputStream()) : Process() {
        private val stdin = TrackingOutputStream()
        private val stdout = TrackingInputStream(stdoutDelegate)
        private val stderr = TrackingInputStream()
        @Volatile private var exitCode: Int? = null
        var timedWaitCalls = 0
            private set
        val allStreamsClosed: Boolean
            get() = stdin.closed && stdout.closed && stderr.closed

        fun complete(code: Int) {
            exitCode = code
        }
        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = stderr
        override fun waitFor(): Int = error("blocking waitFor called")
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            timedWaitCalls++
            error("platform timed waitFor called")
        }
        override fun exitValue(): Int = exitCode ?: throw IllegalThreadStateException("running")
        override fun destroy() {
            exitCode = -1
        }
        override fun destroyForcibly(): Process = apply { destroy() }
    }

    private class TrackingInputStream(
        private val delegate: InputStream = ByteArrayInputStream(ByteArray(0))
    ) : InputStream() {
        @Volatile var closed = false
            private set
        override fun read(): Int = if (closed) throw IOException("closed") else delegate.read()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            if (closed) throw IOException("closed") else delegate.read(buffer, offset, length)
        override fun close() {
            closed = true
            delegate.close()
        }
    }

    private class TrackingOutputStream : OutputStream() {
        @Volatile var closed = false
            private set
        override fun write(value: Int) = Unit
        override fun close() {
            closed = true
        }
    }

    private class TestInputStream(
        private val payload: ByteArray = ByteArray(0),
        private val maxChunkBytes: Int = Int.MAX_VALUE,
        private val onEof: () -> Unit = {}
    ) : InputStream() {
        private var position = 0
        private var eofReported = false
        var bytesRead = 0
            private set

        override fun read(): Int {
            val oneByte = ByteArray(1)
            return if (read(oneByte, 0, 1) == -1) -1 else oneByte[0].toInt() and 0xff
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= payload.size) {
                if (!eofReported) {
                    eofReported = true
                    onEof()
                }
                return -1
            }
            val count = minOf(length, maxChunkBytes, payload.size - position)
            payload.copyInto(buffer, offset, position, position + count)
            position += count
            bytesRead += count
            return count
        }
    }

    private class BlockingInputStream : InputStream() {
        private val readStarted = CountDownLatch(1)
        private val released = CountDownLatch(1)
        @Volatile private var closed = false

        override fun read(): Int {
            val oneByte = ByteArray(1)
            return if (read(oneByte, 0, 1) == -1) -1 else oneByte[0].toInt() and 0xff
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readStarted.countDown()
            released.await()
            if (closed) throw IOException("closed")
            return -1
        }
        override fun close() {
            closed = true
            released.countDown()
        }
        fun awaitReadStarted(timeout: Long, unit: TimeUnit): Boolean = readStarted.await(timeout, unit)
        fun release() = released.countDown()
    }
}
