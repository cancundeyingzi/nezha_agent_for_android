package com.nezhahq.agent.service

import com.nezhahq.agent.SilentLoggerRule
import com.nezhahq.agent.util.DashboardSessionTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import proto.Nezha.GeoIP
import proto.Nezha.Host
import proto.Nezha.State

class MetricReporterTest {
    @get:Rule val logger = SilentLoggerRule()

    @Test fun oldNetworkCompletionCannotClearANewerProbeAndParentCancellationJoinsAllProbes() = runBlocking {
        val parent = Job(coroutineContext[Job])
        val entered = Channel<Int>(Channel.UNLIMITED)
        val ended = Channel<Int>(Channel.UNLIMITED)
        val oldCleanupEntered = CompletableDeferred<Unit>()
        val releaseOldCleanup = CompletableDeferred<Unit>()
        var probes = 0
        val reporter = MetricReporter(CoroutineScope(coroutineContext + parent),
            collectHost = { error("not a handshake") }, collectState = { error("not a state stream") },
            fetchGeoIp = {
                val probe = ++probes
                entered.send(probe)
                try { awaitCancellation() } finally {
                    if (probe == 1) withContext(NonCancellable) {
                        oldCleanupEntered.complete(Unit)
                        releaseOldCleanup.await()
                    }
                    ended.trySend(probe)
                }
            }, currentGeoIpReporter = { error("cancelled probes must not report") }, handshakeTimeoutMillis = 60_000)
        try {
            reporter.onNetworkAvailable()
            assertEquals(1, withTimeout(5_000) { entered.receive() })
            reporter.onNetworkAvailable()
            withTimeout(5_000) { oldCleanupEntered.await() }
            assertEquals(2, withTimeout(5_000) { entered.receive() })
            releaseOldCleanup.complete(Unit)
            assertEquals(1, withTimeout(5_000) { ended.receive() })
            // Let the old completion callback run before the next network event.
            yield()
            reporter.onNetworkAvailable()
            assertEquals(2, withTimeout(5_000) { ended.receive() })
            assertEquals(3, withTimeout(5_000) { entered.receive() })
            withTimeout(5_000) { parent.cancelAndJoin() }
            assertEquals(3, withTimeout(5_000) { ended.receive() })
            reporter.onNetworkAvailable()
            yield()
            assertEquals("a late callback cannot detach work from the stopped runtime", 3, probes)
            assertTrue(parent.children.none())
        } finally {
            releaseOldCleanup.complete(Unit)
            withTimeout(5_000) { parent.cancelAndJoin() }
        }
    }

    @Test fun networkReportResolvesTheCurrentChannelAfterItsFetchCompletes() = runBlocking {
        val fetchEntered = CompletableDeferred<Unit>()
        val releaseFetch = CompletableDeferred<Unit>()
        val reported = Channel<Pair<Int, GeoIP>>(Channel.UNLIMITED)
        var channelGeneration = 1
        val geoIp = GeoIP.newBuilder().setCountryCode("TEST").build()
        val parent = Job(coroutineContext[Job])
        val reporter = MetricReporter(CoroutineScope(coroutineContext + parent),
            collectHost = { error("not a handshake") }, collectState = { error("not a state stream") },
            fetchGeoIp = { fetchEntered.complete(Unit); releaseFetch.await(); geoIp },
            currentGeoIpReporter = {
                val generation = channelGeneration
                val report: suspend (GeoIP) -> Unit = { metadata -> reported.send(generation to metadata) }
                report
            })
        try {
            reporter.onNetworkAvailable()
            withTimeout(5_000) { fetchEntered.await() }
            channelGeneration = 2
            releaseFetch.complete(Unit)
            assertEquals(2 to geoIp, withTimeout(5_000) { reported.receive() })
        } finally {
            releaseFetch.complete(Unit)
            withTimeout(5_000) { parent.cancelAndJoin() }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun missingStateReceiptCancelsTheOutgoingCollectorAndPropagatesTheDeadline() = runBlocking {
        val outgoingStarted = CompletableDeferred<Unit>()
        val outgoingStopped = CompletableDeferred<Unit>()
        val reporter = MetricReporter(this, { Host.getDefaultInstance() }, { State.getDefaultInstance() },
            { null }, { null }, stateReportIntervalMillis = 10, stateReceiptTimeoutMillis = 200)
        val failure = runCatching {
            reporter.reportState({ states -> channelFlow {
                val outgoing = launch {
                    try { states.collect { outgoingStarted.complete(Unit) } }
                    finally { outgoingStopped.complete(Unit) }
                }
                try { awaitCancellation() } finally { outgoing.cancel() }
            } }, { error("No receipt was sent") })
        }.exceptionOrNull()
        assertTrue(failure is DashboardSessionTimeoutException)
        assertTrue(outgoingStarted.isCompleted)
        assertTrue(outgoingStopped.isCompleted)
    }

    @Test fun initialReportsKeepHostThenGeoIpOrderAndDoNotFetchDuringConstruction() = runBlocking {
        val order = mutableListOf<String>()
        val host = Host.newBuilder().setVersion("synthetic").build()
        val geoIp = GeoIP.newBuilder().setCountryCode("TEST").build()
        val reporter = MetricReporter(this,
            collectHost = { order += "collect-host"; host },
            collectState = { error("not a state stream") },
            fetchGeoIp = { order += "fetch-geoip"; geoIp }, currentGeoIpReporter = { null })
        assertTrue(order.isEmpty())
        reporter.reportInitial(
            reportHost = { assertEquals(host, it); order += "report-host" },
            reportGeoIp = { assertEquals(geoIp, it); order += "report-geoip" })
        assertEquals(listOf("collect-host", "report-host", "fetch-geoip", "report-geoip"), order)
    }

    @Test fun anUnresponsiveInitialGeoIpFetchIsBoundedAndItsCancellationFinishesBeforeFailureReturns() = runBlocking {
        val fetchStopped = CompletableDeferred<Unit>()
        var hosts = 0
        var geoIpReports = 0
        val reporter = MetricReporter(this, { Host.getDefaultInstance() }, { State.getDefaultInstance() },
            fetchGeoIp = { try { awaitCancellation() } finally { fetchStopped.complete(Unit) } },
            currentGeoIpReporter = { null }, handshakeTimeoutMillis = 100)
        val failure = runCatching { reporter.reportInitial({ hosts++ }, { geoIpReports++ }) }.exceptionOrNull()
        assertTrue(failure is DashboardSessionTimeoutException)
        assertEquals(1, hosts)
        assertEquals(0, geoIpReports)
        assertTrue(fetchStopped.isCompleted)
    }
}
