package com.nezhahq.agent.service

import com.nezhahq.agent.SilentLoggerRule
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.grpc.GrpcConnectionState
import com.nezhahq.agent.grpc.GrpcTransportMode
import io.grpc.Status
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import proto.Nezha.TaskResult
import proto.Nezha.GeoIP
import proto.Nezha.Host
import proto.Nezha.State
import proto.Nezha.Receipt

class ConnectionSupervisorTest {
    @get:Rule val logger = SilentLoggerRule()

    @Test fun geoIpTimeoutAndFailuresNeverPreventOnlineOrRestartTheHealthyStreams() = runBlocking {
        val parent = Job(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + parent)
        val connection = RuntimeTestConnection().apply { connect() }
        val online = CompletableDeferred<Unit>()
        val tasksStarted = CompletableDeferred<Unit>()
        val eventuallyReported = CompletableDeferred<Unit>()
        var attempts = 0
        var reports = 0
        var stateStreams = 0
        val reporter = MetricReporter(scope, { Host.getDefaultInstance() }, { State.getDefaultInstance() },
            fetchGeoIp = {
                when (++attempts) {
                    1 -> awaitCancellation()
                    2 -> throw IOException("synthetic GeoIP failure")
                    else -> GeoIP.getDefaultInstance()
                }
            }, currentGeoIpReporter = {
                {
                    if (++reports == 1) throw IOException("synthetic metadata RPC failure")
                    eventuallyReported.complete(Unit)
                    Unit
                }
            }, stateReportIntervalMillis = 10, geoIpFetchTimeoutMillis = 100, geoIpRetryDelayMillis = 10)
        val supervisor = ConnectionSupervisor(connection,
            { state, _ -> if (state == GrpcConnectionState.CONNECTED) online.complete(Unit) },
            reportInitial = { reporter.reportInitial {} },
            reportState = { _, onReceipt ->
                stateStreams++
                reporter.reportState({ states -> flow { states.collect { emit(Receipt.getDefaultInstance()) } } }, onReceipt)
            }, dispatchTasks = { tasksStarted.complete(Unit); awaitCancellation() })
        scope.launch { supervisor.run() }
        try {
            withTimeout(5_000) { online.await(); tasksStarted.await(); eventuallyReported.await() }
            assertEquals(4, attempts)
            assertEquals(2, reports)
            assertEquals(1, stateStreams)
            assertEquals(1, connection.connects)
            assertEquals(0, connection.disconnects)
        } finally { withTimeout(5_000) { parent.cancelAndJoin() } }
        assertTrue(parent.children.none())
    }

    @Test fun onlyTheFirstReceiptInEachAttemptResetsBackoff() = runBlocking {
        val ceilings = mutableListOf<Long>()
        val backoff = ReconnectBackoffPolicy(jitter = BackoffJitter { ceilings += it; 0 })
        backoff.nextDelayMillis()
        backoff.nextDelayMillis()
        val connection = RuntimeTestConnection().apply { connect() }
        val callbacks = Channel<() -> Unit>(Channel.UNLIMITED)
        val failTasks = Channel<Unit>(Channel.UNLIMITED)
        val states = mutableListOf<GrpcConnectionState>()
        val supervisor = ConnectionSupervisor(connection, { state, _ -> states += state }, {},
            reportState = { _, onReceipt -> callbacks.send(onReceipt); awaitCancellation() },
            dispatchTasks = { failTasks.receive(); throw IOException("synthetic stream failure") },
            reconnectBackoff = backoff)
        val running = launch { supervisor.run() }
        try {
            val first = withTimeout(5_000) { callbacks.receive() }
            first()
            backoff.nextDelayMillis()
            first()
            backoff.nextDelayMillis()
            failTasks.send(Unit)
            val second = withTimeout(5_000) { callbacks.receive() }
            second()
            backoff.nextDelayMillis()
            assertEquals(listOf(5_000L, 10_000L, 5_000L, 10_000L, 20_000L, 5_000L), ceilings)
            assertEquals(2, states.count { it == GrpcConnectionState.CONNECTED })
            assertEquals(2, connection.connects)
        } finally { withTimeout(5_000) { running.cancelAndJoin() } }
    }

