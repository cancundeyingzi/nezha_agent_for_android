package com.nezhahq.agent.simulator

import com.nezhahq.agent.SilentLoggerRule
import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.SimulatedDeviceConfig
import com.nezhahq.agent.grpc.GrpcConnection
import com.nezhahq.agent.grpc.GrpcManagedChannelFactory
import com.nezhahq.agent.grpc.GrpcTransportMode
import com.nezhahq.agent.grpc.ManagedGrpcConnection
import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.ManagedChannel
import io.grpc.MethodDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import proto.NezhaServiceGrpcKt.NezhaServiceCoroutineStub
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SimulatorSessionStoreTest {
    @get:Rule val silentLogger = SilentLoggerRule()

    @Test fun ordinaryRpcFailureClosesAndRetriesTheSameDeviceUuid() = runBlocking {
        val sessions = SimulatorSessionStore()
        val session = requireNotNull(sessions.reserve())
        val uuids = mutableListOf<String>()
        val closed = AtomicInteger()
        var reports = 0
        var successes = 0
        var failures = 0
        val reporter = GrpcSimulatedDeviceReporter(session,
            factory = SimulatorConnectionFactory { _, device ->
                uuids += device.uuid
                FakeConnection { closed.incrementAndGet() }
            }, report = { _, _ -> if (++reports == 1) throw IOException("ordinary RPC failure") })
        SimulatedDeviceLoop(reporter, intervalMs = 0).run(config,
            shouldContinue = { reports < 2 }, onSuccess = { successes++ }, onFailure = { failures++ })
        assertEquals(2, uuids.size)
        assertEquals(uuids.first(), uuids.last())
        assertEquals(2, closed.get())
        assertEquals(1, successes)
        assertEquals(1, failures)
        assertTrue(session.accepting)
        assertTrue(sessions.stopAndAwaitTermination(session.id))
    }

    @Test fun successfulRpcWithFalseNativeTerminationIsTerminalAndRetainsTheConnection() = runBlocking {
        val sessions = SimulatorSessionStore()
        val session = requireNotNull(sessions.reserve())
        val channel = UnfinishedChannel()
        var factories = 0
        var successes = 0
        var ordinaryFailures = 0
        val reporter = GrpcSimulatedDeviceReporter(session,
            factory = SimulatorConnectionFactory { config, device ->
                factories++
                ManagedGrpcConnection(AgentConfig(config.server, config.port, config.secret, device.uuid, config.useTls, false),
                    channelFactory = GrpcManagedChannelFactory { snapshot, _ ->
                        assertFalse("simulator must not enable privilege discovery", snapshot.rootMode)
                        channel
                    })
            }, report = { _, _ -> })
        val failure = runCatching {
            SimulatedDeviceLoop(reporter, intervalMs = 0).run(config, { true }, { successes++ }, { ordinaryFailures++ })
        }.exceptionOrNull()
        assertTrue(failure is SimulatorCleanupException)
        assertEquals(0, successes)
        assertEquals(0, ordinaryFailures)
        assertFalse(session.accepting)
        assertNull(sessions.reserve())
        assertFalse(sessions.stopAndAwaitTermination(session.id))
        assertEquals(1, factories)
        assertTrue(channel.waits.get() >= 2)
        channel.terminated = true
        assertTrue(sessions.stopAndAwaitTermination(session.id))
        val next = requireNotNull(sessions.reserve())
        assertNotEquals(session.id, next.id)
        assertFalse(sessions.stopAndAwaitTermination(session.id))
        assertTrue(next.accepting)
        assertTrue(sessions.stopAndAwaitTermination(next.id))
    }

    @Test fun cancellationKeepsItsIdentityAndSuppressedCleanupFailureWhileOwnerRetainsChannel() = runBlocking {
        val sessions = SimulatorSessionStore()
        val session = requireNotNull(sessions.reserve())
        val entered = CompletableDeferred<Unit>()
        val rpcCancellation = CompletableDeferred<CancellationException>()
        val reporterCancellation = CompletableDeferred<CancellationException>()
        var canClose = false
        val cleanupFailure = IOException("not terminated")
        val reporter = GrpcSimulatedDeviceReporter(session,
            factory = SimulatorConnectionFactory { _, _ -> FakeConnection { if (!canClose) throw cleanupFailure } },
            report = { _, _ ->
                try { entered.complete(Unit); awaitCancellation() }
                catch (cancelled: CancellationException) { rpcCancellation.complete(cancelled); throw cancelled }
            })
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            try { reporter.reportOne(config, RandomDeviceFactory.create()) }
            catch (cancelled: CancellationException) { reporterCancellation.complete(cancelled); throw cancelled }
        }
        withTimeout(5_000) { entered.await() }
        val cancellation = CancellationException("stop request")
        operation.cancel(cancellation)
        withTimeout(5_000) { operation.join() }
        val rpcFailure = withTimeout(5_000) { rpcCancellation.await() }
        val reporterFailure = withTimeout(5_000) { reporterCancellation.await() }
        assertSame("reporter must preserve its RPC cancellation", rpcFailure, reporterFailure)
        assertTrue(reporterFailure.suppressed.contains(cleanupFailure))
        val observed = runCatching { operation.await() }.exceptionOrNull()
        assertTrue(observed is CancellationException)
        // Deferred await may recover/copy the exception to add the awaiting stack. Its cause graph
        // must still carry the primary cancellation and the retained connection's cleanup error.
        val causes = generateSequence(observed) { it.cause }.take(8).toList()
        assertTrue(causes.any { it === cancellation })
        assertTrue(causes.any { it.suppressed.contains(cleanupFailure) })
        assertFalse(session.accepting)
        assertFalse(sessions.stopAndAwaitTermination(session.id))
        canClose = true
        assertTrue(sessions.stopAndAwaitTermination(session.id))
    }

    @Test fun aWorkerCloseFailureSealsAdmissionCancelsPeersAndStillClosesEveryOtherConnection() = runBlocking {
        val sessions = SimulatorSessionStore()
        val session = requireNotNull(sessions.reserve())
        val bothEntered = CompletableDeferred<Unit>()
        val peerClosed = CompletableDeferred<Unit>()
        val creates = AtomicInteger()
        val successes = AtomicInteger()
        val firstMayClose = java.util.concurrent.atomic.AtomicBoolean(false)
        val first = FakeConnection { if (!firstMayClose.get()) throw IOException("first still alive") }
        val second = FakeConnection { peerClosed.complete(Unit) }
        assertTrue(sessions.start(session, 2) { owner ->
            val reporter = GrpcSimulatedDeviceReporter(owner,
                factory = SimulatorConnectionFactory { _, _ -> if (creates.incrementAndGet() == 1) first else second },
                report = { connection, _ ->
                    if (connection === first) bothEntered.await()
                    else { bothEntered.complete(Unit); awaitCancellation() }
                })
            SimulatedDeviceLoop(reporter, intervalMs = 0, delayNext = {}).run(config.copy(threadCount = 2),
                { owner.accepting }, { successes.incrementAndGet() }, { fail("terminal cleanup must not become an RPC retry") })
        })
        withTimeout(5_000) { peerClosed.await() }
        assertFalse(sessions.stopAndAwaitTermination(session.id))
        assertFalse(session.accepting)
        assertEquals(2, creates.get())
        assertEquals(0, successes.get())
        assertNull(sessions.reserve())
        firstMayClose.set(true)
        assertTrue(sessions.stopAndAwaitTermination(session.id))
    }

    @Test fun resetClearsPresentationWithoutBypassingNativeTerminationOrErasingASuccessor() = runBlocking {
        val sessions = SimulatorSessionStore()
        val session = requireNotNull(sessions.reserve())
        val channel = UnfinishedChannel()
        val connection = ManagedGrpcConnection(
            AgentConfig(config.server, config.port, config.secret, "reset-fixture", config.useTls, false),
            channelFactory = GrpcManagedChannelFactory { _, _ -> channel }
        )
        assertTrue(session.register(connection))
        connection.connect()
        assertTrue(sessions.start(session, 2) { awaitCancellation() })
        sessions.success(session.id)
        sessions.failure(session.id, IOException("ordinary report failure"))
        val reset = sessions.captureConfigurationReset()
        sessions.requestStop(reset.sessionId!!)
        assertFalse(withTimeout(5_000) { sessions.stopAndAwaitTermination(session.id) })

        assertTrue(sessions.configurationResetApplied(reset))
        val pending = sessions.state.value
        assertEquals(SimulatorPhase.STOPPING, pending.phase)
        assertEquals(session.id, pending.sessionId)
        assertEquals(0, pending.successCount)
        assertEquals(0, pending.failureCount)
        assertEquals(0, pending.activeThreadCount)
        assertNotEquals("未启动", pending.lastStatus)
        assertFalse(pending.canStart)
        assertFalse(session.accepting)
        assertNull("display reset must not release the native slot", sessions.reserve())

        channel.terminated = true
        assertTrue(withTimeout(5_000) { sessions.stopAndAwaitTermination(session.id) })
        assertEquals(SimulatorPhase.IDLE, sessions.state.value.phase)
        assertEquals("未启动", sessions.state.value.lastStatus)
        val successor = requireNotNull(sessions.reserve())
        assertTrue(sessions.start(successor, 3) { awaitCancellation() })
        sessions.success(successor.id)
        val successorState = sessions.state.value
        assertFalse(sessions.configurationResetApplied(reset))
        assertEquals("an old reset callback must not clear the successor", successorState, sessions.state.value)
        assertTrue(withTimeout(5_000) { sessions.stopAndAwaitTermination(successor.id) })
        assertEquals(1, sessions.state.value.successCount)
    }

    private class FakeConnection(private val closeAction: () -> Unit) : GrpcConnection {
        override val stub: NezhaServiceCoroutineStub? = null
        override val transportMode = GrpcTransportMode.TLS
        override fun connect() = Unit
        override fun disconnect(preserveConnectionState: Boolean) = Unit
        override fun close() = closeAction()
    }

    private class UnfinishedChannel : ManagedChannel() {
        @Volatile var terminated = false
        private var shutdown = false
        val waits = AtomicInteger()
        override fun shutdown(): ManagedChannel = shutdownNow()
        override fun shutdownNow(): ManagedChannel { shutdown = true; return this }
        override fun isShutdown() = shutdown
        override fun isTerminated() = shutdown && terminated
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean { waits.incrementAndGet(); return isTerminated }
        override fun authority() = "simulator-fixture"
        override fun <RequestT : Any?, ResponseT : Any?> newCall(methodDescriptor: MethodDescriptor<RequestT, ResponseT>, callOptions: CallOptions): ClientCall<RequestT, ResponseT> =
            throw UnsupportedOperationException("RPC is injected")
    }

    private companion object {
        val config = SimulatedDeviceConfig("fixture.invalid", 5555, "synthetic-secret", true, 1)
    }
}
