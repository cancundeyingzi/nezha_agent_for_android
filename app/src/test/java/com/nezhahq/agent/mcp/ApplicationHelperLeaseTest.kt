package com.nezhahq.agent.mcp

import com.nezhahq.agent.mcp.helper.HelperSessionIdentity
import com.nezhahq.agent.mcp.transport.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ApplicationHelperLeaseTest {
    private val cleanup = HelperProcessCleanup()
    @Test fun exitedHelperNeverSignalsOrDestroysCachedPidEvenIfItsNumberHasBeenReused() {
        val process = FakeProcess()
        val control = FakeControl()
        val lease = lease(process, control)
        lease.acceptIdentity(hello())
        process.exit = 137
        control.current = identity(start = 9999)
        lease.closeAndAwait()
        lease.close()
        assertEquals(1, control.reads) // No post-exit probe or signal based on a stale integer.
        assertEquals(0, control.signals)
        assertEquals(0, process.destroys)
        assertEquals(1, process.controlCloses)
    }

    @Test fun liveMatchingSessionIsKilledBeforePipesCloseAndOnlyOnce() {
        val events = mutableListOf<String>()
        val process = FakeProcess(events)
        val control = FakeControl { events += "group-kill"; process.exit = 137 }
        val lease = lease(process, control)
        lease.acceptIdentity(hello())
        lease.closeAndAwait()
        lease.close()
        assertEquals(1, control.signals)
        assertEquals(2, control.reads)
        assertEquals(listOf("group-kill", "control-close"), events)
        assertEquals(0, process.destroys)
        assertEquals(1, process.controlCloses)
    }

    @Test fun changedOrUnreadableKernelIdentityNeverReceivesGroupSignalOrDestroyFallback() {
        val invalid = listOf(
            identity(start = 9999), identity(uid = 999), identity(group = 999), identity(session = 999),
            identity(state = 'Z'), identity(state = 'X'), null
        )
        for (current in invalid) {
            val process = FakeProcess()
            val control = FakeControl()
            val lease = lease(process, control)
            lease.acceptIdentity(hello())
            control.current = current
            lease.closeAndAwait()
            assertEquals(0, control.signals)
            assertEquals(0, process.destroys)
            assertEquals(1, process.controlCloses) // Original helper's independent EOF cleanup.
        }
    }

    @Test fun exitBetweenProcProbeAndSignalIsCheckedAgain() {
        val process = FakeProcess()
        val control = FakeControl()
        val lease = lease(process, control)
        lease.acceptIdentity(hello())
        control.afterRead = { process.exit = 137 }
        lease.closeAndAwait()
        assertEquals(0, control.signals)
        assertEquals(0, process.destroys)
        assertEquals(1, process.controlCloses)
    }

    @Test fun invalidKernelIdentityRejectsHandshakeBeforeAnyRequestCanBeSent() {
        for (current in listOf(identity(start = 100, group = 999), identity(uid = 0), identity(state = 'Z'), null)) {
            val process = FakeProcess()
            val control = FakeControl().apply { this.current = current }
            val lease = lease(process, control)
            assertThrows(HelperFailure::class.java) { lease.acceptIdentity(hello()) }
            lease.closeAndAwait()
            assertEquals(0, control.signals)
            assertEquals(1, process.destroys) // Only the pre-request helper exists.
        }
    }

    @Test fun closeBeforeIdentityNeverRegistersOrSignalsLatePid() {
        val process = FakeProcess()
        val control = FakeControl()
        val lease = lease(process, control)
        lease.closeAndAwait()
        assertThrows(HelperFailure::class.java) { lease.acceptIdentity(hello()) }
        lease.close()
        assertEquals(0, control.reads)
        assertEquals(0, control.signals)
        assertEquals(1, process.destroys)
        assertEquals(1, process.controlCloses)
    }

    @Test fun closeDuringKernelProbeRejectsLateRegistrationWithoutSignalingPid() {
        val process = FakeProcess()
        val control = FakeControl()
        val lease = lease(process, control)
        control.afterRead = { lease.close() }
        assertThrows(HelperFailure::class.java) { lease.acceptIdentity(hello()) }
        lease.closeAndAwait()
        assertEquals(0, control.signals)
        assertEquals(1, process.destroys)
        assertEquals(1, process.controlCloses)
    }

    @Test fun unknownProcessStatusIsNotPermissionToSignalCachedGroup() {
        val process = FakeProcess()
        val control = FakeControl()
        val lease = lease(process, control)
        lease.acceptIdentity(hello())
        process.statusFailure = IllegalStateException("status unavailable")
        lease.closeAndAwait()
        assertEquals(0, control.signals)
        assertEquals(0, process.destroys)
        assertEquals(1, process.controlCloses)
    }

    @Test fun failedSignalStillClosesOriginalControlWithoutDestroyingAcceptedHelperFirst() {
        val process = FakeProcess()
        val control = FakeControl { throw IllegalStateException("signal failed") }
        val lease = lease(process, control)
        lease.acceptIdentity(hello())
        lease.closeAndAwait()
        assertEquals(1, control.signals)
        assertEquals(0, process.destroys)
        assertEquals(1, process.controlCloses)
    }

    @Test fun blockedBufferedRequestWriterDoesNotPinCloseAndRetainsAdmissionUntilCleanupFinishes() {
        for (signalFails in listOf(false, true)) {
            val resources = HelperProcessCleanup(1)
            val pipe = BlockingRequestPipe()
            val process = FakeProcess(providedControl = pipe.buffered)
            val control = FakeControl { if (signalFails) throw IllegalStateException("signal failed") }
            val lease = ApplicationHelperLease.start(10213, control, resources) { process }
            lease.acceptIdentity(hello())
            if (!signalFails) control.current = null
            val writer = Thread({ runCatching { pipe.buffered.write(ByteArray(16_384)) } }, "test-blocked-request")
                .apply { isDaemon = true; start() }
            try {
                assertTrue(pipe.entered.await(1, TimeUnit.SECONDS))
                val started = System.nanoTime()
                lease.close()
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 250)
                assertFalse(lease.cleanupComplete)
                assertEquals(1, resources.retainedProcesses)
                val starts = AtomicInteger()
                val busy = assertThrows(HelperFailure::class.java) {
                    ApplicationHelperLease.start(10213, control, resources) { starts.incrementAndGet(); FakeProcess() }
                }
                assertEquals(com.nezhahq.agent.core.mcp.McpErrorCode.BUSY, busy.error.code)
                assertEquals(0, starts.get())
                pipe.unblock.countDown()
                writer.join(1000)
                lease.awaitCleanup()
                assertTrue(pipe.closed.await(1, TimeUnit.SECONDS))
                assertEquals(0, resources.retainedProcesses)
                val next = ApplicationHelperLease.start(10213, control, resources) { starts.incrementAndGet(); FakeProcess() }
                next.closeAndAwait()
                assertEquals(1, starts.get())
            } finally {
                pipe.unblock.countDown()
                writer.join(1000)
                lease.closeAndAwait()
            }
        }
    }

    @Test fun failedNativeLaunchReleasesUnusedCleanupTicketWithoutStartingAnotherProcess() {
        val resources = HelperProcessCleanup(1)
        assertThrows(IOException::class.java) {
            ApplicationHelperLease.start(10213, FakeControl(), resources) { throw IOException("start failed") }
        }
        assertEquals(0, resources.retainedProcesses)
        ApplicationHelperLease.start(10213, FakeControl(), resources) { FakeProcess() }.closeAndAwait()
    }

    private fun lease(process: Process, control: ApplicationProcessControl) =
        ApplicationHelperLease.start(10213, control, cleanup) { process }

    private fun ApplicationHelperLease.closeAndAwait() { close(); awaitCleanup() }

    private fun ApplicationHelperLease.awaitCleanup() {
        val started = System.nanoTime()
        while (!cleanupComplete && System.nanoTime() - started < 2_000_000_000L) Thread.sleep(1)
        assertTrue("Asynchronous cleanup did not finish", cleanupComplete)
    }

    private class BlockingRequestPipe : OutputStream() {
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val buffered = BufferedOutputStream(this, 8)
        override fun write(value: Int) { write(byteArrayOf(value.toByte())) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            entered.countDown()
            if (!unblock.await(3, TimeUnit.SECONDS)) throw IOException("Test writer was not released")
        }
        override fun close() { closed.countDown() }
    }

    private class FakeProcess(
        private val events: MutableList<String> = mutableListOf(),
        private val providedControl: OutputStream? = null
    ) : Process() {
        var exit: Int? = null
        var destroys = 0
        var controlCloses = 0
        var statusFailure: Exception? = null
        private val input = ByteArrayInputStream(ByteArray(0))
        private val error = ByteArrayInputStream(ByteArray(0))
        private val control = object : ByteArrayOutputStream() {
            override fun close() { events += "control-close"; controlCloses++ }
        }
        override fun getInputStream() = input
        override fun getErrorStream() = error
        override fun getOutputStream(): OutputStream = providedControl ?: control
        override fun exitValue(): Int {
            statusFailure?.let { throw it }
            return exit ?: throw IllegalThreadStateException()
        }
        override fun waitFor() = exitValue()
        override fun destroy() { destroys++; exit = 137 }
    }

    private class FakeControl(private val signal: () -> Unit = {}) : ApplicationProcessControl {
        var current: ApplicationProcessIdentity? = identity()
        var reads = 0
        var signals = 0
        var afterRead: () -> Unit = {}
        override fun identity(pid: Int): ApplicationProcessIdentity? {
            reads++
            return current.also { afterRead() }
        }
        override fun killGroup(pid: Int) { assertEquals(123, pid); signals++; signal() }
    }

    companion object {
        private fun hello() = HelperProtocol.Hello(HelperProtocol.VERSION, ByteArray(32), 10213, 10213, 123, 123)
        private fun identity(
            start: Long = 100, uid: Int = 10213, group: Int = 123, session: Int = 123, state: Char = 'S'
        ) = ApplicationProcessIdentity(uid, HelperSessionIdentity.Snapshot(123, group, session, start, state))
    }
}
