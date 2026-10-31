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
    private val cleanup = HelperProcessCleanup(1)
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

    @Test fun delayedNativeExitIsObservedWithinTheSameCleanupOwnerWithoutAdmissionPressure() {
        val firstProbe = CountDownLatch(1)
        val finishFirstProbe = CountDownLatch(1)
        val process = FakeProcess().apply {
            afterCleanupCheck = { observation ->
                if (observation == 1) {
                    firstProbe.countDown()
                    check(finishFirstProbe.await(1, TimeUnit.SECONDS))
                }
            }
        }
        val lease = lease(process, FakeControl())
        lease.acceptIdentity(hello())
        lease.close()
        try {
            assertTrue(firstProbe.await(1, TimeUnit.SECONDS))
            assertFalse(lease.cleanupComplete)
            // The first exitValue observation is already captured as live. Only a later poll
            // in this owner's bounded window can see the real exit; no new admission is tried.
            process.exit = 137
            finishFirstProbe.countDown()
            lease.awaitCleanup()
            assertTrue(process.cleanupChecks.get() >= 2)
            assertEquals(0, cleanup.retainedProcesses)
            process.assertPipesClosedOnce()
        } finally { finishFirstProbe.countDown() }
    }

    @Test fun liveProcessOutlastsBoundedObservationAndIsReclaimedOnlyAfterExitAndAdmissionPressure() {
        val process = FakeProcess()
        val lease = lease(process, FakeControl())
        lease.acceptIdentity(hello())
        lease.close()
        process.awaitPipeCloseAttempts()
        // A real exit would open this latch. Keep the helper explicitly live beyond the 250 ms
        // observation window, then prove that its owner has stopped polling without a retry.
        assertFalse(process.exitObserved.await(500, TimeUnit.MILLISECONDS))
        val unexpectedProbe = CountDownLatch(1)
        process.afterCleanupCheck = { unexpectedProbe.countDown() }
        assertFalse(unexpectedProbe.await(50, TimeUnit.MILLISECONDS))
        assertFalse(lease.cleanupComplete)
        assertEquals(1, cleanup.retainedProcesses)
        process.exit = 137
        retryRetained(cleanup)
        lease.awaitCleanup()
        assertTrue(unexpectedProbe.await(1, TimeUnit.SECONDS))
        process.assertPipesClosedOnce()
    }

    @Test fun interruptedCleanupObservationKeepsItsTicketUntilALaterTrustedExit() {
        val interruptedProbe = CountDownLatch(1)
        val process = FakeProcess().apply {
            afterCleanupCheck = { Thread.currentThread().interrupt(); interruptedProbe.countDown() }
        }
        val lease = lease(process, FakeControl())
        lease.acceptIdentity(hello())
        lease.close()
        assertTrue(interruptedProbe.await(1, TimeUnit.SECONDS))
        process.awaitPipeCloseAttempts()
        assertFalse(lease.cleanupComplete)
        assertEquals(1, cleanup.retainedProcesses)
        process.afterCleanupCheck = {}
        process.exit = 137
        retryRetained(cleanup)
        lease.awaitCleanup()
        process.assertPipesClosedOnce()
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
            lease.close()
            process.awaitPipeCloseAttempts()
            assertFalse(lease.cleanupComplete)
            assertEquals(1, cleanup.retainedProcesses)
            assertEquals(0, control.signals)
            assertEquals(0, process.destroys)
            assertEquals(1, process.controlCloses) // Original helper's independent EOF cleanup.
            process.exit = 137
            retryRetained(cleanup)
            lease.awaitCleanup()
            assertEquals(2, control.reads) // Cleanup retries never inspect a numeric PID again.
            process.assertPipesClosedOnce()
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
        lease.close()
        process.awaitPipeCloseAttempts()
        assertFalse(lease.cleanupComplete)
        assertEquals(1, cleanup.retainedProcesses)
        assertEquals(0, control.signals)
        assertEquals(0, process.destroys)
        assertEquals(1, process.controlCloses)
        process.statusFailure = null
        process.exit = 137
        retryRetained(cleanup)
        lease.awaitCleanup()
        process.assertPipesClosedOnce()
    }

    @Test fun failedSignalStillClosesOriginalControlWithoutDestroyingAcceptedHelperFirst() {
        val process = FakeProcess()
        val control = FakeControl { throw IllegalStateException("signal failed") }
        val lease = lease(process, control)
        lease.acceptIdentity(hello())
        lease.close()
        process.awaitPipeCloseAttempts()
        assertFalse(lease.cleanupComplete)
        assertEquals(1, cleanup.retainedProcesses)
        assertEquals(1, control.signals)
        assertEquals(0, process.destroys)
        assertEquals(1, process.controlCloses)
        process.exit = 137
        retryRetained(cleanup)
        lease.awaitCleanup()
        process.assertPipesClosedOnce()
    }

    @Test fun fourLiveHelpersRetainAdmissionAfterGroupKillAndPipeCloseUntilTheirOriginalProcessExits() {
        val resources = HelperProcessCleanup(4)
        val starts = AtomicInteger()
        val processes = (0..3).map { FakeProcess() }
        val controls = (0..3).map { FakeControl() }
        val leases = processes.mapIndexed { index, process ->
            ApplicationHelperLease.start(10213, controls[index], resources) {
                starts.incrementAndGet()
                process
            }.also { it.acceptIdentity(hello()); it.close() }
        }
        try {
            processes.forEach { it.awaitPipeCloseAttempts() }
            assertEquals(4, resources.retainedProcesses)
            assertTrue(leases.none { it.cleanupComplete })
            val busy = assertThrows(HelperFailure::class.java) {
                ApplicationHelperLease.start(10213, FakeControl(), resources) { starts.incrementAndGet(); FakeProcess() }
            }
            assertEquals(com.nezhahq.agent.core.mcp.McpErrorCode.BUSY, busy.error.code)
            assertEquals(4, starts.get())

            processes.first().exit = 137
            retryRetained(resources)
            leases.first().awaitCleanup()
            assertEquals(3, resources.retainedProcesses)
            assertTrue(leases.drop(1).none { it.cleanupComplete })
            ApplicationHelperLease.start(10213, FakeControl(), resources) {
                starts.incrementAndGet()
                FakeProcess()
            }.closeAndAwait()
            assertEquals(5, starts.get())
            assertEquals(3, resources.retainedProcesses)
            controls.forEach { assertEquals(1, it.signals); assertEquals(2, it.reads) }
            processes.forEach { it.assertPipesClosedOnce() }
        } finally {
            processes.forEach { it.exit = 137 }
            retryRetained(resources)
            leases.forEach { it.awaitCleanup() }
        }
    }

    @Test fun failedPipeCloseRetainsResourceBundleEvenWhenOriginalProcessHasExited() {
        val process = FakeProcess().apply { controlCloseFailure = IOException("close failed") }
        val lease = lease(process, FakeControl())
        lease.acceptIdentity(hello())
        process.exit = 137
        lease.close()
        process.awaitPipeCloseAttempts()
        val checks = process.cleanupChecks.get()
        retryRetained(cleanup)
        process.awaitCleanupCheckAfter(checks)
        assertFalse(lease.cleanupComplete)
        assertEquals(1, cleanup.retainedProcesses)
        process.assertPipesClosedOnce()
    }

    @Test fun blockedBufferedRequestWriterReturnsPromptlyAndOnlyRealExitRestoresAdmission() {
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
                assertTrue(pipe.closed.await(1, TimeUnit.SECONDS))
                process.awaitPipeCloseAttempts()
                assertFalse(lease.cleanupComplete)
                assertEquals(1, resources.retainedProcesses)
                assertThrows(HelperFailure::class.java) {
                    ApplicationHelperLease.start(10213, control, resources) { starts.incrementAndGet(); FakeProcess() }
                }
                assertEquals(0, starts.get())
                process.exit = 137
                retryRetained(resources)
                lease.awaitCleanup()
                assertEquals(0, resources.retainedProcesses)
                val next = ApplicationHelperLease.start(10213, control, resources) { starts.incrementAndGet(); FakeProcess() }
                next.closeAndAwait()
                assertEquals(1, starts.get())
                process.assertPipesClosedOnce()
            } finally {
                pipe.unblock.countDown()
                writer.join(1000)
                process.exit = 137
                lease.close()
                retryRetained(resources)
                lease.awaitCleanup()
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

    private fun retryRetained(resources: HelperProcessCleanup) {
        val unused = mutableListOf<HelperProcessCleanup.Ticket>()
        try {
            while (true) unused += resources.tryAcquire() ?: break
        } finally {
            unused.forEach { it.releaseUnused() }
        }
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
        @Volatile var exit: Int? = null
        var destroys = 0
        var controlCloses = 0
        @Volatile var statusFailure: Exception? = null
        var controlCloseFailure: Exception? = null
        private var inputCloses = 0
        private var errorCloses = 0
        private val inputGets = AtomicInteger()
        private val errorGets = AtomicInteger()
        private val controlGets = AtomicInteger()
        private val pipeCloseAttempts = CountDownLatch(3)
        val cleanupChecks = AtomicInteger()
        val exitObserved = CountDownLatch(1)
        @Volatile var afterCleanupCheck: (Int) -> Unit = {}
        private val input = object : ByteArrayInputStream(ByteArray(0)) {
            override fun close() { inputCloses++; pipeCloseAttempts.countDown() }
        }
        private val error = object : ByteArrayInputStream(ByteArray(0)) {
            override fun close() { errorCloses++; pipeCloseAttempts.countDown() }
        }
        private val control = object : ByteArrayOutputStream() {
            override fun close() {
                try {
                    providedControl?.close()
                    events += "control-close"
                    controlCloses++
                    controlCloseFailure?.let { throw it }
                } finally { pipeCloseAttempts.countDown() }
            }
        }
        override fun getInputStream() = input.also { inputGets.incrementAndGet() }
        override fun getErrorStream() = error.also { errorGets.incrementAndGet() }
        override fun getOutputStream(): OutputStream = control.also { controlGets.incrementAndGet() }
        fun awaitPipeCloseAttempts() {
            assertTrue("Pipe close attempts did not finish", pipeCloseAttempts.await(1, TimeUnit.SECONDS))
            awaitCleanupCheckAfter(0)
        }
        fun awaitCleanupCheckAfter(previous: Int) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (cleanupChecks.get() <= previous && System.nanoTime() < until) Thread.sleep(1)
            assertTrue("Original Process exit was not checked", cleanupChecks.get() > previous)
        }
        fun assertPipesClosedOnce() {
            assertEquals(listOf(1, 1, 1), listOf(controlGets.get(), inputGets.get(), errorGets.get()))
            assertEquals(listOf(1, 1, 1), listOf(controlCloses, inputCloses, errorCloses))
        }
        override fun exitValue(): Int {
            val failure = statusFailure
            val result = exit
            if (pipeCloseAttempts.count == 0L) afterCleanupCheck(cleanupChecks.incrementAndGet())
            failure?.let { throw it }
            return (result ?: throw IllegalThreadStateException()).also { exitObserved.countDown() }
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
