package com.nezhahq.agent.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GpuHardwareInfoCacheTest {
    @Test
    fun concurrentCallersShareOneCompleteHardwareInfoPair() {
        val cache = GpuHardwareInfoCache()
        val callerCount = 12
        val executor = Executors.newFixedThreadPool(callerCount)
        val ready = CountDownLatch(callerCount)
        val start = CountDownLatch(1)
        val queryStarted = CountDownLatch(1)
        val finishQuery = CountDownLatch(1)
        val queryCount = AtomicInteger()

        try {
            val results = (1..callerCount).map { caller ->
                executor.submit(Callable {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    cache.getOrQuery {
                        queryCount.incrementAndGet()
                        queryStarted.countDown()
                        check(finishQuery.await(5, TimeUnit.SECONDS))
                        "Renderer $caller" to "Vendor $caller"
                    }
                })
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(queryStarted.await(5, TimeUnit.SECONDS))
            finishQuery.countDown()

            val hardwareInfo = results.map { it.get(5, TimeUnit.SECONDS) }
            val first = hardwareInfo.first()!!
            assertEquals(1, queryCount.get())
            assertEquals(first.first.removePrefix("Renderer "), first.second.removePrefix("Vendor "))
            hardwareInfo.forEach { assertSame(first, it) }
            assertSame(first, cache.getOrQuery { error("Success must be reused") })
        } finally {
            start.countDown()
            finishQuery.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun nullQueryCanRetryAndOnlySuccessIsCached() {
        val cache = GpuHardwareInfoCache()
        val expected = "Renderer" to "Vendor"
        var queryCount = 0

        assertNull(cache.getOrQuery {
            queryCount++
            null
        })
        assertSame(expected, cache.getOrQuery {
            queryCount++
            expected
        })
        assertSame(expected, cache.getOrQuery { error("Success must be reused") })
        assertEquals(2, queryCount)
    }

    @Test
    fun thrownQueryCanRetryWithoutCachingFailure() {
        val cache = GpuHardwareInfoCache()
        val failure = IllegalStateException("query failed")
        val expected = "Renderer" to "Vendor"
        var queryCount = 0

        try {
            cache.getOrQuery {
                queryCount++
                throw failure
            }
            fail("Query exception must reach the caller")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        }

        assertSame(expected, cache.getOrQuery {
            queryCount++
            expected
        })
        assertSame(expected, cache.getOrQuery { error("Success must be reused") })
        assertEquals(2, queryCount)
    }

    @Test
    fun independentCachesDoNotShareHardwareInfo() {
        val firstCache = GpuHardwareInfoCache()
        val secondCache = GpuHardwareInfoCache()
        val firstInfo = "First renderer" to "First vendor"
        val secondInfo = "Second renderer" to "Second vendor"

        assertSame(firstInfo, firstCache.getOrQuery { firstInfo })
        assertSame(secondInfo, secondCache.getOrQuery { secondInfo })
        assertSame(firstInfo, firstCache.getOrQuery { error("First success must be reused") })
        assertSame(secondInfo, secondCache.getOrQuery { error("Second success must be reused") })
    }
}
