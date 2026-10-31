package com.nezhahq.agent.service

import com.nezhahq.agent.StubConfigRepository
import com.nezhahq.agent.core.config.ConfigRepository
import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.RemoteCapabilities
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConfigCommandProcessorTest {
    @Test
    fun burstBeforeProcessingLoadsAndAppliesOnlyLatestSnapshot() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        scope.launch { gate.await() }
        val repository = FakeConfigRepository(config("old"))
        val applied = mutableListOf<String>()
        val controller = AgentRuntimeController(
            AgentRuntimeFactory { snapshot ->
                RecordingRuntime(onStart = {
                    applied += snapshot.server
                    started.countDown()
                })
            }
        )
        val processor = processor(scope, dispatcher, repository, controller)

        processor.requestReload()
        repository.result = Result.success(config("latest"))
        processor.requestReload()
        processor.requestReload()
        gate.countDown()

        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("latest"), applied)
        assertEquals(1, repository.loads.get())
        runBlocking { processor.close(); controller.stop() }
        dispatcher.close()
        executor.shutdownNow()
    }

    @Test
    fun requestDuringReloadRunsAfterwardWithLatestStorageAndNeverOverlaps() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val applied = mutableListOf<String>()
        val repository = FakeConfigRepository(config("one"))
        val controller = AgentRuntimeController(
            AgentRuntimeFactory { snapshot ->
                RecordingRuntime(
                    onStart = {
                        val count = active.incrementAndGet()
                        maxActive.updateAndGet { previous -> maxOf(previous, count) }
                        applied += snapshot.server
                        if (snapshot.server == "one") {
                            firstStarted.countDown()
                            releaseFirst.await()
                        } else {
                            secondStarted.countDown()
                        }
                    },
                    onStop = { active.decrementAndGet() }
                )
            }
        )
        val processor = processor(scope, dispatcher, repository, controller)

        processor.requestReload()
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        repository.result = Result.success(config("two"))
        processor.requestReload()
        releaseFirst.countDown()

        assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("one", "two"), applied)
        assertEquals(1, maxActive.get())
        runBlocking { processor.close(); controller.stop() }
        dispatcher.close()
        executor.shutdownNow()
    }

    @Test
    fun storageFailureLeavesExistingRuntimeUntouched() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val started = CountDownLatch(1)
        val failed = CountDownLatch(1)
        var stops = 0
        val repository = FakeConfigRepository(config("valid"))
        val controller = AgentRuntimeController(
            AgentRuntimeFactory {
                RecordingRuntime(
                    onStart = { started.countDown() },
                    onStop = { stops++ }
                )
            }
        )
        val retries = AtomicInteger()
        val processor = processor(
            scope,
            dispatcher,
            repository,
            controller,
            onRuntimeUnavailable = { _, _ -> retries.incrementAndGet() },
            onReloadRejected = { failed.countDown() }
        )
        processor.requestReload()
        assertTrue(started.await(2, TimeUnit.SECONDS))

        repository.result = Result.failure(IllegalStateException("storage unavailable"))
        processor.requestReload()

        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertTrue(controller.isRunning)
        assertEquals(0, stops)
        // A live runtime keeps serving, so there is nothing to retry.
        assertEquals(0, retries.get())
        runBlocking { processor.close(); controller.stop() }
        dispatcher.close()
        executor.shutdownNow()
    }

    @Test
    fun firstStorageFailureSignalsStopPath() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val failed = CountDownLatch(1)
        val repository = FakeConfigRepository(config("unused")).apply {
            result = Result.failure(IllegalArgumentException("invalid"))
        }
        val controller = AgentRuntimeController(
            AgentRuntimeFactory { RecordingRuntime() }
        )
        val retries = AtomicInteger()
        val processor = processor(
            scope,
            dispatcher,
            repository,
            controller,
            onConfigurationUnusable = { failed.countDown() },
            onRuntimeUnavailable = { _, _ -> retries.incrementAndGet() }
        )

        processor.requestReload()

        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertFalse(controller.isRunning)
        // An unusable configuration needs the user, so retrying it forever would be pointless.
        assertEquals(0, retries.get())
        runBlocking { processor.close(); controller.stop() }
        dispatcher.close()
        executor.shutdownNow()
    }

    @Test
    fun capabilityRefreshUpdatesLiveRuntimeWithoutRestartingIt() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val started = CountDownLatch(1)
        val refreshed = CountDownLatch(1)
        val starts = AtomicInteger()
        val runtime = RecordingRuntime(
            onStart = {
                starts.incrementAndGet()
                started.countDown()
            },
            onCapabilities = { refreshed.countDown() }
        )
        val repository = FakeConfigRepository(config("panel"))
        val controller = AgentRuntimeController(AgentRuntimeFactory { runtime })
        val processor = processor(scope, dispatcher, repository, controller)
        processor.requestReload()
        assertTrue(started.await(2, TimeUnit.SECONDS))

        repository.result = Result.success(configWithShell("panel", shellEnabled = true))
        processor.requestCapabilityRefresh()

        assertTrue(refreshed.await(2, TimeUnit.SECONDS))
        assertEquals(true, runtime.lastCapabilities?.shellEnabled)
        assertEquals(1, starts.get())
        runBlocking { processor.close(); controller.stop() }
        dispatcher.close()
        executor.shutdownNow()
    }

    @Test
    fun capabilityRefreshWithoutRuntimeIsIgnoredInsteadOfFailing() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val loaded = CountDownLatch(1)
        val repository = FakeConfigRepository(config("panel")).apply {
            onLoad = { loaded.countDown() }
        }
        val controller = AgentRuntimeController(AgentRuntimeFactory { RecordingRuntime() })
        val failures = mutableListOf<Throwable>()
        val processor = processor(
            scope,
            dispatcher,
            repository,
            controller,
            onConfigurationUnusable = { failures += it },
            onRuntimeUnavailable = { failure, _ -> failures += failure },
            onReloadRejected = { failures += it }
        )

        processor.requestCapabilityRefresh()

        assertTrue(loaded.await(2, TimeUnit.SECONDS))
        runBlocking { processor.close(); controller.stop() }
        assertEquals(emptyList<Throwable>(), failures)
        assertFalse(controller.isRunning)
        dispatcher.close()
        executor.shutdownNow()
    }

    @Test
    fun applyWaitsForThePreviousServiceShutdown() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val shutdownReleased = CountDownLatch(1)
        val started = CountDownLatch(1)
        val startedBeforeShutdownFinished = AtomicInteger()
        val controller = AgentRuntimeController(
            AgentRuntimeFactory {
                RecordingRuntime(onStart = {
                    if (shutdownReleased.count > 0) startedBeforeShutdownFinished.incrementAndGet()
                    started.countDown()
                })
            }
        )
        val processor = processor(
            scope,
            dispatcher,
            FakeConfigRepository(config("panel")),
            controller,
            awaitPreviousShutdown = { shutdownReleased.await() }
        )

        processor.requestReload()
        assertFalse(started.await(200, TimeUnit.MILLISECONDS))
        shutdownReleased.countDown()

        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertEquals(0, startedBeforeShutdownFinished.get())
        runBlocking { processor.close(); controller.stop() }
        dispatcher.close()
        executor.shutdownNow()
    }

    /**
     * Service teardown closes this processor while its worker may be waiting on that very
     * shutdown, so close breaks the cycle by cancelling the worker before joining it.
     *
     * That only works while [AgentConfigCommandProcessor.awaitPreviousShutdown] suspends instead of
     * blocking — hence the single thread here and the cancellable wait, matching the `Job.join` in
     * [RuntimeShutdownGate.awaitIdle]. A blocking wait would pin the thread and hang teardown.
     */
    @Test
    fun closeUnblocksAWorkerWaitingOnItsOwnShutdown() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val waiting = CountDownLatch(1)
        val neverFinishes = CompletableDeferred<Unit>()
        val processor = processor(
            scope,
            dispatcher,
            FakeConfigRepository(config("panel")),
            AgentRuntimeController(AgentRuntimeFactory { RecordingRuntime() }),
            awaitPreviousShutdown = {
                waiting.countDown()
                neverFinishes.await()
            }
        )

        processor.requestReload()
        assertTrue(waiting.await(2, TimeUnit.SECONDS))

        runBlocking { withTimeout(2_000) { processor.close() } }

        assertTrue(neverFinishes.isActive)
        dispatcher.close()
        executor.shutdownNow()
    }

    /**
     * A runtime that failed to start leaves the agent offline with nothing else to recover it, so
     * the processor keeps retrying on its own instead of stopping the service.
     */
    @Test
    fun aFailedRuntimeStartIsRetriedWithBackoffUntilItSucceeds() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val started = CountDownLatch(1)
        val attempts = AtomicInteger()
        val reportedDelays = mutableListOf<Long>()
        val waitedDelays = mutableListOf<Long>()
        val controller = AgentRuntimeController(
            AgentRuntimeFactory {
                RecordingRuntime(onStart = {
                    if (attempts.incrementAndGet() <= 2) error("start failed")
                    started.countDown()
                })
            }
        )
        val processor = processor(
            scope,
            dispatcher,
            FakeConfigRepository(config("panel")),
            controller,
            onRuntimeUnavailable = { _, delayMillis ->
                synchronized(reportedDelays) { reportedDelays += delayMillis }
            },
            delayBeforeRetry = { synchronized(waitedDelays) { waitedDelays += it } }
        )

        try {
            processor.requestReload()

            assertTrue(started.await(2, TimeUnit.SECONDS))
            // onStart 回调尚未发布 current；等待同一控制器的串行操作，确认 reload 已完成。
            runBlocking {
                withTimeout(2_000) { controller.updateCapabilities(RemoteCapabilities()) }
            }
            assertEquals(3, attempts.get())
            assertTrue(controller.isRunning)
            assertEquals(listOf(5_000L, 10_000L), synchronized(reportedDelays) { reportedDelays.toList() })
            assertEquals(listOf(5_000L, 10_000L), synchronized(waitedDelays) { waitedDelays.toList() })
        } finally {
            try {
                runBlocking {
                    try {
                        processor.close()
                    } finally {
                        controller.stop()
                    }
                }
            } finally {
                dispatcher.close()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun anExplicitReloadPreemptsAPendingRetryAndRestartsTheBackoff() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val firstReported = CountDownLatch(1)
        val secondReported = CountDownLatch(2)
        val reportedDelays = mutableListOf<Long>()
        val neverFires = CompletableDeferred<Unit>()
        val controller = AgentRuntimeController(
            AgentRuntimeFactory { RecordingRuntime(onStart = { error("start failed") }) }
        )
        val processor = processor(
            scope,
            dispatcher,
            FakeConfigRepository(config("panel")),
            controller,
            onRuntimeUnavailable = { _, delayMillis ->
                synchronized(reportedDelays) { reportedDelays += delayMillis }
                firstReported.countDown()
                secondReported.countDown()
            },
            delayBeforeRetry = { neverFires.await() }
        )

        processor.requestReload()
        assertTrue(firstReported.await(2, TimeUnit.SECONDS))
        processor.requestReload()

        assertTrue(secondReported.await(2, TimeUnit.SECONDS))
        // The second 5s proves the scheduled retry was dropped and the backoff restarted.
        assertEquals(listOf(5_000L, 5_000L), synchronized(reportedDelays) { reportedDelays.toList() })
        runBlocking { processor.close(); controller.stop() }
        dispatcher.close()
        executor.shutdownNow()
    }

    @Test
    fun closeCancelsAPendingRetry() {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val reported = CountDownLatch(1)
        val attempts = AtomicInteger()
        val releaseRetry = CompletableDeferred<Unit>()
        val controller = AgentRuntimeController(
            AgentRuntimeFactory {
                RecordingRuntime(onStart = {
                    attempts.incrementAndGet()
                    error("start failed")
                })
            }
        )
        val processor = processor(
            scope,
            dispatcher,
            FakeConfigRepository(config("panel")),
            controller,
            onRuntimeUnavailable = { _, _ -> reported.countDown() },
            delayBeforeRetry = { releaseRetry.await() }
        )

        processor.requestReload()
        assertTrue(reported.await(2, TimeUnit.SECONDS))
        runBlocking { processor.close() }
        releaseRetry.complete(Unit)
        executor.submit { }.get(2, TimeUnit.SECONDS)

        assertEquals(1, attempts.get())
        runBlocking { controller.stop() }
        dispatcher.close()
        executor.shutdownNow()
    }

    @Test
    fun pendingReloadAbsorbsCapabilityRefreshRegardlessOfOrder() {
        val reloadThenRefresh = PendingApplyMode().apply {
            record(ConfigApplyMode.RELOAD)
            record(ConfigApplyMode.CAPABILITIES_ONLY)
        }
        val refreshThenReload = PendingApplyMode().apply {
            record(ConfigApplyMode.CAPABILITIES_ONLY)
            record(ConfigApplyMode.RELOAD)
        }

        assertEquals(ConfigApplyMode.RELOAD, reloadThenRefresh.take())
        assertEquals(ConfigApplyMode.RELOAD, refreshThenReload.take())
    }

    @Test
    fun pendingModeIsConsumedExactlyOnce() {
        val pending = PendingApplyMode()
        pending.record(ConfigApplyMode.CAPABILITIES_ONLY)

        assertEquals(ConfigApplyMode.CAPABILITIES_ONLY, pending.take())
        assertNull(pending.take())
    }

    private fun processor(
        scope: CoroutineScope,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        repository: ConfigRepository,
        controller: AgentRuntimeController,
        onConfigurationUnusable: (Throwable) -> Unit = {},
        onRuntimeUnavailable: (Throwable, Long) -> Unit = { _, _ -> },
        onReloadRejected: (Throwable) -> Unit = {},
        awaitPreviousShutdown: suspend () -> Unit = {},
        // Real backoff by default, so a test that does not opt in never observes a retry.
        delayBeforeRetry: suspend (Long) -> Unit = { delay(it) }
    ) = AgentConfigCommandProcessor(
        scope = scope,
        repository = repository,
        controller = controller,
        ioDispatcher = dispatcher,
        onConfigurationUnusable = onConfigurationUnusable,
        onRuntimeUnavailable = onRuntimeUnavailable,
        onReloadRejected = onReloadRejected,
        awaitPreviousShutdown = awaitPreviousShutdown,
        delayBeforeRetry = delayBeforeRetry
    )

    private fun configWithShell(server: String, shellEnabled: Boolean) = AgentConfig(
        server = server,
        port = 5555,
        secret = "secret",
        uuid = "uuid",
        useTls = true,
        rootMode = false,
        remoteCapabilities = RemoteCapabilities(shellEnabled = shellEnabled)
    )

    private class RecordingRuntime(
        private val onStart: suspend () -> Unit = {},
        private val onStop: suspend () -> Unit = {},
        private val onCapabilities: suspend () -> Unit = {}
    ) : AgentRuntimeHandle {
        @Volatile
        var lastCapabilities: RemoteCapabilities? = null
            private set

        override suspend fun start() = onStart()

        override suspend fun stop() = onStop()

        override suspend fun updateCapabilities(capabilities: RemoteCapabilities) {
            lastCapabilities = capabilities
            onCapabilities()
        }
    }

    private class FakeConfigRepository(initial: AgentConfig) : StubConfigRepository() {
        @Volatile
        var result: Result<AgentConfig> = Result.success(initial)

        @Volatile
        var onLoad: () -> Unit = {}

        val loads = AtomicInteger()

        override fun loadAgentConfig(): Result<AgentConfig> {
            loads.incrementAndGet()
            onLoad()
            return result
        }
    }
}
