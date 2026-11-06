package com.nezhahq.agent.ui.main

import com.nezhahq.agent.SilentLoggerRule
import com.nezhahq.agent.simulator.SimulatedDeviceLoop
import com.nezhahq.agent.simulator.SimulatedDeviceReporter
import com.nezhahq.agent.simulator.SimulatorPhase
import com.nezhahq.agent.simulator.SimulatorSessionStore
import com.nezhahq.agent.ui.UiEvents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SimulatorControllerTest {
    @get:Rule val silentLogger = SilentLoggerRule()

    @Test fun startThenStopBeforeSaveCompletesCreatesNoReporterOrChannel() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val repository = ControllerRepository().apply {
            saveSimulatorHook = { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)); Result.success(Unit) }
        }
        val configuration = readyConfiguration(repository)
        val sessions = SimulatorSessionStore()
        val factories = AtomicInteger()
        val reports = AtomicInteger()
        val simulator = SimulatorController(configuration, sessions, UiEvents()) {
            factories.incrementAndGet()
            SimulatedDeviceLoop(SimulatedDeviceReporter { _, _ -> reports.incrementAndGet() })
        }
        try {
            simulator.start()
            assertEquals(SimulatorPhase.STARTING, simulator.state.value.phase)
            withTimeout(5_000) { entered.await() }
            simulator.requestStop(simulator.state.value.sessionId)
            withTimeout(5_000) { sessions.state.first { it.phase == SimulatorPhase.IDLE } }
        } finally { release.countDown() }
        configuration.awaitIdle()
        assertEquals(0, factories.get())
        assertEquals(0, reports.get())
        assertEquals(SimulatorPhase.IDLE, simulator.state.value.phase)
    }

    @Test fun replacementControllerCannotStartUntilOldFinalizerCompletesAndOldIdCannotStopIt() = runBlocking {
        val configuration = readyConfiguration()
        val sessions = SimulatorSessionStore()
        val entered = CompletableDeferred<Unit>()
        val finalizerEntered = CompletableDeferred<Unit>()
        val releaseFinalizer = CompletableDeferred<Unit>()
        val factories = AtomicInteger()
        val factory: (com.nezhahq.agent.simulator.SimulatorConnectionOwner) -> SimulatedDeviceLoop = {
            factories.incrementAndGet()
            SimulatedDeviceLoop(SimulatedDeviceReporter { _, _ ->
                try { entered.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) { finalizerEntered.complete(Unit); releaseFinalizer.await() } }
            })
        }
        val first = SimulatorController(configuration, sessions, UiEvents(), factory)
        first.start()
        configuration.awaitIdle()
        withTimeout(5_000) { entered.await() }
        val oldId = requireNotNull(sessions.state.value.sessionId)
        first.onCleared()
        withTimeout(5_000) { finalizerEntered.await() }
        val replacement = SimulatorController(configuration, sessions, UiEvents(), factory)
        assertEquals(SimulatorPhase.STOPPING, replacement.state.value.phase)
        replacement.start()
        assertEquals(1, factories.get())
        releaseFinalizer.complete(Unit)
        withTimeout(5_000) { sessions.state.first { it.phase == SimulatorPhase.IDLE } }
        replacement.start()
        configuration.awaitIdle()
        withTimeout(5_000) { sessions.state.first { it.phase == SimulatorPhase.RUNNING } }
        val nextId = requireNotNull(sessions.state.value.sessionId)
        assertNotEquals(oldId, nextId)
        first.requestStop(oldId)
        first.requestStop(nextId) // Even a new ID must not revive actions on a cleared controller.
        first.start()
        assertFalse(first.stopAndAwaitTermination(nextId))
        assertEquals(nextId, sessions.state.value.sessionId)
        assertEquals(SimulatorPhase.RUNNING, sessions.state.value.phase)
        replacement.requestStop(nextId)
        assertTrue(withTimeout(5_000) { replacement.stopAndAwaitTermination(nextId) })
    }

    @Test fun anActiveControllerRejectsItsOldStopButCanExplicitlyStopTheVisibleSharedSuccessor() = runBlocking {
        val configuration = readyConfiguration()
        val sessions = SimulatorSessionStore()
        val factory: (com.nezhahq.agent.simulator.SimulatorConnectionOwner) -> SimulatedDeviceLoop = {
            SimulatedDeviceLoop(SimulatedDeviceReporter { _, _ -> awaitCancellation() })
        }
        val visible = SimulatorController(configuration, sessions, UiEvents(), factory)
        visible.start()
        configuration.awaitIdle()
        val oldId = requireNotNull(sessions.state.value.sessionId)
        assertTrue(withTimeout(5_000) { visible.stopAndAwaitTermination(oldId) })
        val another = SimulatorController(configuration, sessions, UiEvents(), factory)
        another.start()
        configuration.awaitIdle()
        val currentId = requireNotNull(sessions.state.value.sessionId)
        assertNotEquals(oldId, currentId)
        visible.requestStop(oldId) // A callback bound to the old displayed session arrives late.
        assertFalse(visible.stopAndAwaitTermination(oldId))
        assertEquals(SimulatorPhase.RUNNING, visible.state.value.phase)
        assertEquals(currentId, visible.state.value.sessionId)
        // A fresh explicit click in this still-visible controller must remain useful, even though
        // its own last start was the old session and another controller owns the current one.
        visible.requestStop(currentId)
        assertTrue(withTimeout(5_000) { visible.stopAndAwaitTermination(currentId) })
        assertEquals(SimulatorPhase.IDLE, another.state.value.phase)
    }
}
