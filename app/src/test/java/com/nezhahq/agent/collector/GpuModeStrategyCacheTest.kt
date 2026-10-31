package com.nezhahq.agent.collector

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class GpuModeStrategyCacheTest {

    @Test
    fun `normal unavailable does not prevent privileged probe`() = runBlocking {
        val cache = GpuModeStrategyCache()
        var privilegedProbes = 0

        val normal = cache.collect(
            isPrivileged = false,
            readCached = { error("normal strategy should not already be cached") },
            probe = {
                GpuProbeResult(GpuCollectionStrategy.UNAVAILABLE, emptyList())
            }
        )
        val privileged = cache.collect(
            isPrivileged = true,
            readCached = { error("privileged strategy should not already be cached") },
            probe = {
                privilegedProbes++
                GpuProbeResult(GpuCollectionStrategy.SHELL_FS, listOf(42.0))
            }
        )

        assertEquals(emptyList<Double>(), normal)
        assertEquals(listOf(42.0), privileged)
        assertEquals(1, privilegedProbes)
    }

    @Test
    fun `collector cache instances do not share strategy`() = runBlocking {
        val first = GpuModeStrategyCache()
        val second = GpuModeStrategyCache()
        var secondProbes = 0

        first.collect(
            isPrivileged = true,
            readCached = { error("first strategy should not already be cached") },
            probe = {
                GpuProbeResult(GpuCollectionStrategy.UNAVAILABLE, emptyList())
            }
        )
        val secondResult = second.collect(
            isPrivileged = true,
            readCached = { error("second strategy should not already be cached") },
            probe = {
                secondProbes++
                GpuProbeResult(GpuCollectionStrategy.DUMPSYS, listOf(73.0))
            }
        )

        assertEquals(listOf(73.0), secondResult)
        assertEquals(1, secondProbes)
    }

    @Test
    fun `switching mode uses each modes cached strategy`() = runBlocking {
        val cache = GpuModeStrategyCache()
        var normalProbes = 0
        var privilegedProbes = 0

        cache.collect(
            isPrivileged = false,
            readCached = { error("normal strategy should not already be cached") },
            probe = {
                normalProbes++
                GpuProbeResult(GpuCollectionStrategy.DIRECT, listOf(10.0))
            }
        )
        cache.collect(
            isPrivileged = true,
            readCached = { error("privileged strategy should not already be cached") },
            probe = {
                privilegedProbes++
                GpuProbeResult(GpuCollectionStrategy.DUMPSYS, listOf(20.0))
            }
        )

        val normalAgain = cache.collect(
            isPrivileged = false,
            readCached = { strategy ->
                assertEquals(GpuCollectionStrategy.DIRECT, strategy)
                listOf(11.0)
            },
            probe = { error("normal mode should use its cache") }
        )
        val privilegedAgain = cache.collect(
            isPrivileged = true,
            readCached = { strategy ->
                assertEquals(GpuCollectionStrategy.DUMPSYS, strategy)
                listOf(21.0)
            },
            probe = { error("privileged mode should use its cache") }
        )

        assertEquals(listOf(11.0), normalAgain)
        assertEquals(listOf(21.0), privilegedAgain)
        assertEquals(1, normalProbes)
        assertEquals(1, privilegedProbes)
    }

    @Test
    fun `privileged unavailable retries after bounded negative cache ttl`() = runBlocking {
        var nowMs = 1_000L
        var probes = 0
        val cache = GpuModeStrategyCache(
            monotonicTimeMs = { nowMs },
            privilegedUnavailableRetryMs = 30_000L
        )

        val revokedResult = cache.collect(
            isPrivileged = true,
            readCached = { error("privileged strategy should not already be cached") },
            probe = {
                probes++
                GpuProbeResult(GpuCollectionStrategy.UNAVAILABLE, emptyList())
            }
        )
        nowMs += 29_999L
        val throttledResult = cache.collect(
            isPrivileged = true,
            readCached = { strategy ->
                assertEquals(GpuCollectionStrategy.UNAVAILABLE, strategy)
                emptyList()
            },
            probe = { error("negative cache should throttle an early retry") }
        )
        nowMs += 1L
        val reauthorizedResult = cache.collect(
            isPrivileged = true,
            readCached = { error("expired negative cache should be re-probed") },
            probe = {
                probes++
                GpuProbeResult(GpuCollectionStrategy.SHELL_FS, listOf(64.0))
            }
        )

        assertEquals(emptyList<Double>(), revokedResult)
        assertEquals(emptyList<Double>(), throttledResult)
        assertEquals(listOf(64.0), reauthorizedResult)
        assertEquals(2, probes)
    }

    /**
     * A cached read that reports its strategy died must not deadlock, and must hand the next call
     * back to the probe. Reporting through the return value is the only safe channel: the cache
     * holds a non-reentrant mutex across `readCached`, so a call back into the cache would hang.
     */
    @Test
    fun `dead cached strategy is dropped and re-probed`() = runBlocking {
        val cache = GpuModeStrategyCache()
        var probes = 0

        cache.collect(
            isPrivileged = true,
            readCached = { error("strategy should not already be cached") },
            probe = {
                probes++
                GpuProbeResult(GpuCollectionStrategy.DIRECT, listOf(30.0))
            }
        )
        val afterDeadRead = cache.collect(
            isPrivileged = true,
            readCached = { null },
            probe = { error("the cached strategy is still valid on this call") }
        )
        val afterReprobe = cache.collect(
            isPrivileged = true,
            readCached = { error("the dead strategy should have been dropped") },
            probe = {
                probes++
                GpuProbeResult(GpuCollectionStrategy.SHELL_FS, listOf(55.0))
            }
        )

        assertEquals(emptyList<Double>(), afterDeadRead)
        assertEquals(listOf(55.0), afterReprobe)
        assertEquals(2, probes)
    }

    @Test
    fun `initial dumpsys probe result is reused within throttle interval`() = runBlocking {
        var nowMs = 10_000L
        var reads = 0
        val throttle = GpuDumpsysThrottle(
            monotonicTimeMs = { nowMs },
            throttleMs = 5_000L
        )

        throttle.recordInitial(37.0)
        nowMs += 2_000L
        val withinThrottle = throttle.read {
            reads++
            51.0
        }
        nowMs += 3_000L
        val afterThrottle = throttle.read {
            reads++
            51.0
        }

        assertEquals(37.0, withinThrottle ?: error("missing cached sample"), 0.0)
        assertEquals(51.0, afterThrottle ?: error("missing refreshed sample"), 0.0)
        assertEquals(1, reads)
    }
}
