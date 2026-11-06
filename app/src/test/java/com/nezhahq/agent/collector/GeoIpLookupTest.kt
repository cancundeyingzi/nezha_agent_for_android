package com.nezhahq.agent.collector

import com.nezhahq.agent.collector.GeoIpCollector.FetchResult
import com.nezhahq.agent.collector.GeoIpCollector.IpFamily
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class GeoIpLookupTest {
    @Test fun eitherSuccessfulFamilySurvivesTheOtherFamilysDeadline() = runBlocking {
        for (fast in IpFamily.values()) {
            var stopped = false
            val lookup = GeoIpLookup(fetch = { family ->
                if (family == fast) answer(family) else try { awaitCancellation() } finally { stopped = true }
            }, familyTimeoutMillis = 50)
            val result = withTimeout(5_000) { lookup.fetch() }!!
            assertEquals(if (fast == IpFamily.V4) V4 else "", result.ip.ipv4)
            assertEquals(if (fast == IpFamily.V6) V6 else "", result.ip.ipv6)
            assertTrue(stopped)
        }
    }

    @Test fun timedOutFamiliesRecordFailuresAndActuallyBackOff() = runBlocking {
        var now = 1_000L
        val calls = mutableMapOf<IpFamily, Int>()
        val lookup = GeoIpLookup(fetch = { family ->
            calls[family] = calls.getOrDefault(family, 0) + 1
            awaitCancellation()
        }, familyTimeoutMillis = 25, nowMillis = { now })
        repeat(3) { assertNull(withTimeout(5_000) { lookup.fetch() }) }
        assertNull(lookup.fetch())
        assertEquals(mapOf(IpFamily.V4 to 3, IpFamily.V6 to 3), calls)
        now += 30_000
        assertNull(withTimeout(5_000) { lookup.fetch() })
        assertEquals(mapOf(IpFamily.V4 to 4, IpFamily.V6 to 4), calls)
    }

    @Test fun cancellationPreservesTheCompletedFamilyWithoutCountingTheCancelledFamilyAsFailure() = runBlocking {
        var v4Calls = 0
        var v6Calls = 0
        var blockV6 = true
        var entered = CompletableDeferred<Unit>()
        val lookup = GeoIpLookup(fetch = { family ->
            if (family == IpFamily.V4) { v4Calls++; answer(family) }
            else {
                v6Calls++
                entered.complete(Unit)
                if (blockV6) awaitCancellation() else answer(family)
            }
        })
        repeat(4) {
            entered = CompletableDeferred()
            val running = launch { lookup.fetch(); fail("cancelled lookup returned") }
            try { withTimeout(5_000) { entered.await() } }
            finally { withTimeout(5_000) { running.cancelAndJoin() } }
        }
        blockV6 = false
        val result = lookup.fetch()!!
        assertEquals(V4, result.ip.ipv4)
        assertEquals(V6, result.ip.ipv6)
        assertEquals(1, v4Calls)
        assertEquals(5, v6Calls)
    }

    @Test fun oneFamilyExceptionDoesNotCancelTheSuccessfulFamily() = runBlocking {
        val lookup = GeoIpLookup(fetch = { family ->
            if (family == IpFamily.V4) throw IOException("unavailable") else answer(family)
        })
        assertEquals(V6, lookup.fetch()!!.ip.ipv6)
    }

    @Test fun overlappingLookupsShareEachFreshFamilyAndRefreshAfterExpiry() = runBlocking {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        var now = 10L
        val lookup = GeoIpLookup(fetch = { family -> calls++; release.await(); answer(family) }, nowMillis = { now })
        val first = async { lookup.fetch() }
        val second = async { lookup.fetch() }
        release.complete(Unit)
        assertEquals(first.await(), second.await())
        assertEquals(2, calls)
        now += 60_000
        lookup.fetch()
        assertEquals(4, calls)
    }

    private fun answer(family: IpFamily) = FetchResult(if (family == IpFamily.V4) V4 else V6, "ZZ")
    private companion object {
        const val V4 = "192.0.2.1"
        const val V6 = "2001:db8::1"
    }
}
