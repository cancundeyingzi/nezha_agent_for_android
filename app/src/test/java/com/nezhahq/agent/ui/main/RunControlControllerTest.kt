package com.nezhahq.agent.ui.main

import com.nezhahq.agent.core.config.StorageStatus
import com.nezhahq.agent.SilentLoggerRule
import com.nezhahq.agent.core.model.RemoteCapability
import com.nezhahq.agent.ui.UiEvents
import com.nezhahq.agent.ui.UiEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RunControlControllerTest {
    @get:Rule val silentLogger = SilentLoggerRule()

    @Test fun notificationIntentWaitsForItsMatchingResultAndStartsAtMostOnce() = runBlocking {
        val configuration = readyConfiguration()
        val platform = ControllerPlatform()
        val run = RunControlController(configuration, platform, UiEvents(), this)
        run.start(false)
        configuration.awaitIdle()
        val launchedRequestId = requireNotNull(run.state.value.pendingStart).requestId
        // This tests controller intent ownership. Actual host/registry saved-state restoration is
        // exercised by NotificationPermissionHostRegressionTest on Android.
        assertEquals(launchedRequestId, run.state.value.pendingStart?.requestId)
        assertEquals(0, platform.starts)
        run.notificationPermissionResult(launchedRequestId, true)
        run.notificationPermissionResult(launchedRequestId, true)
        assertEquals(1, platform.starts)
        assertNull(run.state.value.pendingStart)
    }

    @Test fun stopDuringSaveInvalidatesLateSuccessBeforeItCanRequestPermissionOrStart() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val repository = ControllerRepository().apply {
            saveConnectionHook = { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)); Result.success(Unit) }
        }
        val configuration = readyConfiguration(repository)
        val platform = ControllerPlatform()
        val run = RunControlController(configuration, platform, UiEvents(), this)
        try {
            run.start(false)
            withTimeout(5_000) { entered.await() }
            run.stop()
        } finally { release.countDown() }
        configuration.awaitIdle()
        assertEquals(1, platform.stops)
        assertEquals(0, platform.starts)
        assertNull(run.state.value.pendingStart)
        assertFalse(run.state.value.savingStart)
    }

    @Test fun oldPermissionResultCannotLaunchSuccessorAndDenialStillStartsTheCorrectRequest() = runBlocking {
        val configuration = readyConfiguration()
        val platform = ControllerPlatform()
        val run = RunControlController(configuration, platform, UiEvents(), this)
        run.start(false)
        configuration.awaitIdle()
        val old = requireNotNull(run.state.value.pendingStart).requestId
        run.stop()
        run.start(false)
        configuration.awaitIdle()
        val successor = requireNotNull(run.state.value.pendingStart).requestId
        assertNotEquals(old, successor)
        run.notificationPermissionResult(old, true)
        assertEquals(0, platform.starts)
        assertEquals(successor, run.state.value.pendingStart?.requestId)
        run.notificationPermissionResult(successor, false)
        assertEquals(1, platform.starts)
        run.notificationPermissionResult(successor, false)
        assertEquals(1, platform.starts)
    }

    @Test fun connectionEditAndConfirmedResetInvalidatePendingStart() = runBlocking {
        val configuration = readyConfiguration()
        val platform = ControllerPlatform()
        val run = RunControlController(configuration, platform, UiEvents(), this)
        val connection = ConnectionSettingsController(configuration, platform, UiEvents(), run::invalidateStart)
        run.start(false)
        configuration.awaitIdle()
        val edited = requireNotNull(run.state.value.pendingStart).requestId
        connection.secretChanged("new-secret")
        assertNull(run.state.value.pendingStart)
        run.notificationPermissionResult(edited, true)
        run.start(false)
        configuration.awaitIdle()
        val reset = requireNotNull(run.state.value.pendingStart).requestId
        configuration.reset { run.stop(); run.resetUi() }
        configuration.awaitIdle()
        run.notificationPermissionResult(reset, true)
        assertEquals(0, platform.starts)
        assertNull(run.state.value.pendingStart)
    }

    @Test fun storageFailureWhileAwaitingPermissionPreventsLaunch() = runBlocking {
        val repository = ControllerRepository()
        val configuration = readyConfiguration(repository)
        val platform = ControllerPlatform()
        val run = RunControlController(configuration, platform, UiEvents(), this)
        run.start(false)
        configuration.awaitIdle()
        val requestId = requireNotNull(run.state.value.pendingStart).requestId
        repository.status = StorageStatus.UNAVAILABLE
        configuration.retry()
        configuration.awaitIdle()
        run.notificationPermissionResult(requestId, true)
        assertEquals(0, platform.starts)
        assertNull(run.state.value.pendingStart)
    }

    @Test fun saveFailureNeverRequestsPermissionOrLaunchesAndReleasesGate() = runBlocking {
        val repository = ControllerRepository().apply { saveConnectionHook = { Result.failure(IOException("save failed")) } }
        val configuration = readyConfiguration(repository)
        val platform = ControllerPlatform()
        val run = RunControlController(configuration, platform, UiEvents(), this)
        run.start(false)
        configuration.awaitIdle()
        assertFalse(configuration.state.value.writing)
        assertFalse(run.state.value.savingStart)
        assertNull(run.state.value.pendingStart)
        assertEquals(0, platform.starts)
        repository.saveConnectionHook = { Result.success(Unit) }
        run.start(true)
        configuration.awaitIdle()
        assertEquals(1, platform.starts)
    }

    @Test fun capabilityRefreshDoesNotReconnectAndToolsWaitForExplicitRestart() = runBlocking {
        val configuration = readyConfiguration()
        val platform = ControllerPlatform()
        val run = RunControlController(configuration, platform, UiEvents(), this)
        run.capabilityChanged(RemoteCapability.SHELL, true)
        configuration.awaitIdle()
        assertEquals(1, platform.refreshes)
        run.keepAliveAudioChanged(true)
        configuration.awaitIdle()
        assertTrue(configuration.state.value.tools.audio)
        assertEquals(0, platform.starts)
        assertEquals(0, platform.stops)
        run.invalidateStart() // ViewModel destruction must not stop the foreground service.
        assertEquals(0, platform.stops)
    }

    @Test fun platformFailureAfterDurableSaveKeepsStorageAvailable() = runBlocking {
        val configuration = readyConfiguration()
        val platform = ControllerPlatform().apply {
            startAction = { throw IllegalStateException("foreground service unavailable") }
            refreshAction = { throw IllegalStateException("refresh unavailable") }
        }
        val run = RunControlController(configuration, platform, UiEvents(), this)
        run.start(true)
        configuration.awaitIdle()
        assertEquals(StorageStatus.READY, configuration.state.value.storageStatus)
        assertNull(configuration.state.value.storageError)
        assertNull(run.state.value.pendingStart)
        assertEquals(0, platform.starts)
        run.capabilityChanged(RemoteCapability.SHELL, true)
        configuration.awaitIdle()
        assertEquals(StorageStatus.READY, configuration.state.value.storageStatus)
        assertTrue(configuration.state.value.capabilities.shellEnabled)
        assertEquals(0, platform.refreshes)
    }

    @Test fun latePermissionResultDuringAnotherWriteClearsIntentWithoutLaunchingOrQueuing() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val repository = ControllerRepository()
        val configuration = readyConfiguration(repository)
        val platform = ControllerPlatform()
        val run = RunControlController(configuration, platform, UiEvents(), this)
        run.start(false)
        configuration.awaitIdle()
        val pending = requireNotNull(run.state.value.pendingStart).requestId
        repository.saveToolsHook = { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)); Result.success(Unit) }
        try {
            run.keepAliveAudioChanged(true)
            withTimeout(5_000) { entered.await() }
            run.notificationPermissionResult(pending, true)
            assertEquals(0, platform.starts)
            assertNull(run.state.value.pendingStart)
            assertFalse(run.state.value.savingStart)
        } finally { release.countDown() }
        configuration.awaitIdle()
        assertTrue(configuration.state.value.tools.audio)
        run.notificationPermissionResult(pending, true)
        assertEquals(0, platform.starts)
        run.start(true)
        configuration.awaitIdle()
        assertEquals(1, platform.starts)
    }

    @Test fun toolChangesPromptForRestartOnlyAfterSuccessfulSaveAndNeverRestartAutomatically() = runBlocking {
        val repository = ControllerRepository()
        val events = UiEvents()
        val configuration = readyConfiguration(repository, events)
        val platform = ControllerPlatform(sdkInt = 30)
        val run = RunControlController(configuration, platform, events, this)
        val changes = listOf(run::keepAliveAudioChanged, run::floatWindowChanged, run::vpnTrafficChanged)
        for (change in changes) {
            repository.saveToolsHook = { Result.success(Unit) }
            change(true)
            configuration.awaitIdle()
            repository.saveToolsHook = { Result.failure(IOException("save failed")) }
            change(false)
            configuration.awaitIdle()
        }
        // Each operation has completed on this dispatcher. A sentinel drains all buffered events
        // deterministically without sleeping or guessing how long a missing event might take.
        val sentinel = UiEvent.Message("test-events-complete")
        events.send(sentinel.text)
        val emitted = withTimeout(5_000) { events.flow.takeWhile { it != sentinel }.toList() }
        assertEquals(6, emitted.size)
        emitted.chunked(2).forEach { pair ->
            assertEquals(UiEvent.LongMessage("配置已保存，请在主页停止并重新启动探针以生效"), pair[0])
            assertTrue("failed saves must only report the storage failure", pair[1] is UiEvent.LongMessage)
            assertNotEquals("failed saves must not repeat the saved confirmation", pair[0], pair[1])
        }
        assertTrue(configuration.state.value.tools.audio)
        assertTrue(configuration.state.value.tools.overlay)
        assertTrue(configuration.state.value.tools.vpn)
        assertEquals(0, platform.starts)
        assertEquals(0, platform.stops)
    }
}
