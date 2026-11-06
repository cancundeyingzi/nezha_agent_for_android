package com.nezhahq.agent.ui.main

import com.nezhahq.agent.core.config.StorageStatus
import com.nezhahq.agent.SilentLoggerRule
import com.nezhahq.agent.core.model.KeepAliveSettings
import com.nezhahq.agent.core.model.RemoteCapability
import com.nezhahq.agent.ui.UiEvents
import com.nezhahq.agent.util.ConfigurationReadiness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UiConfigurationCoordinatorTest {
    @get:Rule val silentLogger = SilentLoggerRule()

    @Test fun constructionAndUnreleasedReadinessNeverReadRepository() = runBlocking {
        val repository = ControllerRepository()
        val readiness = ConfigurationReadiness()
        val owner = Job()
        val coordinator = UiConfigurationCoordinator(CoroutineScope(coroutineContext + owner), repository, readiness, UiEvents())
        assertEquals(0, repository.reads.get())
        coordinator.initialize()
        yield() // Let initialization reach the unresolved readiness barrier.
        assertTrue(coordinator.state.value.loading)
        assertFalse(coordinator.editConnection { it.copy(secret = "early") })
        assertEquals(0, repository.reads.get())
        readiness.complete(StorageStatus.READY)
        withTimeout(5_000) { coordinator.state.first { !it.loading } }
        assertEquals(5, repository.reads.get())
        assertEquals("synthetic-secret", coordinator.state.value.connection.secret)
        owner.cancelAndJoin()
    }

    @Test fun unavailableStorageAllowsDraftEditingButRejectsEveryPersistenceEntry() = runBlocking {
        val repository = ControllerRepository().apply { status = StorageStatus.UNAVAILABLE }
        val coordinator = readyConfiguration(repository)
        assertTrue(coordinator.state.value.canEditDraft)
        assertFalse(coordinator.state.value.canPersist)
        assertTrue(coordinator.editConnection { it.copy(server = "edited.invalid") })
        coordinator.editSimulator { it.copy(secret = "new-simulator") }
        coordinator.saveTools(KeepAliveSettings(audio = true))
        coordinator.saveCapability(RemoteCapability.SHELL, true) { fail("must not refresh") }
        assertEquals(0, repository.writes.get())
        assertEquals("edited.invalid", coordinator.state.value.connection.server)
        assertEquals("new-simulator", coordinator.state.value.simulator.secret)
    }

    @Test fun suspendedParseSharesTheGateWithEditsStartsToolsRecoveryAndOtherParses() = runBlocking {
        val repository = ControllerRepository()
        val coordinator = readyConfiguration(repository)
        val parsed = CompletableDeferred<ConnectionFields>()
        var parses = 0
        coordinator.parseConnection({ parses++; parsed.await() }, {})
        assertTrue(coordinator.state.value.writing)
        assertFalse(coordinator.editConnection { it.copy(server = "raced") })
        coordinator.parseConnection({ parses++; it }, {})
        coordinator.saveTools(KeepAliveSettings(audio = true))
        coordinator.reset { fail("reset must not enter while parsing") }
        val platform = ControllerPlatform()
        RunControlController(coordinator, platform, UiEvents(), this).start(true)
        assertEquals(1, parses)
        assertEquals(0, repository.writes.get())
        parsed.complete(ConnectionFields(server = "parsed.invalid"))
        coordinator.awaitIdle()
        assertEquals("parsed.invalid", coordinator.state.value.connection.server)
        assertEquals(0, platform.starts)
        coordinator.parseConnection({ parses++; it.copy(server = "newer.invalid") }, {})
        coordinator.awaitIdle()
        assertEquals(2, parses)
        assertEquals("newer.invalid", coordinator.state.value.connection.server)
    }

    @Test fun cancellationAndParseFailureReleaseTheOnlyGate() = runBlocking {
        val owner = Job()
        val scope = CoroutineScope(coroutineContext + owner)
        val coordinator = scope.readyConfiguration()
        val before = coordinator.state.value.connection
        coordinator.parseConnection({ throw IllegalArgumentException("bad") }, {})
        assertFalse(coordinator.state.value.writing)
        assertEquals(before, coordinator.state.value.connection)
        coordinator.parseConnection({ awaitCancellation() }, { fail("cancelled parse applied") })
        assertTrue(coordinator.state.value.writing)
        withTimeout(5_000) { owner.cancelAndJoin() }
        assertFalse(coordinator.state.value.writing)
    }

    @Test fun ordinaryRetryPreservesEditedDraftIncludingRootAndSecret() = runBlocking {
        val repository = ControllerRepository().apply { status = StorageStatus.UNAVAILABLE }
        val coordinator = readyConfiguration(repository)
        coordinator.editConnection { it.copy(secret = "user-secret", rootMode = true) }
        coordinator.editSimulator { it.copy(server = "user-simulator.invalid") }
        repository.status = StorageStatus.READY
        coordinator.retry()
        coordinator.awaitIdle()
        assertEquals("user-secret", coordinator.state.value.connection.secret)
        assertTrue(coordinator.state.value.connection.rootMode)
        assertEquals("user-simulator.invalid", coordinator.state.value.simulator.server)
        assertTrue(coordinator.state.value.canPersist)
    }

    @Test fun retryPreservesExplicitEditsBackToDefaultsInsteadOfRestoringOldSecretsOrRoot() = runBlocking {
        val repository = ControllerRepository().apply {
            status = StorageStatus.UNAVAILABLE
            snapshot = snapshot.copy(connection = snapshot.connection.copy(useTls = false, rootMode = true))
        }
        val coordinator = readyConfiguration(repository)
        coordinator.editConnection { it.copy(secret = "", uuid = "", useTls = true, rootMode = false) }
        coordinator.editSimulator { it.copy(threadCount = "5", secret = "") }
        repository.status = StorageStatus.READY
        coordinator.retry()
        coordinator.awaitIdle()
        assertEquals("", coordinator.state.value.connection.secret)
        assertEquals("", coordinator.state.value.connection.uuid)
        assertTrue(coordinator.state.value.connection.useTls)
        assertFalse(coordinator.state.value.connection.rootMode)
        assertEquals("5", coordinator.state.value.simulator.threadCount)
        assertEquals("", coordinator.state.value.simulator.secret)
    }

    @Test fun persistenceInFlightRejectsParseWithoutInvokingItAndCancellationReleasesGate() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val repository = ControllerRepository().apply {
            saveConnectionHook = { entered.complete(Unit); check(release.await(5, TimeUnit.SECONDS)); Result.success(Unit) }
        }
        val owner = Job()
        val coordinator = CoroutineScope(coroutineContext + owner).readyConfiguration(repository)
        var parses = 0
        var savedCallbacks = 0
        try {
            coordinator.saveConnection(repository.snapshot.connection, { savedCallbacks++ }, {})
            withTimeout(5_000) { entered.await() }
            coordinator.parseConnection({ parses++; it.copy(server = "raced.invalid") }, {})
            assertEquals(0, parses)
            assertTrue(coordinator.state.value.writing)
            owner.cancel()
        } finally { release.countDown() }
        withTimeout(5_000) { owner.join() }
        assertFalse(coordinator.state.value.writing)
        assertEquals(0, savedCallbacks)
        assertEquals("fixture.invalid", coordinator.state.value.connection.server)
    }

    @Test fun failedConfirmedResetClearsOldDraftAndRetryCannotResurrectIt() = runBlocking {
        val repository = ControllerRepository().apply { resetSucceeds = false }
        val coordinator = readyConfiguration(repository)
        coordinator.editConnection { it.copy(secret = "old-secret", rootMode = true) }
        var stopRequests = 0
        var appliedResets = 0
        coordinator.reset(onResetSnapshotApplied = { appliedResets++ }) { stopRequests++ }
        assertEquals("", coordinator.state.value.connection.secret)
        assertFalse(coordinator.state.value.connection.rootMode)
        coordinator.awaitIdle()
        assertTrue(coordinator.state.value.resetPending)
        assertEquals(0, appliedResets)
        assertFalse(coordinator.editConnection { it.copy(secret = "must not revive") })
        repository.resetSucceeds = true
        coordinator.retry()
        coordinator.awaitIdle()
        assertEquals(1, stopRequests)
        assertEquals(2, repository.resets.get())
        assertEquals(1, appliedResets)
        assertEquals("", coordinator.state.value.connection.secret)
        assertFalse(coordinator.state.value.connection.rootMode)
        assertFalse(coordinator.state.value.resetPending)
        assertTrue(coordinator.state.value.canEditDraft)
        coordinator.retry()
        coordinator.awaitIdle()
        assertEquals("an ordinary retry must not repeat the reset projection", 1, appliedResets)
    }
}
