package com.nezhahq.agent.util

import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the one liveness check both [RootShell] and [PersistentShell] now share.
 *
 * It reads like something [Process.isAlive] could replace, but that API is 26+ while the app ships
 * to API 23. A live remote Shizuku process is normalized to [IllegalThreadStateException] by
 * [RemoteShellProcess]; other runtime failures are transport errors and must not be swallowed.
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
    fun remoteTransportFailureIsNotMistakenForALiveProcess() {
        assertThrows(IllegalStateException::class.java) {
            isProcessAlive(FakeProcess { throw IllegalStateException("Binder disconnected") })
        }
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
