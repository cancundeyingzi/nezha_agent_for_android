package com.nezhahq.agent.mcp.privileged

import com.nezhahq.agent.mcp.transport.*
import com.nezhahq.agent.util.ShellBackendPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class PrivilegedHelperAuthorityTest {
    private val authorities = mutableListOf<PrivilegedHelperAuthority>()
    @After fun close() { authorities.forEach { it.close() } }

    @Test fun ordinaryModeNeverTouchesPrivilegedFactory() {
        val ordinary = object : HelperLauncher {
            override val backend = HelperBackend.APPLICATION
            override suspend fun launch(spec: HelperLaunchSpec): HelperProcessLease = error("unused")
        }
        assertSame(ordinary, helperLauncherForMode(false, { ordinary }, { error("Privileged discovery") }))
    }

    @Test fun disabledAuthorityDoesNotDiscoverServicesOrSu() {
        val gateway = FakeGateway()
        val authority = authority(gateway, enabled = false)
        assertThrows(HelperFailure::class.java) { authority.acquire() }
        assertEquals(0, gateway.discoveries)
        assertEquals(0, gateway.suDiscoveries)
    }

    @Test fun pendingOrRevokedShizukuRetainsItsSelectionWithoutSuFallback() {
        val service = FakeService().apply { granted = false }
        val gateway = FakeGateway(service)
        val authority = authority(gateway)
        assertThrows(HelperFailure::class.java) { authority.acquire() }
        assertTrue(service.checks.get() > 0)
        gateway.current = null
        authority.serviceChanged()
        assertThrows(HelperFailure::class.java) { authority.acquire() }
        assertEquals(0, gateway.rootStarts)
    }

    @Test fun firstDiscoveryFailureWithInstalledSuPinsShizukuEvenAfterServiceDisappears() {
        val gateway = FakeGateway().apply { discover = { throw java.io.IOException("discovery failed") } }
        val authority = authority(gateway)
        val failure = assertThrows(HelperFailure::class.java) { authority.acquire() }
        assertEquals(com.nezhahq.agent.core.mcp.McpErrorCode.CAPABILITY_UNAVAILABLE, failure.error.code)
        assertEquals(0, gateway.suDiscoveries)
        gateway.discover = null
        repeat(3) { assertThrows(HelperFailure::class.java) { authority.acquire() } }
        assertEquals(0, gateway.rootStarts)
    }

    @Test fun binderUidFailureReplacementAndDeathAreDiscoveryErrorsWithoutSuFallback() {
        for (mode in 0..3) {
            val original = Any()
            var token: Any? = original
            var alive = true
            var captures = 0
            val gateway = FakeGateway().apply {
                discover = {
                    capturePrivilegedService({ token }, { alive }, {
                        when (mode) {
                            0 -> throw IllegalStateException("UID lookup failed")
                            1 -> token = Any()
                            2 -> token = null
                            3 -> alive = false
                        }
                        2000
                    }, { _, uid -> captures++; FakeService(uid) })
                }
            }
            val authority = authority(gateway)
            assertThrows(HelperFailure::class.java) { authority.acquire() }
            assertEquals(0, captures)
            assertEquals(0, gateway.suDiscoveries)
            gateway.discover = null
            repeat(2) { assertThrows(HelperFailure::class.java) { authority.acquire() } }
            assertEquals(0, gateway.rootStarts)
        }
    }

    @Test fun observedBinderEventBeforeDiscoveryKeepsShizukuSelectedAfterDeath() {
        val gateway = FakeGateway(FakeService())
        val authority = authority(gateway)
        authority.serviceChanged()
        gateway.current = null
        assertThrows(HelperFailure::class.java) { authority.acquire() }
        assertEquals(0, gateway.rootStarts)
    }

    @Test fun lateDiscoveryFailureCannotPinShizukuInANewEnable() {
        val gateway = FakeGateway()
        val policy = ShellBackendPolicy()
        val authority = authority(gateway, policy)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        gateway.discover = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); throw java.io.IOException() }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val stale = executor.submit<Boolean> { runCatching { authority.acquire() }.isFailure }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            authority.configure(false)
            policy.reset()
            authority.configure(true)
            gateway.discover = null
            authority.acquire().use { assertEquals(HelperBackend.ROOT, it.backend) }
            release.countDown()
            assertTrue(stale.get(1, TimeUnit.SECONDS))
            authority.acquire().use { assertEquals(HelperBackend.ROOT, it.backend) }
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun liveRpcDenialOverridesAnOlderGrantAndRevokesRegisteredWork() {
        val service = FakeService()
        val authority = authority(FakeGateway(service))
        val permit = authority.acquire()
        val revoked = AtomicInteger()
        val callbackFinished = CountDownLatch(1)
        val callbackSawRevocation = AtomicBoolean(false)
        permit.register {
            callbackSawRevocation.set(runCatching { permit.requireFast() }.exceptionOrNull() is HelperFailure)
            revoked.incrementAndGet()
            callbackFinished.countDown()
        }
        service.granted = false
        assertThrows(HelperFailure::class.java) { permit.requireLive() }
        assertThrows(HelperFailure::class.java) { permit.requireFast() }
        assertTrue(callbackFinished.await(1, TimeUnit.SECONDS))
        assertEquals(1, revoked.get())
        assertTrue(callbackSawRevocation.get())
    }

    @Test fun liveDenialClosesFastGateWithoutWaitingForARevocationCallback() {
        val service = FakeService()
        val authority = authority(FakeGateway(service))
        val permit = authority.acquire()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val callbacks = AtomicInteger()
        permit.register {
            entered.countDown()
            try { check(release.await(2, TimeUnit.SECONDS)); callbacks.incrementAndGet() }
            finally { finished.countDown() }
        }
        service.granted = false
        val executor = Executors.newSingleThreadExecutor()
        try {
            val checking = executor.submit<Boolean> { runCatching { permit.requireLive() }.exceptionOrNull() is HelperFailure }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(checking.get(300, TimeUnit.MILLISECONDS))
            assertThrows(HelperFailure::class.java) { permit.requireFast() }
            assertEquals(0, callbacks.get())
            release.countDown()
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertEquals(1, callbacks.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun rpcTimeoutPublishesClosedFastGateAndInvokesRevocationExactlyOnce() {
        val service = FakeService()
        val authority = authority(FakeGateway(service), timeout = 60)
        val permit = authority.acquire()
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val callbacks = AtomicInteger()
        service.check = { release.await(2, TimeUnit.SECONDS); true }
        permit.register { callbacks.incrementAndGet(); finished.countDown() }
        try {
            assertThrows(HelperFailure::class.java) { permit.requireLive() }
            assertThrows(HelperFailure::class.java) { permit.requireFast() }
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertEquals(1, callbacks.get())
        } finally { release.countDown() }
    }

    @Test fun binderReplacementAndRootOffInvalidateOldPermitsAndLateGrant() {
        val old = FakeService()
        val gateway = FakeGateway(old)
        val authority = authority(gateway)
        val permit = authority.acquire()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        old.check = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); true }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val checking = executor.submit<Boolean> { runCatching { permit.requireLive() }.isSuccess }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            gateway.current = FakeService()
            authority.serviceChanged()
            authority.configure(false)
            authority.configure(true)
            release.countDown()
            assertFalse(checking.get(1, TimeUnit.SECONDS))
            assertThrows(HelperFailure::class.java) { permit.requireFast() }
            val fresh = authority.acquire()
            fresh.requireFast()
            fresh.close()
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun concurrentLiveChecksShareExactlyOneRpc() {
        val service = FakeService()
        val authority = authority(FakeGateway(service))
        val permit = authority.acquire()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        service.check = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); true }
        val baseline = service.checks.get()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { permit.requireLive() }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val second = executor.submit { permit.requireLive() }
            Thread.sleep(30)
            assertEquals(baseline + 1, service.checks.get())
            release.countDown()
            first.get(1, TimeUnit.SECONDS)
            second.get(1, TimeUnit.SECONDS)
        } finally { release.countDown(); executor.shutdownNow(); permit.close() }
    }

    @Test fun timeoutFailsClosedAndDoesNotCreateMoreRpcWorkers() {
        val service = FakeService()
        val authority = authority(FakeGateway(service), timeout = 60)
        val permit = authority.acquire()
        val release = CountDownLatch(1)
        service.check = { release.await(2, TimeUnit.SECONDS); true }
        try {
            assertThrows(HelperFailure::class.java) { permit.requireLive() }
            val checks = service.checks.get()
            repeat(4) { assertThrows(HelperFailure::class.java) { authority.acquire() } }
            assertEquals(checks, service.checks.get())
        } finally { release.countDown() }
    }

    @Test fun rootFailureOnlyRejectsItsOwnEnableAndPolicyEpoch() {
        val policy = ShellBackendPolicy()
        val authority = authority(FakeGateway(), policy)
        val old = authority.acquire()
        old.rejectRoot()
        assertThrows(HelperFailure::class.java) { authority.acquire() }
        authority.configure(false)
        policy.reset()
        authority.configure(true)
        val fresh = authority.acquire()
        old.rejectRoot()
        fresh.requireLive()
        authority.acquire().close()
        fresh.close()
    }

    @Test fun shutdownRevokesActivePermitBeforeAnyCallbackCanObserveItAsLive() {
        val authority = authority(FakeGateway())
        val permit = authority.acquire()
        val count = AtomicInteger()
        permit.register { assertThrows(HelperFailure::class.java) { permit.requireFast() }; count.incrementAndGet() }
        authority.revoke()
        authority.revoke()
        assertEquals(1, count.get())
        authority.acquire().close()
    }

    @Test fun newEnableSurvivesOldEpochsSlowCloseAndLateRootRejection() {
        val policy = ShellBackendPolicy()
        val authority = authority(FakeGateway(), policy)
        val old = authority.acquire()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        old.register { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); old.rejectRoot() }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val off = executor.submit { authority.configure(false) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            policy.reset()
            authority.configure(true)
            val fresh = authority.acquire()
            release.countDown()
            off.get(1, TimeUnit.SECONDS)
            fresh.requireLive()
            authority.acquire().close()
            fresh.close()
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun lateServiceDiscoveryFromOldEnableCannotOverwriteANewGrantedBinder() {
        val old = FakeService()
        val gateway = FakeGateway(old)
        val authority = authority(gateway)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        gateway.discover = { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); old }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val stale = executor.submit<Boolean> { runCatching { authority.acquire() }.isSuccess }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            authority.configure(false)
            authority.configure(true)
            gateway.current = FakeService()
            gateway.discover = null
            authority.serviceChanged()
            val fresh = authority.acquire()
            val revoked = AtomicInteger()
            fresh.register { revoked.incrementAndGet() }
            release.countDown()
            assertFalse(stale.get(1, TimeUnit.SECONDS))
            fresh.requireLive()
            assertEquals(0, revoked.get())
            fresh.close()
        } finally { release.countDown(); executor.shutdownNow() }
    }

    private fun authority(
        gateway: FakeGateway, policy: ShellBackendPolicy = ShellBackendPolicy(), enabled: Boolean = true,
        timeout: Long = 1_500
    ) = PrivilegedHelperAuthority(gateway, policy, timeout).also { authorities += it; it.configure(enabled) }

    internal class FakeService(override val uid: Int = 2000) : PrivilegedService {
        override val token = Any()
        @Volatile var granted = true
        @Volatile var alive = true
        @Volatile var check: (() -> Boolean)? = null
        var start: ((Array<String>) -> Process)? = null
        val checks = AtomicInteger()
        override fun isAlive() = alive
        override fun checkPermission(): Boolean { checks.incrementAndGet(); return check?.invoke() ?: granted }
        override fun launch(argv: Array<String>, environment: Array<String>, directory: String): Process =
            start?.invoke(argv) ?: error("unused")
    }

    internal class FakeGateway(@Volatile var current: PrivilegedService? = null) : PrivilegedGateway {
        var discoveries = 0
        var suDiscoveries = 0
        var rootStarts = 0
        @Volatile var discover: (() -> PrivilegedService?)? = null
        var start: () -> Process = { error("unused") }
        override fun service(): PrivilegedService? {
            discoveries++
            val callback = discover
            return if (callback == null) current else callback()
        }
        override fun executableSu(): String { suDiscoveries++; return "/system/xbin/su" }
        override fun launchSu(path: String, command: String, environment: Map<String, String>, directory: String): Process {
            rootStarts++
            return start()
        }
    }
}