    @Test fun consecutiveHandshakeFailuresKeepBackingOffAndNeverStartEitherContinuousStream() = runBlocking {
        val ceilings = mutableListOf<Long>()
        val reachedFourthAttempt = CompletableDeferred<Unit>()
        var attempts = 0
        var continuousStarts = 0
        val connection = RuntimeTestConnection().apply { connect() }
        val supervisor = ConnectionSupervisor(connection, { _, _ -> },
            reportInitial = {
                if (++attempts < 4) throw AssertionError("synthetic handshake failure")
                reachedFourthAttempt.complete(Unit)
                awaitCancellation()
            },
            reportState = { _, _ -> continuousStarts++; awaitCancellation() },
            dispatchTasks = { continuousStarts++; awaitCancellation() },
            reconnectBackoff = ReconnectBackoffPolicy(jitter = BackoffJitter { ceilings += it; 0 }))
        val running = launch { supervisor.run() }
        try {
            withTimeout(5_000) { reachedFourthAttempt.await() }
            assertEquals(listOf(5_000L, 10_000L, 20_000L), ceilings)
            assertEquals(0, continuousStarts)
            assertEquals(4, connection.connects)
            assertEquals(3, connection.disconnects)
        } finally { withTimeout(5_000) { running.cancelAndJoin() } }
    }

    @Test fun authenticationAndTlsAndExplicitPlaintextFailuresPreserveTheirStatusAndTransport() = runBlocking {
        val cases = listOf(
            Triple(GrpcTransportMode.TLS, Status.UNAUTHENTICATED.asRuntimeException(), GrpcConnectionState.AUTH_FAILED),
            Triple(GrpcTransportMode.TLS, SSLHandshakeException("synthetic certificate failure"), GrpcConnectionState.RECONNECTING),
            Triple(GrpcTransportMode.PLAINTEXT, IOException("synthetic transport failure"), GrpcConnectionState.PLAINTEXT_RECONNECTING)
        )
        for ((mode, failure, failedState) in cases) {
            val connection = RuntimeTestConnection(mode).apply { connect() }
            val states = mutableListOf<GrpcConnectionState>()
            val retried = CompletableDeferred<Unit>()
            var attempts = 0
            val supervisor = ConnectionSupervisor(connection, { state, _ -> states += state },
                reportInitial = {
                    if (++attempts == 1) throw failure
                    retried.complete(Unit)
                    awaitCancellation()
                }, reportState = { _, _ -> error("handshake has not completed") },
                dispatchTasks = { error("handshake has not completed") },
                reconnectBackoff = ReconnectBackoffPolicy(jitter = BackoffJitter { 0 }))
            val running = launch { supervisor.run() }
            try {
                withTimeout(5_000) { retried.await() }
                val connecting = if (mode == GrpcTransportMode.TLS) GrpcConnectionState.CONNECTING
                    else GrpcConnectionState.PLAINTEXT_CONNECTING
                assertEquals(listOf(connecting, failedState, connecting), states)
                assertEquals(mode, connection.transportMode)
                assertEquals(2, connection.connects)
                assertEquals(listOf(true), connection.preservedStates)
            } finally { withTimeout(5_000) { running.cancelAndJoin() } }
        }
        assertTrue(logger.messages.any { it.contains("不自动切换明文") })
    }

    @Test fun componentConstructionDoesNotCollectDiscoverBackendsOrCreateChildWork() = runBlocking {
        val parent = Job(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + parent)
        val connection = RuntimeTestConnection()
        try {
            MetricReporter(scope,
                collectHost = { error("eager host/root discovery") },
                collectState = { error("eager metric collection") },
                fetchGeoIp = { error("eager network fetch") },
                currentGeoIpReporter = { error("eager connection lookup") })
            TaskDispatcher(RemoteCapabilities(), createMcpSession = { _, _ -> error("eager backend creation") })
            StreamSessionManager(scope, Channel<TaskResult>(),
                runSession = { error("eager legacy session") }, parseRequest = { error("eager parsing") })
            ConnectionSupervisor(connection, { _, _ -> error("eager status publication") },
                { error("eager handshake") }, { _, _ -> error("eager state stream") }, { error("eager tasks") })
            assertEquals(0, connection.connects)
            assertTrue(parent.children.none())
        } finally { parent.cancelAndJoin() }
    }
}
