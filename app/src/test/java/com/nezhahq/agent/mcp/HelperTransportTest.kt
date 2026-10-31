package com.nezhahq.agent.mcp

import com.nezhahq.agent.core.mcp.McpErrorCode
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.mcp.exec.McpExecBackend
import com.nezhahq.agent.mcp.transport.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import proto.Nezha.Task
import proto.Nezha.TaskResult

class HelperTransportTest {
    @Test fun rejectedHandshakeNeverSendsAnyUserBytesAndClosesLease() = runBlocking {
        val changes: List<(HelperProtocol.Hello) -> HelperProtocol.Hello> = listOf(
            { it.copy(version = 2) }, { it.copy(uid = 0) }, { it.copy(effectiveUid = 0) },
            { it.copy(sessionId = it.pid + 1) }, { it.copy(nonce = ByteArray(32)) }
        )
        for (change in changes) {
            val launcher = FakeLauncher { spec -> FakeLease(frames {
                frame(HelperProtocol.Kind.HELLO, HelperProtocol.encodeHello(change(hello(spec))))
            }) }
            val outcome = exchange(launcher)
            assertEquals(McpErrorCode.CAPABILITY_UNAVAILABLE, (outcome as HelperExchangeResult.Failed).error!!.code)
            assertEquals(0, launcher.lease!!.sent.size())
            assertEquals(1, launcher.lease!!.closes.get())
            assertEquals(0, launcher.lease!!.identities)
        }
    }

