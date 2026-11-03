package com.nezhahq.agent.service

import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.RemoteCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeControllerTest {
    @Test
    fun oldRuntimeStopsBeforeReplacementIsCreatedAndStarted() = runBlocking {
        val events = mutableListOf<String>()
        var nextId = 0
        val controller = AgentRuntimeController(
            factory = AgentRuntimeFactory {
                val id = ++nextId
                events += "create-$id"
                RecordingRuntime(
                    onStart = { events += "start-$id" },
                    onStop = { events += "stop-$id" }
                )
            }
        )

        controller.reload(config("first"))
        controller.reload(config("second"))

        assertEquals(
            listOf("create-1", "start-1", "stop-1", "create-2", "start-2"),
            events
        )
    }

    @Test
    fun failedReplacementIsClosedAndControllerRemainsStopped() = runBlocking {
        var stops = 0
        val controller = AgentRuntimeController(
            factory = AgentRuntimeFactory {
                RecordingRuntime(
                    onStart = { error("start failed") },
                    onStop = { stops++ }
                )
            }
        )

        val failure = runCatching { controller.reload(config("failed")) }.exceptionOrNull()

        assertEquals("start failed", failure?.message)
        assertEquals(1, stops)
        assertFalse(controller.isRunning)
    }

    @Test
    fun repeatedStopIsIdempotentIncludingFinalCleanup() = runBlocking {
        var runtimeStops = 0
        var finalCleanups = 0
        val controller = AgentRuntimeController(
            factory = AgentRuntimeFactory {
                RecordingRuntime(onStop = { runtimeStops++ })
            },
            finalCleanup = { finalCleanups++ }
        )
        controller.reload(config("running"))

        controller.stop()
        controller.stop()

        assertEquals(1, runtimeStops)
        assertEquals(1, finalCleanups)
    }

    @Test
    fun capabilityUpdateTargetsOnlyCurrentRuntime() = runBlocking {
        val runtimes = mutableListOf<RecordingRuntime>()
        val controller = AgentRuntimeController(
            factory = AgentRuntimeFactory {
                RecordingRuntime().also(runtimes::add)
            }
        )
        val first = RemoteCapabilities(shellEnabled = true)
        val second = RemoteCapabilities(natEnabled = true)

        controller.reload(config("one"))
        controller.updateCapabilities(first)
        controller.reload(config("two"))
        controller.updateCapabilities(second)

        assertEquals(listOf(first), runtimes[0].capabilityUpdates)
        assertEquals(listOf(second), runtimes[1].capabilityUpdates)
        assertTrue(controller.isRunning)
    }

    @Test
    fun stopReleasesProcessResourcesEvenWhenTheRuntimeFailsToStop() = runBlocking {
        var finalCleanups = 0
        val controller = AgentRuntimeController(
            factory = AgentRuntimeFactory {
                RecordingRuntime(onStop = { error("teardown failed") })
            },
            finalCleanup = { finalCleanups++ }
        )
        controller.reload(config("running"))

        val failure = runCatching { controller.stop() }.exceptionOrNull()

        assertEquals("teardown failed", failure?.message)
        assertEquals(1, finalCleanups)
        assertFalse(controller.isRunning)
    }

    @Test
    fun stopReportsRuntimeAndCleanupFailuresTogether() = runBlocking {
        val controller = AgentRuntimeController(
            factory = AgentRuntimeFactory {
                RecordingRuntime(onStop = { error("teardown failed") })
            },
            finalCleanup = { error("cleanup failed") }
        )
        controller.reload(config("running"))

        val failure = runCatching { controller.stop() }.exceptionOrNull()

        assertEquals("teardown failed", failure?.message)
        assertEquals(
            listOf("cleanup failed"),
            failure?.suppressed?.map(Throwable::message)
        )
    }

    @Test
    fun failedOldCleanupPreventsCreationAndRetryStopsTheSameRuntimeFirst() = runBlocking {
        val events = mutableListOf<String>()
        val teardownFailures = mutableListOf<Throwable>()
        var failuresLeft = 2
        val controller = AgentRuntimeController(
            factory = AgentRuntimeFactory { snapshot ->
                events += "create-${snapshot.server}"
                RecordingRuntime(
                    onStart = { events += "start-${snapshot.server}" },
                    onStop = {
                        events += "stop-${snapshot.server}"
                        if (failuresLeft-- > 0) error("teardown failed")
                    }
                )
            },
            onTeardownFailure = { teardownFailures += it }
        )
        controller.reload(config("first"))

        repeat(2) {
            assertTrue(runCatching { controller.reload(config("second")) }.isFailure)
            assertFalse(controller.isRunning)
        }
        assertEquals(
            listOf("create-first", "start-first", "stop-first", "stop-first"),
            events
        )

        controller.reload(config("second"))

        assertEquals(
            listOf("create-first", "start-first", "stop-first", "stop-first", "stop-first",
                "create-second", "start-second"),
            events
        )
        assertEquals(2, teardownFailures.size)
        assertTrue(controller.isRunning)
    }

    @Test
    fun failedStartAndFailedDiscardRetainTheReplacementForCleanupRetry() = runBlocking {
        val events = mutableListOf<String>()
        var stops = 0
        val controller = AgentRuntimeController(AgentRuntimeFactory { snapshot ->
            events += "create-${snapshot.server}"
            RecordingRuntime(
                onStart = {
                    events += "start-${snapshot.server}"
                    if (snapshot.server == "broken") error("start failed")
                },
                onStop = {
                    events += "stop-${snapshot.server}"
                    if (++stops <= 2) error("stop failed")
                }
            )
        })

        val failure = runCatching { controller.reload(config("broken")) }.exceptionOrNull()
        assertEquals("start failed", failure?.message)
        assertEquals(listOf("stop failed"), failure?.suppressed?.map(Throwable::message))
        assertFalse(controller.isRunning)
        assertTrue(runCatching { controller.reload(config("next")) }.isFailure)
        assertEquals(1, events.count { it.startsWith("create-") })

        controller.reload(config("next"))

        assertEquals(
            listOf("create-broken", "start-broken", "stop-broken", "stop-broken", "stop-broken",
                "create-next", "start-next"),
            events
        )
    }

    @Test
    fun terminalStopRetriesFailedRuntimeAndProcessCleanupWithoutRestarting() = runBlocking {
        var stops = 0
        var finalCleanups = 0
        val controller = AgentRuntimeController(
            factory = AgentRuntimeFactory {
                RecordingRuntime(onStop = { if (++stops == 1) error("stop failed") })
            },
            finalCleanup = { if (++finalCleanups == 1) error("cleanup failed") }
        )
        controller.reload(config("running"))

        assertTrue(runCatching { controller.stop() }.isFailure)
        controller.stop()
        controller.stop()

        assertEquals(2, stops)
        assertEquals(2, finalCleanups)
        assertFalse(controller.isRunning)
        assertTrue(runCatching { controller.reload(config("after-stop")) }.isFailure)
    }

    @Test
    fun cancelledReloadFinishesOldCleanupButDoesNotCreateReplacement() = runBlocking {
        val stopping = CompletableDeferred<Unit>()
        val finishStop = CompletableDeferred<Unit>()
        var creates = 0
        val controller = AgentRuntimeController(AgentRuntimeFactory {
            creates++
            RecordingRuntime(onStop = {
                stopping.complete(Unit)
                finishStop.await()
            })
        })
        controller.reload(config("first"))
        val reload = launch { controller.reload(config("cancelled")) }
        withTimeout(2_000) { stopping.await() }

        reload.cancel()
        assertFalse(reload.isCompleted)
        finishStop.complete(Unit)
        withTimeout(2_000) { reload.join() }
        controller.stop()

        assertEquals(1, creates)
        assertFalse(controller.isRunning)
    }

    @Test
    fun cancelledStartRetainsFailedCleanupForTerminalStop() = runBlocking {
        val starting = CompletableDeferred<Unit>()
        var stops = 0
        val controller = AgentRuntimeController(AgentRuntimeFactory {
            RecordingRuntime(
                onStart = { starting.complete(Unit); awaitCancellation() },
                onStop = { if (++stops == 1) error("stop failed") }
            )
        })
        val reload = launch { controller.reload(config("cancelled")) }
        withTimeout(2_000) { starting.await() }

        withTimeout(2_000) { reload.cancelAndJoin() }
        controller.stop()

        assertEquals(2, stops)
        assertFalse(controller.isRunning)
    }

    @Test
    fun cleanupCancellationWithAnActiveCallerRemainsRetryableAndReportingCannotHideIt() = runBlocking {
        var stops = 0
        var creates = 0
        val controller = AgentRuntimeController(
            factory = AgentRuntimeFactory {
                creates++
                RecordingRuntime(onStop = {
                    if (++stops == 1) throw CancellationException("cleanup cancelled")
                })
            },
            onTeardownFailure = { error("reporting failed") }
        )
        controller.reload(config("first"))

        val failure = runCatching { controller.reload(config("second")) }.exceptionOrNull()
        assertTrue(failure is RuntimeShutdownPendingException)
        assertEquals("cleanup cancelled", failure?.cause?.message)
        assertEquals(1, creates)
        assertFalse(controller.isRunning)

        controller.reload(config("second"))
        assertEquals(2, creates)
        assertTrue(controller.isRunning)
    }

    @Test
    fun replacementWaitsForActualChildCoroutineFinalizerToFinish() = runBlocking {
        val supervisor = SupervisorJob()
        val finalizing = CompletableDeferred<Unit>()
        val finishFinalizer = CompletableDeferred<Unit>()
        val child = CoroutineScope(coroutineContext + supervisor).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    finalizing.complete(Unit)
                    finishFinalizer.await()
                }
            }
        }
        var creates = 0
        val coordinator = RuntimeShutdownCoordinator(
            cancelAndJoinWork = { supervisor.cancelAndJoin() },
            closeNetwork = {},
            closeKeepAlive = { true },
            closeGrpc = {}
        )
        val controller = AgentRuntimeController(AgentRuntimeFactory {
            creates++
            if (creates == 1) RecordingRuntime(onStop = { coordinator.close() })
            else RecordingRuntime()
        })
        controller.reload(config("first"))
        val reload = launch { controller.reload(config("second")) }

        try {
            withTimeout(2_000) { finalizing.await() }
            assertFalse(child.isCompleted)
            assertFalse(reload.isCompleted)
            assertEquals(1, creates)
            finishFinalizer.complete(Unit)
            withTimeout(2_000) { reload.join() }
            assertTrue(child.isCompleted)
            assertTrue(supervisor.isCompleted)
            assertEquals(2, creates)
        } finally {
            finishFinalizer.complete(Unit)
            supervisor.cancelAndJoin()
            reload.cancelAndJoin()
            controller.stop()
        }
    }

    /**
     * Collectors query the privileged shell as soon as the runtime starts, so authorization has to
     * be applied before it exists — and it must follow the snapshot being started, not the previous
     * one, or a reload that turns root mode off would leave it granted.
     */
    @Test
    fun privilegedAccessIsAppliedForEachSnapshotBeforeItsRuntimeStarts() = runBlocking {
        val events = mutableListOf<String>()
        val controller = AgentRuntimeController(
            factory = AgentRuntimeFactory { snapshot ->
                RecordingRuntime(onStart = { events += "start-rootMode=${snapshot.rootMode}" })
            },
            applyPrivilegedAccess = { enabled -> events += "authorize=$enabled" }
        )

        controller.reload(config("first").copy(rootMode = true))
        controller.reload(config("second").copy(rootMode = false))

        assertEquals(
            listOf(
                "authorize=true",
                "start-rootMode=true",
                "authorize=false",
                "start-rootMode=false"
            ),
            events
        )
    }

    @Test
    fun stopReleasesTheProcessShellThatReloadAuthorized() = runBlocking {
        val events = mutableListOf<String>()
        val controller = AgentRuntimeController(
            factory = AgentRuntimeFactory { RecordingRuntime() },
            applyPrivilegedAccess = { enabled -> events += "authorize=$enabled" },
            finalCleanup = { events += "release-shell" }
        )
        controller.reload(config("running").copy(rootMode = true))

        controller.stop()

        assertEquals(listOf("authorize=true", "release-shell"), events)
    }

    @Test
    fun finalCleanupReportFailureDoesNotInvalidateSuccessfulProcessCleanup() = runBlocking {
        var runtimeStops = 0
        var finalCleanups = 0
        var reports = 0
        val controller = AgentRuntimeController(
            factory = AgentRuntimeFactory { RecordingRuntime(onStop = { runtimeStops++ }) },
            finalCleanup = { finalCleanups++ },
            onFinalCleanupComplete = { reports++; error("success logger failed") }
        )
        controller.reload(config("running"))

        controller.stop()
        controller.stop()

        assertFalse(controller.isRunning)
        assertEquals(1, runtimeStops)
        assertEquals(1, finalCleanups)
        assertEquals(1, reports)
    }

    @Test
    fun terminalStopRestoresCallerCancellationAfterBothCleanupPhasesAndRetainsFailures() = runBlocking {
        for (failFirstStop in listOf(false, true)) {
            val stopping = CompletableDeferred<Unit>()
            val finishStop = CompletableDeferred<Unit>()
            val outcome = CompletableDeferred<Throwable?>()
            var stops = 0
            var finalCleanups = 0
            val controller = AgentRuntimeController(
                factory = AgentRuntimeFactory {
                    RecordingRuntime(onStop = {
                        stops++
                        stopping.complete(Unit)
                        finishStop.await()
                        if (failFirstStop && stops == 1) error("cleanup failed during cancellation")
                    })
                },
                finalCleanup = { finalCleanups++ }
            )
            controller.reload(config("running"))
            val stop = launch {
                outcome.complete(runCatching { controller.stop() }.exceptionOrNull())
            }

            try {
                withTimeout(2_000) { stopping.await() }
                stop.cancel()
                assertFalse(stop.isCompleted)
                finishStop.complete(Unit)

                val failure = withTimeout(2_000) { outcome.await() }
                withTimeout(2_000) { stop.join() }
                assertTrue(failure is CancellationException)
                assertEquals(1, finalCleanups)
                assertFalse(controller.isRunning)

                controller.stop()
                assertEquals(if (failFirstStop) 2 else 1, stops)
                assertEquals(1, finalCleanups)
            } finally {
                finishStop.complete(Unit)
                stop.cancelAndJoin()
                controller.stop()
            }
        }
    }

    private class RecordingRuntime(
        private val onStart: suspend () -> Unit = {},
        private val onStop: suspend () -> Unit = {}
    ) : AgentRuntimeHandle {
        val capabilityUpdates = mutableListOf<RemoteCapabilities>()

        override suspend fun start() = onStart()

        override suspend fun stop() = onStop()

        override suspend fun updateCapabilities(capabilities: RemoteCapabilities) {
            capabilityUpdates += capabilities
        }
    }
}

internal fun config(server: String) = AgentConfig(
    server = server,
    port = 5555,
    secret = "secret",
    uuid = "uuid",
    useTls = true,
    rootMode = false
)
