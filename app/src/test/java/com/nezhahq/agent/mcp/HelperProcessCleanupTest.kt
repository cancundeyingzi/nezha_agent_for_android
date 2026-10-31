package com.nezhahq.agent.mcp

import com.nezhahq.agent.mcp.transport.HelperProcessCleanup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class HelperProcessCleanupTest {
    @Test fun fullAdmissionRetriesExistingExitProofWithoutAuthorizationOrAnotherNativeLaunch() {
        val cleanup = HelperProcessCleanup(4)
        val nativeStarts = AtomicInteger()
        val initialReads = CountDownLatch(4)
        val releaseInitial = CountDownLatch(1)
        val secondReads = CountDownLatch(4)
        val finalRead = CountDownLatch(1)
        val bindings = (0..3).map {
            val ticket = requireNotNull(cleanup.tryAcquire())
            val process = ExistingProcess().also { nativeStarts.incrementAndGet() }
            ticket.scheduleUntil {
                // Capture the old observation before the latch. A later exit must be proved by
                // a new cleanup attempt, not accidentally observed by the original attempt.
                val exited = try { process.exitValue(); true } catch (_: IllegalThreadStateException) { false }
                when (process.checks.incrementAndGet()) {
                    1 -> { initialReads.countDown(); check(releaseInitial.await(2, TimeUnit.SECONDS)) }
                    2 -> secondReads.countDown()
                    else -> finalRead.countDown()
                }
                exited
            }
            ticket to process
        }
        val tickets = bindings.map { it.first }
        val processes = bindings.map { it.second }
        try {
            assertTrue(initialReads.await(1, TimeUnit.SECONDS))
            processes.take(3).forEach { it.exit = 0 }
            // This is the same admission operation ordinary ApplicationHelperLauncher uses.
            assertNull(cleanup.tryAcquire())
            releaseInitial.countDown()
            assertTrue(secondReads.await(1, TimeUnit.SECONDS))
            await { tickets.take(3).all { it.released } }
            assertFalse(tickets.last().released)
            assertEquals(1, cleanup.retainedProcesses)
            assertEquals(4, nativeStarts.get())

            // Reserve the three free slots without starting native work, then make the last old
            // resource actually exit. A full ordinary admission again retries only owned work.
            val unused = (0..2).map { requireNotNull(cleanup.tryAcquire()) }
            processes.last().exit = 137
            assertNull(cleanup.tryAcquire())
            assertTrue(finalRead.await(1, TimeUnit.SECONDS))
            await { tickets.last().released }
            assertEquals(3, cleanup.retainedProcesses)
            unused.forEach { it.releaseUnused() }
            requireNotNull(cleanup.tryAcquire()).releaseUnused()
            assertEquals(0, cleanup.retainedProcesses)
            assertEquals(4, nativeStarts.get())
        } finally { releaseInitial.countDown() }
    }

    private class ExistingProcess : Process() {
        @Volatile var exit: Int? = null
        val checks = AtomicInteger()
        override fun exitValue() = exit ?: throw IllegalThreadStateException()
        override fun waitFor() = exitValue()
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun destroy() = error("Cleanup retries must not start or replace native resources")
    }

    private fun await(condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition()) {
            check(System.nanoTime() < until) { "Existing cleanup did not converge" }
            Thread.yield()
        }
    }
}
