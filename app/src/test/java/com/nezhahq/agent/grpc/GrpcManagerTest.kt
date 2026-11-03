package com.nezhahq.agent.grpc

import com.nezhahq.agent.SilentLoggerRule
import com.nezhahq.agent.core.model.AgentConfig
import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GrpcManagerTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    @Test
    fun resolveTransportModeUsesExplicitTlsConfig() {
        assertEquals(GrpcTransportMode.TLS, GrpcManager.resolveTransportMode(useTls = true))
        assertEquals(GrpcTransportMode.PLAINTEXT, GrpcManager.resolveTransportMode(useTls = false))
    }

    @Test
    fun plaintextStatesAreNamedExplicitly() {
        assertTrue(GrpcConnectionState.PLAINTEXT_CONNECTED.isPlaintext)
        assertFalse(GrpcConnectionState.CONNECTED.isPlaintext)
    }

    @Test
    fun reconnectClosesOldChannelBeforePublishingNewStub() {
        val events = mutableListOf<String>()
        var channelId = 0
        val connection = ManagedGrpcConnection(
            config = config(useTls = true),
            channelFactory = GrpcManagedChannelFactory { _, _ ->
                val id = ++channelId
                events += "create-$id"
                FakeManagedChannel { events += "close-$id" }
            }
        )

        connection.connect()
        connection.connect()

        assertEquals(listOf("create-1", "close-1", "create-2"), events)
        assertTrue(connection.stub != null)
    }

    @Test
    fun closeIsIdempotentAndClearsStubAndUiState() {
        var channelCloses = 0
        // Its own holder rather than a global, so this test cannot be perturbed by another one.
        val connectionState = ConnectionStateHolder()
        val connection = ManagedGrpcConnection(
            config = config(useTls = false),
            stateSink = connectionState::updateState,
            channelFactory = GrpcManagedChannelFactory { _, _ ->
                FakeManagedChannel { channelCloses++ }
            }
        )
        connection.connect()
        connectionState.updateState(GrpcConnectionState.PLAINTEXT_CONNECTED)

        connection.close()
        connection.close()

        assertEquals(1, channelCloses)
        assertNull(connection.stub)
        assertEquals(GrpcConnectionState.IDLE, connectionState.connectionState.value)
        assertEquals(GrpcTransportMode.PLAINTEXT, connection.transportMode)
    }

    @Test
    fun tlsFactoryFailureNeverRetriesAsPlaintext() {
        val attemptedModes = mutableListOf<GrpcTransportMode>()
        val connection = ManagedGrpcConnection(
            config = config(useTls = true),
            channelFactory = GrpcManagedChannelFactory { _, mode ->
                attemptedModes += mode
                error("TLS setup failed")
            }
        )

        assertTrue(runCatching { connection.connect() }.isFailure)
        assertEquals(listOf(GrpcTransportMode.TLS), attemptedModes)
        assertNull(connection.stub)
    }

    @Test
    fun failedChannelShutdownRemainsOwnedAndCanBeRetried() {
        var shutdownAttempts = 0
        val connection = ManagedGrpcConnection(
            config = config(useTls = true),
            channelFactory = GrpcManagedChannelFactory { _, _ ->
                FakeManagedChannel { if (++shutdownAttempts == 1) error("shutdown failed") }
            }
        )
        connection.connect()

        assertTrue(runCatching { connection.close() }.isFailure)
        assertNull(connection.stub)
        connection.close()
        connection.close()

        assertEquals(2, shutdownAttempts)
        assertTrue(runCatching { connection.connect() }.isFailure)
    }

    @Test
    fun terminalCloseWaitsWithinABoundAndRetainsAnUnterminatedChannelForRetry() {
        val channel = FakeManagedChannel {}.apply { terminationReady = false }
        val connection = ManagedGrpcConnection(
            config = config(useTls = true),
            channelFactory = GrpcManagedChannelFactory { _, _ -> channel }
        )
        connection.connect()

        assertTrue(runCatching { connection.close() }.isFailure)
        assertEquals(listOf(1_000L), channel.waitedMillis)
        assertTrue(runCatching { connection.close() }.isFailure)
        assertEquals(listOf(1_000L, 1_000L), channel.waitedMillis)
        assertTrue(runCatching { connection.connect() }.isFailure)

        channel.terminationReady = true
        connection.close()
        connection.close()
        assertNull(connection.stub)
    }

    @Test
    fun reconnectRetainsTheClosingChannelAndNeverBlocksWaitingForTermination() {
        val first = FakeManagedChannel {}.apply { terminationReady = false }
        var creates = 0
        val connection = ManagedGrpcConnection(
            config = config(useTls = true),
            channelFactory = GrpcManagedChannelFactory { _, _ ->
                if (++creates == 1) first else FakeManagedChannel {}
            }
        )
        connection.connect()
        connection.disconnect()

        assertTrue(runCatching { connection.connect() }.isFailure)
        assertEquals(1, creates)
        assertNull(connection.stub)
        assertTrue(first.waitedMillis.isEmpty())

        first.terminationReady = true
        connection.connect()
        assertEquals(2, creates)
        assertTrue(connection.stub != null)
        assertTrue(first.waitedMillis.isEmpty())
        connection.close()
    }

    @Test
    fun failingStateSinkDoesNotPreventChannelTerminationAndCleanupRetry() {
        val channel = FakeManagedChannel {}.apply { terminationReady = false }
        val connection = ManagedGrpcConnection(
            config = config(useTls = true),
            stateSink = { error("UI state sink failed") },
            channelFactory = GrpcManagedChannelFactory { _, _ -> channel }
        )
        connection.connect()

        assertTrue(runCatching { connection.close() }.isFailure)
        assertEquals(listOf(1_000L), channel.waitedMillis)
        channel.terminationReady = true
        connection.close()
        connection.close()
        assertNull(connection.stub)
    }

    private fun config(useTls: Boolean) = AgentConfig(
        server = "example.com",
        port = 443,
        secret = "secret",
        uuid = "uuid",
        useTls = useTls,
        rootMode = false
    )

    private class FakeManagedChannel(
        private val onClose: () -> Unit
    ) : ManagedChannel() {
        private var shutdown = false
        var terminationReady = true
        val waitedMillis = mutableListOf<Long>()

        override fun shutdown(): ManagedChannel = shutdownNow()

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown && terminationReady

        override fun shutdownNow(): ManagedChannel {
            if (!shutdown) {
                onClose()
                shutdown = true
            }
            return this
        }

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
            waitedMillis += unit.toMillis(timeout)
            return isTerminated
        }

        override fun <RequestT : Any?, ResponseT : Any?> newCall(
            methodDescriptor: MethodDescriptor<RequestT, ResponseT>,
            callOptions: CallOptions
        ): ClientCall<RequestT, ResponseT> {
            throw UnsupportedOperationException()
        }

        override fun authority(): String = "fake"
    }
}
