package com.nezhahq.agent.device

import android.os.Binder
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nezhahq.agent.mcp.privileged.PrivilegedGateway
import com.nezhahq.agent.mcp.privileged.PrivilegedHelperAuthority
import com.nezhahq.agent.mcp.privileged.PrivilegedService
import com.nezhahq.agent.mcp.transport.HelperBackend
import com.nezhahq.agent.mcp.transport.HelperFailure
import com.nezhahq.agent.util.ShellBackendPolicy
import java.io.DataInputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Injected grant/Binder boundaries. These are explicitly NOT real SukiSU/Shizuku manager tests. */
@RunWith(AndroidJUnit4::class)
class SimulatedPrivilegeRevocationRegressionTest {
    @get:Rule val evidence = DeviceEvidenceRule()

    @Test fun simulatedRootModeRevocationClosesRegisteredApplicationUidProcessExactlyOnce() {
        val gateway = SimulatedGateway()
        PrivilegedHelperAuthority(gateway, ShellBackendPolicy()).use { authority ->
            authority.configure(true)
            authority.acquire().use { permit ->
                assertEquals(HelperBackend.ROOT, permit.backend)
                OrdinaryProcess().use { process ->
                    val closed = AtomicInteger()
                    permit.register { closed.incrementAndGet(); process.stop() }.use {
                        authority.configure(false)
                        authority.configure(false)
                        assertThrows(HelperFailure::class.java) { permit.requireFast() }
                        assertThrows(HelperFailure::class.java) { authority.acquire() }
                        process.awaitExit()
                        assertEquals(1, closed.get())
                        assertEquals(0, gateway.privilegedLaunches.get())
                    }
                }
            }
        }
    }

    @Test fun simulatedLiveShizukuDenialClosesRegisteredProcessAndNeverFallsBackToRoot() {
        val service = SimulatedService()
        val gateway = SimulatedGateway(service)
        PrivilegedHelperAuthority(gateway, ShellBackendPolicy()).use { authority ->
            authority.configure(true)
            authority.acquire().use { permit ->
                assertEquals(HelperBackend.SHIZUKU, permit.backend)
                OrdinaryProcess().use { process ->
                    val closed = AtomicInteger()
                    permit.register { closed.incrementAndGet(); process.stop() }.use {
                        service.granted = false
                        assertThrows(HelperFailure::class.java) { permit.requireLive() }
                        assertThrows(HelperFailure::class.java) { permit.requireFast() }
                        process.awaitExit()
                        gateway.current = null
                        authority.serviceChanged()
                        assertThrows(HelperFailure::class.java) { authority.acquire() }
                        assertEquals(1, closed.get())
                        assertEquals(0, gateway.privilegedLaunches.get())
                    }
                }
            }
        }
    }

    @Test fun simulatedShizukuBinderReplacementInvalidatesOldPermitBeforeFreshGrant() {
        val original = SimulatedService()
        val gateway = SimulatedGateway(original)
        PrivilegedHelperAuthority(gateway, ShellBackendPolicy()).use { authority ->
            authority.configure(true)
            authority.acquire().use { stale ->
                OrdinaryProcess().use { process ->
                    stale.register(process::stop).use {
                        val replacement = SimulatedService()
                        assertNotSame(original.token, replacement.token)
                        gateway.current = replacement
                        authority.serviceChanged()
                        assertThrows(HelperFailure::class.java) { stale.requireLive() }
                        process.awaitExit()
                        authority.acquire().use { fresh ->
                            assertEquals(HelperBackend.SHIZUKU, fresh.backend)
                            fresh.requireLive()
                            assertSame(replacement.token, fresh.service!!.token)
                        }
                        assertEquals(0, gateway.privilegedLaunches.get())
                    }
                }
            }
        }
    }

    private class SimulatedGateway(@Volatile var current: SimulatedService? = null) : PrivilegedGateway {
        val privilegedLaunches = AtomicInteger()
        override fun service(): PrivilegedService? = current
        override fun serviceToken(): Any? = current?.token
        override fun executableSu() = "/synthetic/never-executed/su"
        override fun launchSu(path: String, command: String, environment: Map<String, String>, directory: String): Process {
            privilegedLaunches.incrementAndGet()
            error("Simulation must never launch su")
        }
    }

    private class SimulatedService : PrivilegedService {
        override val token = Binder()
        override val uid = 2000
        @Volatile var granted = true
        override fun isAlive() = token.isBinderAlive
        override fun checkPermission() = granted
        override fun launch(argv: Array<String>, environment: Array<String>, directory: String): Process =
            error("Simulation must never invoke a privileged process service")
    }

    /** A real ordinary-UID OS process and real pipes, while authorization decisions stay simulated. */
    private class OrdinaryProcess : AutoCloseable {
        private val process = ProcessBuilder("/system/bin/sh", "-c", "/system/bin/id -u; printf 'ready\\n'; read ignored").start()

        init {
            try {
                val expected = "${android.os.Process.myUid()}\nready\n".toByteArray()
                awaitCondition("ordinary process startup", 5_000) {
                    check(running()) { "Ordinary process exited before readiness" }
                    process.inputStream.available() >= expected.size
                }
                val actual = ByteArray(expected.size)
                DataInputStream(process.inputStream).readFully(actual)
                assertArrayEquals(expected, actual)
            } catch (failure: Throwable) {
                close()
                throw failure
            }
        }

        fun stop() = process.destroy()
        fun awaitExit() = awaitCondition("revocation closed ordinary process", 5_000) { !running() }
        private fun running(): Boolean = try { process.exitValue(); false } catch (_: IllegalThreadStateException) { true }
        override fun close() {
            process.destroy()
            process.outputStream.close()
            process.inputStream.close()
            process.errorStream.close()
            awaitExit()
        }
    }
}
