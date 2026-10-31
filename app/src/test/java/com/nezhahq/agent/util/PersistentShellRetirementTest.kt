package com.nezhahq.agent.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentShellRetirementTest {
    @Test
    fun detachedReferenceCannotReopenAfterRetirementAndShutdown() {
        val marker = ShellMarker("retired_completed_session")
        val process = FakeProcess(frame("before", marker))
        val starts = AtomicInteger()
        val shell = PersistentShell(
            sessionFactory = {
                starts.incrementAndGet()
                ShellSession(process, "old")
            },
            markerFactory = { marker }
        )
        val detachedReference = shell
        try {
            assertEquals("before", shell.execute("before-retirement", timeoutMs = 1_000))
            val writtenBeforeRetirement = process.writtenUtf8()

            shell.retire()
            shell.shutdown()

            assertEquals("", detachedReference.execute("late-command", timeoutMs = 1_000))
            assertEquals(1, starts.get())
            assertEquals(writtenBeforeRetirement, process.writtenUtf8())
            assertEquals(1, process.destroyCalls.get())
            assertFalse(detachedReference.isAlive())
            assertNull(detachedReference.sessionType())
        } finally {
            shell.shutdown()
        }
    }

    @Test(timeout = 30_000)
    fun retirementDoesNotWaitForFactoryAndDestroysItsLateSession() {
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val marker = ShellMarker("retired_factory_session")
        val process = FakeProcess(frame("unexpected", marker))
        val starts = AtomicInteger()
        val markers = AtomicInteger()
        val executors = AtomicInteger()
        val shell = PersistentShell(
            sessionFactory = {
                starts.incrementAndGet()
                factoryEntered.countDown()
                check(releaseFactory.await(10, TimeUnit.SECONDS))
                ShellSession(process, "late")
            },
            markerFactory = { markers.incrementAndGet(); marker },
            executorFactory = { executors.incrementAndGet(); newShellReaderExecutor() }
        )
        val callers = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "test-shell-retirement").apply { isDaemon = true }
        }
        try {
            val command = callers.submit<String> { shell.execute("late-command", timeoutMs = 120_000) }
            assertTrue(factoryEntered.await(5, TimeUnit.SECONDS))

            // The factory owns the shell lock. Retirement must complete before it is released.
            callers.submit { shell.retire() }.get(5, TimeUnit.SECONDS)
            assertFalse(command.isDone)
            assertEquals(0, process.destroyCalls.get())

            releaseFactory.countDown()
            assertEquals("", command.get(5, TimeUnit.SECONDS))
            assertEquals(1, process.destroyCalls.get())
            assertEquals("", process.writtenUtf8())
            assertEquals(0, markers.get())
            assertEquals(0, executors.get())
            assertFalse(shell.isAlive())
            assertNull(shell.sessionType())
            assertEquals("", shell.execute("another-late-command", timeoutMs = 1_000))
            assertEquals(1, starts.get())
        } finally {
            releaseFactory.countDown()
            callers.shutdownNow()
            shell.shutdown()
        }
    }

    private fun frame(output: String, marker: ShellMarker): ByteArray =
        (output + marker.prefix + "0" + marker.suffix).toByteArray(Charsets.UTF_8)

    private class FakeProcess(payload: ByteArray) : Process() {
        private val input = ByteArrayInputStream(payload)
        private val output = ByteArrayOutputStream()
        private val error = ByteArrayInputStream(byteArrayOf())
        val destroyCalls = AtomicInteger()
        @Volatile private var alive = true

        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = output
        override fun getErrorStream(): InputStream = error
        override fun waitFor(): Int = throw UnsupportedOperationException()
        override fun exitValue(): Int {
            if (alive) throw IllegalThreadStateException("still running")
            return 0
        }
        override fun destroy() {
            destroyCalls.incrementAndGet()
            alive = false
        }
        fun writtenUtf8(): String = output.toString(Charsets.UTF_8.name())
    }
}
