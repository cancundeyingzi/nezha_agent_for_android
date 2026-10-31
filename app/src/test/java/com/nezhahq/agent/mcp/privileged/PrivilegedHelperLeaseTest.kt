package com.nezhahq.agent.mcp.privileged

import com.nezhahq.agent.core.mcp.McpErrorCode
import com.nezhahq.agent.mcp.transport.*
import com.nezhahq.agent.util.ShellBackendPolicy
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PrivilegedHelperLeaseTest {
    private val authorities = mutableListOf<PrivilegedHelperAuthority>()
    @After fun close() { authorities.forEach { it.close() } }

    @Test fun streamsAreCapturedExactlyOnceAndAcceptedCloseUsesControlEof() {
        val (authority, _) = authority()
        val process = FakeProcess()
        val cleanup = HelperProcessCleanup(1)
        val lease = start(authority, process, cleanup)
        assertSame(lease.stdout, lease.stdout)
        assertSame(lease.stderr, lease.stderr)
        lease.acceptIdentity(hello())
        lease.beforeRequest()
        lease.close()
        lease.close()
        waitFor { lease.cleanupComplete }
        assertEquals(listOf(1, 1, 1), process.getters.toList())
        assertEquals(1, process.controlCloses)
        assertEquals(0, process.destroys)
        assertThrows(HelperFailure::class.java) { lease.stdin.write(42) }
    }

    @Test fun partialStreamFailureClosesAlreadyAcquiredPipesWithoutReenteringGetters() {
        val (authority, _) = authority()
        val process = FakeProcess().apply { failGetter = 2 }
        val cleanup = HelperProcessCleanup(1)
        assertThrows(IOException::class.java) { start(authority, process, cleanup) }
        waitFor { cleanup.retainedProcesses == 0 }
        assertEquals(listOf(1, 1, 0), process.getters.toList())
        assertEquals(1, process.controlCloses)
    }

    @Test fun rawGateRejectionBeforeNativeLaunchReleasesTheUnusedTaskTicket() {
        val (authority, _) = authority()
        val cleanup = HelperProcessCleanup(1)
        assertThrows(PrivilegedLaunchNotStarted::class.java) {
            PrivilegedHelperLease.start(authority.acquire(), program(), cleanup) { throw PrivilegedLaunchNotStarted() }
        }
        waitFor { cleanup.retainedProcesses == 0 }
    }

    @Test fun badUidNeverSendsAnyPayloadAndRejectsSuForTheCurrentEnable() = runBlocking<Unit> {
        val (authority, _) = authority()
        val process = FakeProcess()
        val launcher = object : HelperLauncher {
            override val backend = HelperBackend.ROOT
            override suspend fun launch(spec: HelperLaunchSpec): HelperProcessLease {
                process.bytes = ByteArrayOutputStream().also {
                    HelperProtocol.Writer(it).frame(HelperProtocol.Kind.HELLO, HelperProtocol.encodeHello(
                        hello().copy(nonce = spec.nonce, uid = 2000, effectiveUid = 2000)
                    ))
                }.toByteArray()
                return PrivilegedHelperLease.start(authority.acquire(), program(spec), HelperProcessCleanup(1)) { process }
            }
        }
        val result = McpHelperTransport().exchange(launcher, HelperCall(1, 15, "secret user payload"),
            HelperDeadline.afterMillis(1_000), 64) { AutoCloseable { } } as HelperExchangeResult.Failed
        assertEquals(McpErrorCode.CAPABILITY_UNAVAILABLE, result.error!!.code)
        assertEquals(0, process.sent.size())
        assertThrows(HelperFailure::class.java) { authority.acquire() }
    }

    @Test fun unknownRemoteExitRetainsCapacityUntilTrustedReconciliation() {
        val (authority, _) = authority(PrivilegedHelperAuthorityTest.FakeService(uid = 0))
        val process = FakeProcess().apply { unknownExit = true }
        val cleanup = HelperProcessCleanup(1)
        val lease = start(authority, process, cleanup)
        lease.acceptIdentity(hello())
        lease.close()
        waitFor { lease.needsProbe }
        assertEquals(1, cleanup.retainedProcesses)
        assertFalse(lease.cleanupComplete)
        var launched = false
        val failure = assertThrows(HelperFailure::class.java) {
            PrivilegedHelperLease.start(authority.acquire(), program(), cleanup) { launched = true; FakeProcess() }
        }
        assertEquals(McpErrorCode.BUSY, failure.error.code)
        assertFalse(launched)
        lease.reconciled()
        waitFor { lease.cleanupComplete }
        assertEquals(0, cleanup.retainedProcesses)
        assertEquals(0, process.destroys)
    }

    @Test fun pendingSuWithoutHelloCannotUseAnEmptyNonceScanAsExitProof() {
        assertRootCleanDoesNotProveSuExit(acceptHello = false)
    }

    @Test fun acceptedRootHelperCleanDoesNotReleaseALiveLocalSu() {
        assertRootCleanDoesNotProveSuExit(acceptHello = true)
    }

    @Test fun shizukuAdbAuthorizationRechecksOldRootExitWithoutLaunchingNativeWork() {
        val (root, _) = authority()
        val process = FakeProcess().apply { remainAliveOnControlClose = true }
        val firstRead = CountDownLatch(1)
        val allowFirst = CountDownLatch(1)
        val secondRead = CountDownLatch(1)
        val allowSecond = CountDownLatch(1)
        process.onStatusRead = { count ->
            if (count <= 2) {
                (if (count == 1) firstRead else secondRead).countDown()
                check((if (count == 1) allowFirst else allowSecond).await(2, TimeUnit.SECONDS))
                // End this cleanup attempt after its captured ALIVE result. The next attempt
                // must come from the new authorization, rather than the old polling loop.
                Thread.currentThread().interrupt()
            }
        }
        val cleanup = HelperProcessCleanup(1)
        val lease = start(root, process, cleanup)
        lease.acceptIdentity(hello())
        lease.close()
        val coordinator = PrivilegedProbeCoordinator()
        try {
            assertTrue(firstRead.await(1, TimeUnit.SECONDS))
            lease.reconciled()
            allowFirst.countDown()
            assertTrue(secondRead.await(1, TimeUnit.SECONDS))
            assertFalse(lease.cleanupComplete)
            assertFalse(lease.canProbe)
            process.exit = 137
            val nativeStarts = AtomicInteger()
            val service = PrivilegedHelperAuthorityTest.FakeService(uid = 2000).apply {
                start = { nativeStarts.incrementAndGet(); error("No maintenance native launch expected") }
            }
            val (shizuku, gateway) = authority(service)
            coordinator.retain(lease)
            shizuku.acquire().use { coordinator.authorized(it) }
            allowSecond.countDown()
            waitFor { lease.cleanupComplete }
            assertEquals(0, cleanup.retainedProcesses)
            assertTrue(process.statusReads.get() >= 3)
            assertEquals(0, nativeStarts.get())
            assertEquals(0, gateway.rootStarts)
        } finally { allowFirst.countDown(); allowSecond.countDown(); coordinator.close(); lease.close() }
    }

    private fun assertRootCleanDoesNotProveSuExit(acceptHello: Boolean) {
        val (authority, _) = authority()
        val process = FakeProcess().apply { remainAliveOnControlClose = true }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        process.onFirstStatusRead = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) }
        val cleanup = HelperProcessCleanup(1)
        val lease = start(authority, process, cleanup)
        if (acceptHello) lease.acceptIdentity(hello())
        lease.close()
        val coordinator = PrivilegedProbeCoordinator()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val before = process.statusReads.get()
            lease.reconciled() // Even a CLEAN claim has no evidence about pending/local su exit.
            release.countDown()
            waitFor { lease.cleanupComplete || process.statusReads.get() >= before + 3 }
            assertFalse(lease.cleanupComplete)
            assertEquals(1, cleanup.retainedProcesses)
            assertFalse(lease.canProbe)
            assertThrows(IllegalThreadStateException::class.java) { process.exitValue() }
            coordinator.retain(lease)
            val (fresh, gateway) = this.authority()
            fresh.acquire().use { coordinator.authorized(it) }
            assertEquals(0, gateway.rootStarts)
            assertEquals(MaintenanceSlotState.IDLE, coordinator.state)
            // Later actual su exit, observed through the same Process, can release the bundle.
            process.exit = 137
            lease.retryLocalExitCheck()
            waitFor { lease.cleanupComplete }
            assertEquals(0, cleanup.retainedProcesses)
        } finally { release.countDown(); coordinator.close(); lease.close() }
    }

    @Test fun cancellationBeforeNativeLaunchReturnsStillClosesTheLateOwnedLease() = runBlocking {
        val (authority, gateway) = authority()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val process = FakeProcess()
        gateway.start = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); process }
        val launcher = PrivilegedHelperLauncher("/data/app/test/base.apk", emptyMap(), authority, gateway)
        val job = async(Dispatchers.Default) { launcher.launch(HelperLaunchSpec(ByteArray(32), 2_000)) }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            job.cancelAndJoin()
            release.countDown()
            waitFor { process.controlCloses == 1 }
            assertEquals(0, process.sent.size())
        } finally { release.countDown() }
    }

    @Test fun closeNeverWaitsForABufferedRequestWriterAndRetainsItsTicketUntilUnblocked() {
        val (authority, _) = authority()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val raw = object : OutputStream() {
            override fun write(value: Int) { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) = write(0)
        }
        val process = FakeProcess(BufferedOutputStream(raw, 8))
        val cleanup = HelperProcessCleanup(1)
        val lease = start(authority, process, cleanup)
        lease.acceptIdentity(hello())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writer = executor.submit { lease.stdin.write(ByteArray(32)) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            executor.submit { authority.configure(false) }.get(300, TimeUnit.MILLISECONDS)
            assertFalse(lease.cleanupComplete)
            assertNull(cleanup.tryAcquire())
            assertEquals(0, process.destroys)
            release.countDown()
            writer.get(1, TimeUnit.SECONDS)
            waitFor { lease.cleanupComplete }
            assertEquals(1, process.controlCloses)
        } finally { release.countDown(); executor.shutdownNow(); lease.close() }
    }

    @Test fun trustedProxySavesInputBeforeBackgroundingAndClosesBothFd3Copies() {
        val program = TrustedHelperProgram("/data/app/local'quoted/base.apk", mapOf(
            "BOOTCLASSPATH" to "/apex/runtime.jar", "LD_PRELOAD" to "must-not-survive", "TOKEN" to "secret"
        ), HelperLaunchSpec(ByteArray(32), 1_000))
        assertTrue(program.script.contains(" <&3 3<&- &\nchild=\$!\nexec 3<&-\nwait \"\$child\""))
        assertTrue(program.script.startsWith("exec 3<&0\n/system/bin/env -i "))
        assertFalse(program.script.contains("<&0 &"))
        assertFalse(program.script.contains("exec /system/bin/app_process"))
        assertFalse(program.script.contains("exec /system/bin/env"))
        assertFalse(program.script.contains("2>&1"))
        assertFalse(program.script.contains("LD_PRELOAD"))
        assertFalse(program.script.contains("TOKEN"))
        assertEquals("/data/local/tmp", program.environment["TMPDIR"])
        assertEquals("/system/bin/sh", program.argv[0])
        assertTrue(program.suCommand.startsWith("'/system/bin/sh' '-c' "))
        assertTrue(program.script.contains("'\\''"))
    }

    private fun authority(service: PrivilegedService? = null): Pair<PrivilegedHelperAuthority, PrivilegedHelperAuthorityTest.FakeGateway> {
        val gateway = PrivilegedHelperAuthorityTest.FakeGateway(service)
        val authority = PrivilegedHelperAuthority(gateway, ShellBackendPolicy()).also {
            it.configure(true); authorities += it
        }
        return authority to gateway
    }
    private fun start(authority: PrivilegedHelperAuthority, process: FakeProcess, cleanup: HelperProcessCleanup) =
        PrivilegedHelperLease.start(authority.acquire(), program(), cleanup) { process }

    private class FakeProcess(private val providedControl: OutputStream? = null) : Process() {
        val getters = IntArray(3)
        val sent = ByteArrayOutputStream()
        var bytes = ByteArray(0)
        var failGetter = 0
        @Volatile var controlCloses = 0
        @Volatile var destroys = 0
        @Volatile var exit: Int? = null
        var unknownExit = false
        var remainAliveOnControlClose = false
        var onFirstStatusRead: (() -> Unit)? = null
        var onStatusRead: ((Int) -> Unit)? = null
        val statusReads = AtomicInteger()
        private val firstStatusRead = AtomicBoolean(true)
        private val control = object : OutputStream() {
            override fun write(value: Int) { if (providedControl == null) sent.write(value) else providedControl.write(value) }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (providedControl == null) sent.write(buffer, offset, length) else providedControl.write(buffer, offset, length)
            }
            override fun close() {
                providedControl?.close()
                controlCloses++
                if (!remainAliveOnControlClose) exit = 137
            }
        }
        override fun getOutputStream(): OutputStream { getters[0]++; fail(1); return control }
        override fun getInputStream(): InputStream { getters[1]++; fail(2); return ByteArrayInputStream(bytes) }
        override fun getErrorStream(): InputStream { getters[2]++; fail(3); return ByteArrayInputStream(ByteArray(0)) }
        override fun exitValue(): Int {
            val observed = exit
            val count = statusReads.incrementAndGet()
            if (firstStatusRead.compareAndSet(true, false)) onFirstStatusRead?.invoke()
            onStatusRead?.invoke(count)
            if (unknownExit) throw IOException("Binder unavailable")
            return observed ?: throw IllegalThreadStateException()
        }
        override fun waitFor() = exitValue()
        override fun destroy() { destroys++; exit = 137 }
        private fun fail(index: Int) { if (failGetter == index) throw IOException("stream unavailable") }
    }

    companion object {
        private fun hello() = HelperProtocol.Hello(HelperProtocol.VERSION, ByteArray(32), 0, 0, 123, 123, 100)
        private fun program(spec: HelperLaunchSpec = HelperLaunchSpec(ByteArray(32), 1_000)) =
            TrustedHelperProgram("/data/app/test/base.apk", emptyMap(), spec)
        private fun waitFor(test: () -> Boolean) {
            val until = System.nanoTime() + 2_000_000_000L
            while (!test()) {
                check(System.nanoTime() < until) { "Bounded cleanup did not converge" }
                Thread.sleep(2)
            }
        }
    }
}
