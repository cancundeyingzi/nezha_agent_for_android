package com.nezhahq.agent.util

import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the one liveness check both [RootShell] and [PersistentShell] now share.
 *
 * It reads like something [Process.isAlive] could replace, and twice it was copied rather than
 * shared. Neither substitution is safe: `isAlive()` is API 26+ while the app ships to API 23, and
 * it reports a running Shizuku shell as dead because that shell is a remote process whose
 * `exitValue()` signals "still running" with [IllegalStateException] instead of the
 * [IllegalThreadStateException] the JDK contract names. These cases fail if either detail is lost.
 */
class ProcessLivenessTest {

    @Test
    fun runningProcessIsAlive() {
        assertTrue(isProcessAlive(FakeProcess { throw IllegalThreadStateException() }))
    }

    @Test
    fun processThatReturnedAnExitCodeIsNotAlive() {
        assertFalse(isProcessAlive(FakeProcess { 0 }))
        assertFalse(isProcessAlive(FakeProcess { 137 }))
    }

    @Test
    fun runningShizukuShellIsAliveDespiteReportingIllegalState() {
        assertTrue(isProcessAlive(FakeProcess { throw IllegalStateException("Not exited") }))
    }

    /** Only [exitValue] matters here; the streams are never touched by the check under test. */
    private class FakeProcess(private val exitCode: () -> Int) : Process() {
        override fun exitValue(): Int = exitCode()

        override fun waitFor(): Int = exitCode()

        override fun destroy() = Unit

        override fun getOutputStream(): OutputStream = throw UnsupportedOperationException()

        override fun getInputStream(): InputStream = throw UnsupportedOperationException()

        override fun getErrorStream(): InputStream = throw UnsupportedOperationException()
    }
}