    @Test fun malformedHelloFrameNeverSendsCommand() = runBlocking {
        val malformed = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply { writeInt(0x4e5a4d48); writeByte(1); writeInt(Int.MAX_VALUE) }
        }.toByteArray()
        val launcher = FakeLauncher { FakeLease(malformed) }
        assertTrue(exchange(launcher) is HelperExchangeResult.Failed)
        assertEquals(0, launcher.lease!!.sent.size())
    }

    @Test fun terminalReservationPrecedesPayloadReadAndSurvivesHelperCleanupUntilFinalDrop() = runBlocking {
        val result = result(Long.MIN_VALUE)
        lateinit var counted: HelperProtocolTest.CountingInput
        val launcher = FakeLauncher { spec ->
            counted = HelperProtocolTest.CountingInput(frames {
                frame(HelperProtocol.Kind.HELLO, HelperProtocol.encodeHello(hello(spec)))
                frame(HelperProtocol.Kind.RESULT, result.toByteArray())
            })
            FakeLease(counted)
        }
        val released = AtomicInteger()
        val outcome = McpHelperTransport().exchange(
            launcher, HelperCall(Long.MIN_VALUE, 15, "{\"cmd\":\"echo\"}"), HelperDeadline.afterMillis(1000), 64
        ) { bytes ->
            assertEquals(result.serializedSize.toLong(), bytes)
            assertEquals(9 + HelperProtocol.HELLO_BYTES + 9, counted.position)
            AutoCloseable { released.incrementAndGet() }
        } as HelperExchangeResult.Completed
        assertEquals(Long.MIN_VALUE, outcome.owned.result.id)
        assertEquals(1, launcher.lease!!.closes.get())
        assertEquals(0, released.get())
        outcome.owned.close()
        outcome.owned.close()
        assertEquals(1, released.get())
        val reader = HelperProtocol.Reader(ByteArrayInputStream(launcher.lease!!.sent.toByteArray()))
        val header = reader.header(setOf(HelperProtocol.Kind.REQUEST))
        val request = HelperProtocol.decodeRequest(reader.payload(header))
        assertEquals(Long.MIN_VALUE, request.id)
        assertEquals("{\"cmd\":\"echo\"}", request.data)
    }

    @Test fun mismatchedTerminalIdentityReleasesReservationAndRejectsResult() = runBlocking {
        for (wrong in listOf(result(99), result(1).toBuilder().setType(16).build())) {
            val released = AtomicInteger()
            val launcher = FakeLauncher { spec -> FakeLease(frames {
                frame(HelperProtocol.Kind.HELLO, HelperProtocol.encodeHello(hello(spec)))
                frame(HelperProtocol.Kind.RESULT, wrong.toByteArray())
            }) }
            val outcome = McpHelperTransport().exchange(
                launcher, HelperCall(1, 15, "{}"), HelperDeadline.afterMillis(1000), 64
            ) { AutoCloseable { released.incrementAndGet() } }
            assertTrue(outcome is HelperExchangeResult.Failed)
            assertEquals(1, released.get())
        }
    }

    @Test fun oversizedTerminalIsRejectedBeforeReservationOrAllocation() = runBlocking {
        var reservations = 0
        val launcher = FakeLauncher { spec -> FakeLease(ByteArrayOutputStream().also { bytes ->
            HelperProtocol.Writer(bytes).frame(HelperProtocol.Kind.HELLO, HelperProtocol.encodeHello(hello(spec)))
            DataOutputStream(bytes).apply { writeInt(0x4e5a4d48); writeByte(6); writeInt(Int.MAX_VALUE) }
        }.toByteArray()) }
        val outcome = McpHelperTransport().exchange(
            launcher, HelperCall(1, 15, "{}"), HelperDeadline.afterMillis(1000), 64
        ) { reservations++; AutoCloseable { } }
        assertTrue(outcome is HelperExchangeResult.Failed)
        assertEquals(0, reservations)
        assertEquals(1, launcher.lease!!.closes.get())
    }

    @Test fun reservationRefusalPropagatesOnceWithoutRetryOrFallbackAllocation() = runBlocking {
        val refusal = IllegalStateException("budget refused")
        var reservations = 0
        val launcher = FakeLauncher { spec -> FakeLease(frames {
            frame(HelperProtocol.Kind.HELLO, HelperProtocol.encodeHello(hello(spec)))
            frame(HelperProtocol.Kind.RESULT, result(1).toByteArray())
        }) }
        try {
            McpHelperTransport().exchange(
                launcher, HelperCall(1, 15, "{}"), HelperDeadline.afterMillis(1000), 64
            ) { reservations++; throw refusal }
            fail("Expected caller budget refusal")
        } catch (failure: IllegalStateException) { assertSame(refusal, failure) }
        assertEquals(1, reservations)
        assertEquals(1, launcher.lease!!.closes.get())
    }

    @Test fun timeoutPreservesAlreadyStreamedBytesAndRevokesTheGroupOwner() = runBlocking {
        val launcher = FakeLauncher { spec -> FakeLease(BlockingTail(frames {
            frame(HelperProtocol.Kind.HELLO, HelperProtocol.encodeHello(hello(spec)))
            frame(HelperProtocol.Kind.STDOUT, "before-timeout".toByteArray())
            frame(HelperProtocol.Kind.STDERR, byteArrayOf(0xe2.toByte(), 0x82.toByte()))
            frame(HelperProtocol.Kind.TRUNCATED, byteArrayOf(2))
        })) }
        val outcome = exchange(launcher, 150) as HelperExchangeResult.Failed
        val capture = requireNotNull(outcome.output)
        assertTrue(outcome.timedOut)
        assertNull(outcome.error)
        assertEquals("before-timeout", capture.snapshot().stdout)
        assertEquals("\ufffd", capture.snapshot().stderr)
        assertTrue(capture.snapshot().stderrTruncated)
        assertEquals(1, launcher.lease!!.closes.get())
    }

    @Test fun cancellationPropagatesAndClosesControlWithoutWaitingForOutputEOF() = runBlocking {
        val launcher = FakeLauncher { spec -> FakeLease(BlockingTail(frames {
            frame(HelperProtocol.Kind.HELLO, HelperProtocol.encodeHello(hello(spec)))
        })).apply { cleanupFailure = IllegalStateException("cleanup failed") } }
        val job = async { exchange(launcher, 5000) }
        withTimeout(1000) { while (launcher.lease?.sent?.size() == null || launcher.lease!!.sent.size() == 0) delay(1) }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, launcher.lease!!.closes.get())
    }

    @Test fun cancellationWhileWaitingForBudgetClosesHelperAndDoesNotAllocatePayload() = runBlocking {
        lateinit var counted: HelperProtocolTest.CountingInput
        val launcher = FakeLauncher { spec ->
            counted = HelperProtocolTest.CountingInput(frames {
                frame(HelperProtocol.Kind.HELLO, HelperProtocol.encodeHello(hello(spec)))
                frame(HelperProtocol.Kind.RESULT, result(1).toByteArray())
            })
            FakeLease(counted)
        }
        val waiting = CompletableDeferred<Unit>()
        val job = async {
            McpHelperTransport().exchange(launcher, HelperCall(1, 15, "{}"), HelperDeadline.afterMillis(5000), 64) {
                waiting.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(1000) { waiting.await() }
        job.cancelAndJoin()
        assertEquals(9 + HelperProtocol.HELLO_BYTES + 9, counted.position)
        assertEquals(1, launcher.lease!!.closes.get())
    }

    @Test fun policyParsePreparationAndNulFailuresNeverLaunchAndUseExistingEnvelopeSemantics() = runBlocking {
        val launcher = FakeLauncher { error("Must never launch") }
        val backend = McpExecBackend(launcher)
        val reserved = mutableListOf<Long>()
        suspend fun run(data: String, enabled: Boolean = true): TaskResult {
            val owned = backend.execute(task(data), RemoteCapabilities(shellEnabled = enabled)) {
                reserved += it
                AutoCloseable { }
            }
            return owned.use { it.result }
        }
        assertTrue(run("{}", false).successful)
        assertFalse(run("{oops").successful)
        assertTrue(run("{\"cmd\":\"\"}").successful)
        assertTrue(run("{\"cmd\":\"echo\\u0000secret\"}").data.contains("NUL"))
        val envError = run("{\"cmd\":\"echo\",\"env\":{\"bad=secret\":\"private-value\"}}")
        assertFalse(envError.data.contains("secret"))
        assertFalse(envError.data.contains("private-value"))
        assertTrue(reserved.all { it <= 1024 })
        assertEquals(0, launcher.starts)
    }

    @Test fun launchAndHandshakeTimeConsumeOverallDeadlineWithoutFallbackBackend() = runBlocking {
        val launcher = object : HelperLauncher {
            override val backend = HelperBackend.ROOT
            override suspend fun launch(spec: HelperLaunchSpec): HelperProcessLease {
                delay(1000)
                error("Deadline must cancel authorization/startup")
            }
        }
        val outcome = exchange(launcher, 50) as HelperExchangeResult.Failed
        assertTrue(outcome.timedOut)
    }

    @Test fun lateSuccessfulLaunchIsOwnedBeforeOuterDeadlineDiscardsItsReturn() = runBlocking(Dispatchers.Default) {
        val lease = FakeLease(ByteArray(0))
        val launcher = object : HelperLauncher {
            override val backend = HelperBackend.APPLICATION
            override suspend fun launch(spec: HelperLaunchSpec): HelperProcessLease {
                // The deadline timer runs independently of this deliberately synchronous launch.
                // Its resource succeeds just as withTimeout is already cancelling the return.
                val context = currentCoroutineContext()
                val started = System.nanoTime()
                while (context.isActive) {
                    check(System.nanoTime() - started < 2_000_000_000L) { "Deadline test timer did not run" }
                    Thread.sleep(1)
                }
                return lease
            }
        }
        val outcome = exchange(launcher, 30) as HelperExchangeResult.Failed
        assertTrue(outcome.timedOut)
        assertEquals(1, lease.closes.get())
        assertEquals(0, lease.sent.size())
    }

    @Test fun successfulLaunchIsOwnedWhenCancellationWinsAtItsReturnBoundary() = runBlocking {
        val lease = FakeLease(ByteArray(0))
        val launcher = object : HelperLauncher {
            override val backend = HelperBackend.APPLICATION
            override suspend fun launch(spec: HelperLaunchSpec): HelperProcessLease {
                currentCoroutineContext().cancel(CancellationException("return boundary cancelled"))
                return lease
            }
        }
        val job = async { exchange(launcher) }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, lease.closes.get())
        assertEquals(0, lease.sent.size())
    }

    private suspend fun exchange(launcher: HelperLauncher, millis: Long = 1000): HelperExchangeResult =
        McpHelperTransport().exchange(launcher, HelperCall(1, 15, "{}"), HelperDeadline.afterMillis(millis), 64) {
            AutoCloseable { }
        }

    private fun result(id: Long) = TaskResult.newBuilder().setId(id).setType(15).setSuccessful(true).setData("{}").build()
    private fun task(data: String) = Task.newBuilder().setId(1).setType(15).setData(data).build()
    private fun hello(spec: HelperLaunchSpec) = HelperProtocol.Hello(1, spec.nonce, 10213, 10213, 1234, 1234)
    private fun frames(write: HelperProtocol.Writer.() -> Unit): ByteArray = ByteArrayOutputStream().also {
        HelperProtocol.Writer(it).write()
    }.toByteArray()

    private class FakeLauncher(val create: (HelperLaunchSpec) -> FakeLease) : HelperLauncher {
        override val backend = HelperBackend.APPLICATION
        @Volatile var lease: FakeLease? = null
        var starts = 0
        override suspend fun launch(spec: HelperLaunchSpec): HelperProcessLease {
            starts++
            return create(spec).also { lease = it }
        }
    }

    private class FakeLease(override val stdout: InputStream) : HelperProcessLease {
        constructor(bytes: ByteArray) : this(ByteArrayInputStream(bytes))
        override val backend = HelperBackend.APPLICATION
        override val expectedUid = 10213
        val sent = ByteArrayOutputStream()
        override val stdin get() = sent
        override val stderr = ByteArrayInputStream(ByteArray(0))
        val closes = AtomicInteger()
        var identities = 0
        var cleanupFailure: Exception? = null
        override fun acceptIdentity(hello: HelperProtocol.Hello) { identities++ }
        override fun close() {
            if (closes.incrementAndGet() == 1) stdout.close()
            cleanupFailure?.let { throw it }
        }
    }

    /** Deliberately ignores thread interruption like a native pipe, but group revocation unblocks it. */
    private class BlockingTail(prefix: ByteArray) : InputStream() {
        private val prefix = ByteArrayInputStream(prefix)
        private val gate = java.lang.Object()
        private var closed = false
        override fun read(): Int {
            val next = prefix.read()
            if (next >= 0) return next
            synchronized(gate) { while (!closed) try { gate.wait() } catch (_: InterruptedException) { } }
            return -1
        }
        override fun close() { synchronized(gate) { closed = true; gate.notifyAll() } }
    }
}
