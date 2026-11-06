package com.nezhahq.agent.collector

import com.nezhahq.agent.collector.GeoIpCollector.FetchResult
import com.nezhahq.agent.collector.GeoIpCollector.IpFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import proto.Nezha.GeoIP
import proto.Nezha.IP

/** Each family owns its cache, deadline and failure budget; a slow IPv6 lookup cannot erase IPv4. */
internal class GeoIpLookup(
    private val fetch: suspend (IpFamily) -> FetchResult?,
    private val familyTimeoutMillis: Long = 4_000,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val ipv4 = FamilyState()
    private val ipv6 = FamilyState()

    suspend fun fetch(): GeoIP? = coroutineScope {
        val v4 = async { lookup(IpFamily.V4, ipv4) }
        val v6 = async { lookup(IpFamily.V6, ipv6) }
        val a = v4.await()
        val b = v6.await()
        if (a == null && b == null) null else GeoIP.newBuilder()
            .setIp(IP.newBuilder().apply {
                a?.let { setIpv4(it.ip) }
                b?.let { setIpv6(it.ip) }
            })
            .setCountryCode(a?.countryCode ?: b?.countryCode ?: "")
            .build()
    }

    private suspend fun lookup(family: IpFamily, state: FamilyState): FetchResult? = state.mutex.withLock {
        val now = nowMillis()
        state.cached?.takeIf { now - state.cachedAt in 0 until CACHE_TTL_MS }?.let { return@withLock it }
        if (state.failures >= FAILURE_THRESHOLD && now - state.failedAt in 0 until BACKOFF_MS) {
            return@withLock null
        }
        val result = try {
            withTimeoutOrNull(familyTimeoutMillis) { fetch(family) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
        if (result == null) {
            state.failures = (state.failures + 1).coerceAtMost(FAILURE_THRESHOLD)
            state.failedAt = nowMillis()
        } else {
            // Commit immediately, even if the other family is subsequently cancelled or times out.
            state.cached = result
            state.cachedAt = nowMillis()
            state.failures = 0
        }
        result
    }

    private class FamilyState {
        val mutex = Mutex()
        var cached: FetchResult? = null
        var cachedAt = 0L
        var failures = 0
        var failedAt = 0L
    }

    private companion object {
        const val CACHE_TTL_MS = 60_000L
        const val BACKOFF_MS = 30_000L
        const val FAILURE_THRESHOLD = 3
    }
}
