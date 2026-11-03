package com.nezhahq.agent.service

import com.nezhahq.agent.StubConfigRepository
import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.RemoteCapabilities
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConfigRetryOwnershipTest {
    @Test
    fun explicitReloadDuringAnInFlightFailureOwnsRecoveryWithoutALaterDuplicateRetry() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFailure = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var creates = 0
        var timers = 0
        val fixture = Fixture(
            coroutineContext,
            factory = AgentRuntimeFactory {
                if (++creates == 1) Runtime {
                    firstEntered.complete(Unit)
                    releaseFailure.await()
                    error("old start failed")
                } else Runtime { started.complete(Unit) }
            },
            retryDelay = { timers++ }
        )
        try {
            fixture.processor.requestReload()
            withTimeout(2_000) { firstEntered.await() }
            fixture.processor.requestReload()
            releaseFailure.complete(Unit)
            withTimeout(2_000) { started.await() }
            fixture.settle()

            assertEquals(2, creates)
            assertEquals(0, timers)
            assertTrue(fixture.reports.tryReceive().isFailure)
            assertTrue(fixture.controller.isRunning)
        } finally {
            releaseFailure.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun cancelledTimerThatReturnsLateCannotEnqueueAfterTheExplicitReloadSucceeds() = runBlocking {
        val releaseTimer = CompletableDeferred<Unit>()
        val timer = CompletableDeferred<Job>()
        val started = CompletableDeferred<Unit>()
        var creates = 0
        val fixture = Fixture(
            coroutineContext,
            factory = AgentRuntimeFactory {
                if (++creates == 1) Runtime { error("start failed") } else Runtime { started.complete(Unit) }
            },
            retryDelay = {
                timer.complete(currentCoroutineContext()[Job]!!)
                withContext(NonCancellable) { releaseTimer.await() }
            }
        )
        try {
            fixture.processor.requestReload()
            assertEquals(5_000L, withTimeout(2_000) { fixture.reports.receive() })
            val oldTimer = withTimeout(2_000) { timer.await() }
            fixture.processor.requestReload()
            withTimeout(2_000) { started.await() }
            fixture.settle()
            assertTrue(oldTimer.isCancelled)

            releaseTimer.complete(Unit)
            withTimeout(2_000) { oldTimer.join() }
            fixture.settle()
            assertEquals(2, creates)
        } finally {
            releaseTimer.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun alreadyQueuedRetryIsDiscardedWhenAnExplicitReloadArrives() = runBlocking {
        val releaseTimer = CompletableDeferred<Unit>()
        val timer = CompletableDeferred<Job>()
        val capabilityWaiting = CompletableDeferred<Unit>()
        val releaseCapability = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var gateCalls = 0
        var creates = 0
        val fixture = Fixture(
            coroutineContext,
            factory = AgentRuntimeFactory { creates++; Runtime { started.complete(Unit) } },
            retryDelay = { timer.complete(currentCoroutineContext()[Job]!!); releaseTimer.await() },
            awaitShutdown = {
                when (++gateCalls) {
                    1 -> error("previous shutdown pending")
                    2 -> { capabilityWaiting.complete(Unit); releaseCapability.await() }
                }
            }
        )
        try {
            fixture.processor.requestReload()
            withTimeout(2_000) { fixture.reports.receive(); timer.await() }
            fixture.processor.requestCapabilityRefresh()
            withTimeout(2_000) { capabilityWaiting.await() }
            releaseTimer.complete(Unit)
            withTimeout(2_000) { timer.await().join() }

            fixture.processor.requestReload()
            releaseCapability.complete(Unit)
            withTimeout(2_000) { started.await() }
            fixture.settle()
            assertEquals(1, creates)
            assertEquals(3, fixture.repository.loads)
        } finally {
            releaseTimer.complete(Unit)
            releaseCapability.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun capabilityOnlyNoOpWhileOfflinePreservesTheNecessaryStartRetry() = runBlocking {
        val releaseTimer = CompletableDeferred<Unit>()
        val timer = CompletableDeferred<Job>()
        val started = CompletableDeferred<Unit>()
        var creates = 0
        val fixture = Fixture(
            coroutineContext,
            factory = AgentRuntimeFactory {
                if (++creates == 1) Runtime { error("start failed") } else Runtime { started.complete(Unit) }
            },
            retryDelay = { timer.complete(currentCoroutineContext()[Job]!!); releaseTimer.await() }
        )
        try {
            fixture.processor.requestReload()
            withTimeout(2_000) { fixture.reports.receive() }
            val pending = withTimeout(2_000) { timer.await() }
            fixture.processor.requestCapabilityRefresh()
            fixture.settle()
            assertEquals(2, fixture.repository.loads)
            assertFalse(fixture.controller.isRunning)
            assertFalse(pending.isCancelled)

            releaseTimer.complete(Unit)
            withTimeout(2_000) { started.await() }
            fixture.settle()
            assertEquals(2, creates)
            assertTrue(fixture.controller.isRunning)
        } finally {
            releaseTimer.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun schedulingAnotherFailureInvalidatesThePreviousTimerInsteadOfLosingItsReference() = runBlocking {
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val timers = Channel<Job>(Channel.UNLIMITED)
        val started = CompletableDeferred<Unit>()
        var delayed = 0
        var gates = 0
        var creates = 0
        val fixture = Fixture(
            coroutineContext,
            factory = AgentRuntimeFactory { creates++; Runtime { started.complete(Unit) } },
            retryDelay = {
                timers.send(currentCoroutineContext()[Job]!!)
                if (++delayed == 1) withContext(NonCancellable) { releaseFirst.await() }
                else releaseSecond.await()
            },
            awaitShutdown = { if (++gates <= 2) error("shutdown pending") }
        )
        try {
            fixture.processor.requestReload()
            assertEquals(5_000L, withTimeout(2_000) { fixture.reports.receive() })
            val first = withTimeout(2_000) { timers.receive() }
            fixture.processor.requestCapabilityRefresh()
            assertEquals(10_000L, withTimeout(2_000) { fixture.reports.receive() })
            withTimeout(2_000) { timers.receive() }
            assertTrue(first.isCancelled)
            releaseFirst.complete(Unit)
            withTimeout(2_000) { first.join() }
            fixture.settle()
            assertEquals(0, creates)

            releaseSecond.complete(Unit)
            withTimeout(2_000) { started.await() }
            fixture.settle()
            assertEquals(1, creates)
        } finally {
            releaseFirst.complete(Unit)
            releaseSecond.complete(Unit)
            fixture.close()
        }
    }

    private class Fixture(
        context: CoroutineContext,
        factory: AgentRuntimeFactory,
        retryDelay: suspend (Long) -> Unit,
        awaitShutdown: suspend () -> Unit = {}
    ) {
        private val lifetime = SupervisorJob()
        private val scope = CoroutineScope(context + lifetime)
        val reports = Channel<Long>(Channel.UNLIMITED)
        val repository = Repository()
        val controller = AgentRuntimeController(factory)
        val processor = AgentConfigCommandProcessor(
            scope, repository, controller, Dispatchers.Unconfined,
            onConfigurationUnusable = {},
            onRuntimeUnavailable = { _, delayMillis -> reports.trySend(delayMillis) },
            onReloadRejected = {},
            awaitPreviousShutdown = awaitShutdown,
            delayBeforeRetry = retryDelay
        )

        suspend fun settle() {
            scope.launch {}.join()
            controller.updateCapabilities(RemoteCapabilities())
        }

        suspend fun close() {
            processor.close()
            controller.stop()
            lifetime.cancelAndJoin()
        }
    }

    private class Repository : StubConfigRepository() {
        var loads = 0
        override fun loadAgentConfig(): Result<AgentConfig> { loads++; return Result.success(config("panel")) }
    }

    private class Runtime(private val onStart: suspend () -> Unit) : AgentRuntimeHandle {
        override suspend fun start() = onStart()
        override suspend fun stop() = Unit
        override suspend fun updateCapabilities(capabilities: RemoteCapabilities) = Unit
    }
}
