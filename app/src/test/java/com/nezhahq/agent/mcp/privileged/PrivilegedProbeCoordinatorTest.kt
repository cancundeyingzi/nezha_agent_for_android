package com.nezhahq.agent.mcp.privileged

import com.nezhahq.agent.core.mcp.McpErrorCode
import com.nezhahq.agent.mcp.transport.*
import com.nezhahq.agent.util.ShellBackendPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PrivilegedProbeCoordinatorTest {
    private val authorities = mutableListOf<PrivilegedHelperAuthority>()
    private val coordinators = mutableListOf<PrivilegedProbeCoordinator>()
    @After fun close() { authorities.forEach { it.close() }; coordinators.forEach { it.close() } }

    @Test fun fullTaskCapacityAndMaintenanceSuLaunchFailureDoNotRepeatPermissionRequests() {
        val fixture = fixture(0)
        fixture.gateway.start = { throw IOException("native launch denied") }
        fixture.reenable()
        fixture.authority.acquire().close()
        assertEquals(MaintenanceSlotState.UNKNOWN, fixture.completed.poll(2, TimeUnit.SECONDS))
        repeat(4) {
            val failure = assertThrows(HelperFailure::class.java) { fixture.authority.acquire() }
            assertEquals(McpErrorCode.CAPABILITY_UNAVAILABLE, failure.error.code)
        }
        assertEquals(1, fixture.gateway.rootStarts)
        assertEquals(4, fixture.cleanup.retainedProcesses)
        // The old failed su epoch cannot reject a fresh enable; the unknown maintenance owner
        // still correctly prevents another physical probe until application recovery.
        fixture.reenable()
        fixture.authority.acquire().close()
        assertEquals(1, fixture.gateway.rootStarts)
    }

    @Test fun normalSuExitWithoutSelectedUidProofRejectsOnlyItsCurrentEpoch() {
        val fixture = fixture(0)
        fixture.gateway.start = { ProbeProcess("") }
        fixture.reenable()
        fixture.authority.acquire().close()
        assertEquals(MaintenanceSlotState.IDLE, fixture.completed.poll(2, TimeUnit.SECONDS))
        repeat(3) { assertThrows(HelperFailure::class.java) { fixture.authority.acquire() } }
        assertEquals(1, fixture.gateway.rootStarts)
        fixture.reenable()
        fixture.authority.acquire().close()
        assertEquals(MaintenanceSlotState.IDLE, fixture.completed.poll(2, TimeUnit.SECONDS))
        assertEquals(2, fixture.gateway.rootStarts)
    }

    @Test fun provenPreNativeRejectionDoesNotLeaveAnUnknownMaintenanceSlot() {
        val fixture = fixture(0)
        fixture.gateway.start = { throw PrivilegedLaunchNotStarted() }
        fixture.authority.acquire().close()
        assertEquals(MaintenanceSlotState.IDLE, fixture.completed.poll(2, TimeUnit.SECONDS))
        assertThrows(HelperFailure::class.java) { fixture.authority.acquire() }
        fixture.reenable()
        fixture.authority.acquire().close()
        assertEquals(MaintenanceSlotState.IDLE, fixture.completed.poll(2, TimeUnit.SECONDS))
        assertEquals(2, fixture.gateway.rootStarts)
    }

    @Test fun readyButPendingFirstCandidateDoesNotStarveOtherTicketsOrRejectRoot() = runBlocking<Unit> {
        for (uid in listOf(0, 2000)) {
            val fixture = fixture(uid)
            val calls = AtomicInteger()
            val observed = mutableListOf<String>()
            fun process(): Process {
                val index = calls.getAndIncrement()
                val nonce = fixture.tasks[index].program.nonce
                observed += nonce
                val ready = "READY:$nonce:$uid\n"
                if (uid == 0 && index != 0) fixture.taskProcesses[index].exit = 137
                return ProbeProcess(if (index == 0) ready else ready + "CLEAN:$nonce\n")
            }
            if (uid == 0) fixture.gateway.start = ::process else fixture.service!!.start = { argv ->
                val probe = process()
                assertEquals(observed.last(), argv[argv.size - 4])
                probe
            }
            fixture.reenable()
            repeat(4) {
                fixture.authority.acquire().close()
                assertEquals(MaintenanceSlotState.IDLE, fixture.completed.poll(2, TimeUnit.SECONDS))
            }
            assertEquals(fixture.tasks.map { it.program.nonce }, observed)
            withTimeout(2_000) { while (fixture.cleanup.retainedProcesses != 1) yield() }
            assertFalse(fixture.tasks[0].cleanupComplete)
            assertTrue(fixture.tasks.drop(1).all { it.cleanupComplete })
            assertEquals(4, calls.get())
        }
    }

    @Test fun acceptedShizukuTicketsRecoverAllFourFromCleanRepliesAfterBinderReplacement() = runBlocking<Unit> {
        val fixture = fixture(2000)
        val starts = AtomicInteger()
        fixture.gateway.current = PrivilegedHelperAuthorityTest.FakeService().apply {
            start = { argv ->
                starts.incrementAndGet()
                val nonce = argv[argv.size - 4]
                ProbeProcess("READY:$nonce:2000\nCLEAN:$nonce\n")
            }
        }
        fixture.authority.serviceChanged()
        repeat(4) {
            fixture.authority.acquire().close()
            assertEquals(MaintenanceSlotState.IDLE, fixture.completed.poll(2, TimeUnit.SECONDS))
        }
        withTimeout(2_000) { while (fixture.cleanup.retainedProcesses != 0) yield() }
        assertEquals(4, starts.get())
        assertTrue(fixture.tasks.all { it.cleanupComplete })
    }

    @Test fun unknownMaintenanceExitRetainsItsSoleOwnerAcrossBinderReplacement() {
        val fixture = fixture(2000)
        val starts = AtomicInteger()
        val process = ProbeProcess("", unknownExit = true, destroyFails = true)
        fixture.service!!.start = { starts.incrementAndGet(); process }
        fixture.authority.acquire().close()
        assertEquals(MaintenanceSlotState.UNKNOWN, fixture.completed.poll(2, TimeUnit.SECONDS))
        val replacement = PrivilegedHelperAuthorityTest.FakeService().apply {
            start = { starts.incrementAndGet(); ProbeProcess("") }
        }
        fixture.gateway.current = replacement
        fixture.authority.serviceChanged()
        repeat(6) { fixture.authority.acquire().close() }
        assertEquals(1, starts.get())
        assertEquals(MaintenanceSlotState.UNKNOWN, fixture.coordinator.state)
        assertEquals(listOf(1, 1, 1), process.getters.toList())
        assertEquals(1, process.destroys)
        assertEquals(4, fixture.cleanup.retainedProcesses)
    }

    @Test fun partialPipeFailureAndUnknownDestroyNeverAdmitAnotherProbe() {
        val fixture = fixture(2000)
        val starts = AtomicInteger()
        val process = ProbeProcess("", unknownExit = true, destroyFails = true, failInputGetter = true)
        fixture.service!!.start = { starts.incrementAndGet(); process }
        fixture.authority.acquire().close()
        assertEquals(MaintenanceSlotState.UNKNOWN, fixture.completed.poll(2, TimeUnit.SECONDS))
        repeat(4) { fixture.authority.acquire().close() }
        assertEquals(1, starts.get())
        assertEquals(listOf(1, 1, 0), process.getters.toList())
        assertEquals(1, process.controlCloses)
        assertEquals(1, process.destroys)
    }

    private fun fixture(uid: Int): Fixture {
        val completed = ArrayBlockingQueue<MaintenanceSlotState>(16)
        val coordinator = PrivilegedProbeCoordinator({ completed.offer(it) }, exitWaitMillis = 20)
            .also { coordinators += it }
        val service = if (uid == 2000) PrivilegedHelperAuthorityTest.FakeService() else null
        val gateway = PrivilegedHelperAuthorityTest.FakeGateway(service)
        val policy = ShellBackendPolicy()
        val authority = PrivilegedHelperAuthority(gateway, policy, onLiveService = coordinator::authorized)
            .also { it.configure(true); authorities += it }
        val cleanup = HelperProcessCleanup(4)
        val taskProcesses = (0..3).map { UnknownTaskProcess(remote = uid != 0) }
        val tasks = (0..3).map { index ->
            val nonce = ByteArray(32) { index.toByte() }
            val program = TrustedHelperProgram("/data/app/test/base.apk", emptyMap(), HelperLaunchSpec(nonce, 1_000))
            PrivilegedHelperLease.start(authority.acquire(), program, cleanup) { taskProcesses[index] }.also {
                it.acceptIdentity(HelperProtocol.Hello(HelperProtocol.VERSION, nonce, uid, uid,
                    100 + index, 100 + index, 1000L + index))
            }
        }
        tasks.forEach { it.close(); coordinator.retain(it) }
        assertEquals(4, cleanup.retainedProcesses)
        assertNull(cleanup.tryAcquire())
        return Fixture(authority, policy, gateway, service, coordinator, completed, cleanup, tasks, taskProcesses)
    }

    private data class Fixture(
        val authority: PrivilegedHelperAuthority, val policy: ShellBackendPolicy,
        val gateway: PrivilegedHelperAuthorityTest.FakeGateway,
        val service: PrivilegedHelperAuthorityTest.FakeService?,
        val coordinator: PrivilegedProbeCoordinator,
        val completed: ArrayBlockingQueue<MaintenanceSlotState>,
        val cleanup: HelperProcessCleanup, val tasks: List<PrivilegedHelperLease>,
        val taskProcesses: List<UnknownTaskProcess>
    ) {
        fun reenable() { authority.configure(false); policy.reset(); authority.configure(true) }
    }

    private class UnknownTaskProcess(private val remote: Boolean) : Process() {
        @Volatile var exit: Int? = null
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun exitValue(): Int {
            exit?.let { return it }
            if (remote) throw IOException("old Binder unavailable")
            throw IllegalThreadStateException()
        }
        override fun waitFor() = exitValue()
        override fun destroy() = Unit
    }

    private class ProbeProcess(
        output: String, private val unknownExit: Boolean = false,
        private val destroyFails: Boolean = false, private val failInputGetter: Boolean = false
    ) : Process() {
        val getters = IntArray(3)
        var destroys = 0
        var controlCloses = 0
        private val input = ByteArrayInputStream(output.toByteArray(Charsets.US_ASCII))
        private val diagnostic = ByteArrayInputStream(ByteArray(0))
        private val control = object : ByteArrayOutputStream() {
            override fun close() { controlCloses++ }
        }
        override fun getOutputStream(): OutputStream { getters[0]++; return control }
        override fun getInputStream(): InputStream {
            getters[1]++
            if (failInputGetter) throw IOException("missing relay")
            return input
        }
        override fun getErrorStream(): InputStream { getters[2]++; return diagnostic }
        override fun exitValue(): Int { if (unknownExit) throw IOException("unknown exit"); return 0 }
        override fun waitFor() = exitValue()
        override fun destroy() { destroys++; if (destroyFails) throw IOException("unknown destroy") }
    }
}
