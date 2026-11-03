package com.nezhahq.agent.service.keepalive

import com.nezhahq.agent.SilentLoggerRule
import com.nezhahq.agent.core.model.KeepAliveSettings
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.PlatformLogSink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.Executors

class KeepAliveControllerTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    @Test
    fun reconfigureAndCloseAreRepeatableAndSettingsTransitionIndependently() = runBlocking {
        val audio = RecordingResource()
        val overlay = RecordingResource()
        val wakeLock = RecordingResource()
        val vpn = RecordingResource()
        val controller = KeepAliveController(audio, overlay, wakeLock, vpn)
        val initial = KeepAliveSettings(audio = true, overlay = false, vpn = false)

        controller.reconfigure(initial)
        controller.reconfigure(initial)

        assertEquals(1, audio.enableCount)
        assertEquals(0, overlay.enableCount)
        assertEquals(1, wakeLock.enableCount)
        assertEquals(0, vpn.enableCount)

        controller.reconfigure(KeepAliveSettings(audio = false, overlay = true, vpn = true))

        assertEquals(1, audio.disableCount)
        assertEquals(1, overlay.enableCount)
        assertEquals(1, vpn.enableCount)
        assertTrue(wakeLock.enabled)

        controller.close()
        controller.close()

        assertEquals(1, audio.closeCount)
        assertEquals(1, overlay.closeCount)
        assertEquals(1, wakeLock.closeCount)
        assertEquals(1, vpn.closeCount)

        controller.reconfigure(initial)

        assertTrue(audio.enabled)
        assertFalse(overlay.enabled)
        assertTrue(wakeLock.enabled)
        assertFalse(vpn.enabled)
    }

    @Test
    fun reconfigureAttemptsEveryResourceWhenComponentsThrow() = runBlocking {
        val audio = ThrowingResource(failSetEnabled = true)
        val overlay = ThrowingResource()
        val wakeLock = ThrowingResource(failSetEnabled = true)
        val vpn = ThrowingResource()
        val controller = KeepAliveController(audio, overlay, wakeLock, vpn)

        controller.reconfigure(KeepAliveSettings(audio = true, overlay = true, vpn = true))

        assertEquals(1, audio.setEnabledCount)
        assertEquals(1, overlay.setEnabledCount)
        assertEquals(1, wakeLock.setEnabledCount)
        assertEquals(1, vpn.setEnabledCount)
    }

    @Test
    fun closeAttemptsEveryResourceWhenComponentsThrow() = runBlocking {
        val audio = ThrowingResource(failClose = true)
        val overlay = ThrowingResource()
        val wakeLock = ThrowingResource()
        val vpn = ThrowingResource(failClose = true)
        val controller = KeepAliveController(audio, overlay, wakeLock, vpn)

        controller.close()

        assertEquals(1, audio.closeCount)
        assertEquals(1, overlay.closeCount)
        assertEquals(1, vpn.closeCount)
        assertEquals(1, wakeLock.closeCount)
    }

    @Test
    fun failingPlatformLogsDoNotInterruptLaterResourcesAfterCloseErrors() = runBlocking {
        val uncaught = mutableListOf<Throwable>()
        val cleanupJob = SupervisorJob()
        val cleanupScope = CoroutineScope(
            coroutineContext + cleanupJob + CoroutineExceptionHandler { _, failure -> uncaught += failure }
        )
        val overlay = ThrowingResource(failClose = true)
        val audio = ThrowingResource(failClose = true)
        val vpn = ThrowingResource(failClose = true)
        val wakeLock = RecordingResource()
        val controller = KeepAliveController(audio, overlay, wakeLock, vpn, cleanupScope)
        val previousSink = Logger.platformSink
        val failingSink = FailingPlatformLogSink()
        Logger.platformSink = failingSink

        try {
            assertTrue(withTimeout(2_000) { controller.closeWithin(1_000) })
            withTimeout(2_000) { cleanupJob.join() }

            assertEquals(1, overlay.closeCount)
            assertEquals(1, audio.closeCount)
            assertEquals(1, vpn.closeCount)
            assertEquals(1, wakeLock.closeCount)
            assertEquals(3, failingSink.errors.get())
            assertTrue(uncaught.isEmpty())
        } finally {
            cleanupJob.cancelAndJoin()
            Logger.platformSink = previousSink
        }
    }

    @Test
    fun failingPlatformLogsDoNotInterruptLaterResourcesAfterIndividualTimeouts() = runBlocking {
        val uncaught = mutableListOf<Throwable>()
        val cleanupJob = SupervisorJob()
        val cleanupScope = CoroutineScope(
            coroutineContext + cleanupJob + CoroutineExceptionHandler { _, failure -> uncaught += failure }
        )
        val overlay = SuspendingCloseResource()
        val audio = SuspendingCloseResource()
        val vpn = RecordingResource()
        val wakeLock = RecordingResource()
        val controller = KeepAliveController(audio, overlay, wakeLock, vpn, cleanupScope)
        val previousSink = Logger.platformSink
        val failingSink = FailingPlatformLogSink()
        Logger.platformSink = failingSink

        try {
            assertTrue(withTimeout(2_000) { controller.closeWithin(1_000) })
            withTimeout(2_000) { cleanupJob.join() }

            assertTrue(overlay.closeStarted)
            assertTrue(audio.closeStarted)
            assertEquals(1, vpn.closeCount)
            assertEquals(1, wakeLock.closeCount)
            assertEquals(2, failingSink.errors.get())
            assertTrue(uncaught.isEmpty())
        } finally {
            cleanupJob.cancelAndJoin()
            Logger.platformSink = previousSink
        }
    }

    @Test
    fun failingPlatformLogCannotConsumeCancellationOfTheCleanupCaller() = runBlocking {
        val uncaught = mutableListOf<Throwable>()
        val cleanupJob = SupervisorJob()
        val cleanupScope = CoroutineScope(
            coroutineContext + cleanupJob + CoroutineExceptionHandler { _, failure -> uncaught += failure }
        )
        val overlay = ThrowingResource(failClose = true)
        val audio = RecordingResource()
        val vpn = RecordingResource()
        val wakeLock = RecordingResource()
        val controller = KeepAliveController(audio, overlay, wakeLock, vpn, cleanupScope)
        val previousSink = Logger.platformSink
        val failingSink = FailingPlatformLogSink(beforeError = { cleanupJob.cancel() })
        Logger.platformSink = failingSink

        try {
            assertFalse(withTimeout(2_000) { controller.closeWithin(1_000) })
            withTimeout(2_000) { cleanupJob.join() }

            assertTrue(cleanupJob.isCancelled)
            assertEquals(1, overlay.closeCount)
            assertEquals(0, audio.closeCount)
            assertEquals(0, vpn.closeCount)
            assertEquals(0, wakeLock.closeCount)
            assertEquals(1, failingSink.errors.get())
            assertTrue(uncaught.isEmpty())
        } finally {
            cleanupJob.cancelAndJoin()
            Logger.platformSink = previousSink
        }
    }

    @Test
    fun normalReconfigurePropagatesCancellation() = runBlocking {
        val controller = KeepAliveController(
            CancellationResource(cancelSetEnabled = true),
            RecordingResource(),
            RecordingResource(),
            RecordingResource()
        )

        var cancellation: CancellationException? = null
        try {
            controller.reconfigure(KeepAliveSettings(audio = true))
        } catch (e: CancellationException) {
            cancellation = e
        }

        assertTrue(cancellation != null)
    }

    @Test
    fun normalClosePropagatesCancellation() = runBlocking {
        val controller = KeepAliveController(
            CancellationResource(cancelClose = true),
            RecordingResource(),
            RecordingResource(),
            RecordingResource()
        )

        var cancellation: CancellationException? = null
        try {
            controller.close()
        } catch (e: CancellationException) {
            cancellation = e
        }

        assertTrue(cancellation != null)
    }

    /**
     * The overlay is released before any resource that can hang.
     *
     * It is the only one whose loss is permanent: an abandoned `TYPE_APPLICATION_OVERLAY` survives
     * the runtime that created it and nothing can reach it afterwards, whereas a wake lock or an
     * audio track dies with the process. So it goes first, and a stuck audio teardown must find it
     * already gone.
     */
    @Test
    fun boundedCloseReleasesTheOverlayBeforeAnythingThatCanHang() = runBlocking {
        val cleanupExecutor = Executors.newSingleThreadExecutor()
        val cleanupDispatcher = cleanupExecutor.asCoroutineDispatcher()
        val cleanupScope = CoroutineScope(SupervisorJob() + cleanupDispatcher)
        val blocker = BlockingCloseResource()
        val overlay = RecordingResource()
        val vpn = RecordingResource()
        val wakeLock = LatchingCloseResource()
        val waiter = KeepAliveCloseWaiter { _, _ ->
            assertTrue(blocker.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            false
        }
        val controller = KeepAliveController(
            blocker,
            overlay,
            wakeLock,
            vpn,
            cleanupScope,
            waiter
        )

        try {
            assertFalse(controller.closeWithin(750L))
            assertEquals(1, overlay.closeCount)
        } finally {
            blocker.unblock()
            cleanupScope.cancel()
            cleanupDispatcher.close()
        }
    }

    /**
     * One resource overrunning its budget must not consume the whole deadline.
     *
     * A single shared budget meant the first slow release used all of it and every later resource
     * was cancelled without being attempted even once.
     */
    @Test
    fun aResourceThatOverrunsItsBudgetDoesNotStarveTheOnesAfterIt() = runBlocking {
        val overlay = SuspendingCloseResource()
        val audio = RecordingResource()
        val vpn = RecordingResource()
        val wakeLock = RecordingResource()
        val controller = KeepAliveController(audio, overlay, wakeLock, vpn)

        controller.close(perResourceTimeoutMillis = 50L)

        assertTrue(overlay.closeStarted)
        assertEquals(1, audio.closeCount)
        assertEquals(1, vpn.closeCount)
        assertEquals(1, wakeLock.closeCount)
    }

    @Test
    fun audioRapidRestartNeverOverlapsAndReleasesEachOutput() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val activeCount = AtomicInteger()
        val maxActiveCount = AtomicInteger()
        val created = LinkedBlockingQueue<BlockingAudioOutput>()
        val audio = AudioKeepAlive(scope, dispatcher, outputFactory = AudioOutputFactory {
            BlockingAudioOutput(activeCount, maxActiveCount).also(created::add)
        })

        try {
            audio.setEnabled(true)
            val first = created.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertTrue(first.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            audio.setEnabled(false)
            assertTrue(first.released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            audio.setEnabled(true)
            val second = created.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertTrue(second.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            audio.close()

            assertTrue(second.released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(1, maxActiveCount.get())
            assertEquals(0, activeCount.get())
        } finally {
            audio.close()
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun audioReleasesAfterStartupFailureAndCanStartAgain() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val created = LinkedBlockingQueue<FailingAudioOutput>()
        val audio = AudioKeepAlive(scope, dispatcher, outputFactory = AudioOutputFactory {
            FailingAudioOutput().also(created::add)
        })

        try {
            audio.setEnabled(true)
            val first = created.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertTrue(first.released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            audio.setEnabled(true)
            val second = created.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertTrue(second.released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(1, first.releaseCount.get())
            assertEquals(1, second.releaseCount.get())
        } finally {
            audio.close()
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun audioReleasesAfterWriteFailure() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val output = WriteFailingAudioOutput()
        val audio = AudioKeepAlive(
            scope,
            dispatcher,
            outputFactory = AudioOutputFactory { output }
        )

        try {
            audio.setEnabled(true)

            assertTrue(output.released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(1, output.releaseCount.get())
        } finally {
            audio.close()
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun audioCloseContinuesAfterInterruptThrowsAndReleaseIsAttemptedOffCaller() = runBlocking {
        val writerExecutor = Executors.newSingleThreadExecutor()
        val writerDispatcher = writerExecutor.asCoroutineDispatcher()
        val cleanupExecutor = Executors.newFixedThreadPool(2)
        val cleanupDispatcher = cleanupExecutor.asCoroutineDispatcher()
        val writerScope = CoroutineScope(SupervisorJob() + writerDispatcher)
        val cleanupScope = CoroutineScope(SupervisorJob() + cleanupDispatcher)
        val output = InterruptThrowingAudioOutput()
        val audio = AudioKeepAlive(
            scope = writerScope,
            dispatcher = writerDispatcher,
            outputFactory = AudioOutputFactory { output },
            cleanupScope = cleanupScope,
            cleanupWaiter = AudioCleanupWaiter { }
        )
        val overlay = RecordingResource()
        val controller = KeepAliveController(
            audio,
            overlay,
            RecordingResource(),
            RecordingResource()
        )
        val callerThread = Thread.currentThread()

        try {
            audio.setEnabled(true)
            assertTrue(output.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            controller.close()

            assertTrue(output.releaseAttempted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(1, overlay.closeCount)
            assertTrue(output.interruptThread.get() !== callerThread)
            assertTrue(output.releaseThread.get() !== callerThread)
        } finally {
            output.finishWrite()
            audio.close()
            writerScope.cancel()
            cleanupScope.cancel()
            writerDispatcher.close()
            cleanupDispatcher.close()
        }
    }

    @Test
    fun audioBoundedCloseDoesNotRestartUntilPathologicalWriterFinishes() = runBlocking {
        val writerExecutor = Executors.newFixedThreadPool(2)
        val writerDispatcher = writerExecutor.asCoroutineDispatcher()
        val cleanupExecutor = Executors.newFixedThreadPool(2)
        val cleanupDispatcher = cleanupExecutor.asCoroutineDispatcher()
        val writerScope = CoroutineScope(SupervisorJob() + writerDispatcher)
        val cleanupScope = CoroutineScope(SupervisorJob() + cleanupDispatcher)
        val first = PathologicalAudioOutput()
        val replacement = BlockingAudioOutput(AtomicInteger(), AtomicInteger())
        val createdCount = AtomicInteger()
        val audio = AudioKeepAlive(
            scope = writerScope,
            dispatcher = writerDispatcher,
            outputFactory = AudioOutputFactory {
                if (createdCount.getAndIncrement() == 0) first else replacement
            },
            cleanupScope = cleanupScope,
            cleanupWaiter = AudioCleanupWaiter { }
        )

        try {
            audio.setEnabled(true)
            assertTrue(first.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            audio.close()
            assertTrue(first.interruptAttempted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(first.releaseAttempted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            audio.setEnabled(true)
            writerExecutor.submit {}.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(1, createdCount.get())

            first.finishCleanup()
            assertTrue(replacement.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(2, createdCount.get())
        } finally {
            first.finishCleanup()
            audio.close()
            replacement.released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            writerScope.cancel()
            cleanupScope.cancel()
            writerDispatcher.close()
            cleanupDispatcher.close()
        }
    }

    @Test
    fun audioFactoryCancellationRemainsOwnedUntilReleaseCompletes() = runBlocking {
        val writerExecutor = Executors.newFixedThreadPool(2)
        val writerDispatcher = writerExecutor.asCoroutineDispatcher()
        val cleanupExecutor = Executors.newFixedThreadPool(2)
        val cleanupDispatcher = cleanupExecutor.asCoroutineDispatcher()
        val writerScope = CoroutineScope(SupervisorJob() + writerDispatcher)
        val cleanupScope = CoroutineScope(SupervisorJob() + cleanupDispatcher)
        val first = FactoryBlockedAudioOutput()
        val replacement = BlockingAudioOutput(AtomicInteger(), AtomicInteger())
        val factoryEntered = CountDownLatch(1)
        val factoryGate = CountDownLatch(1)
        val factoryCount = AtomicInteger()
        val audio = AudioKeepAlive(
            scope = writerScope,
            dispatcher = writerDispatcher,
            outputFactory = AudioOutputFactory {
                if (factoryCount.getAndIncrement() == 0) {
                    factoryEntered.countDown()
                    factoryGate.await()
                    first
                } else {
                    replacement
                }
            },
            cleanupScope = cleanupScope,
            cleanupWaiter = AudioCleanupWaiter { }
        )

        try {
            audio.setEnabled(true)
            assertTrue(factoryEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            audio.setEnabled(false)
            audio.setEnabled(true)
            factoryGate.countDown()
            assertTrue(first.releaseAttempted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            writerExecutor.submit {}.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(1, factoryCount.get())

            first.allowRelease()

            assertTrue(replacement.started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertEquals(2, factoryCount.get())
        } finally {
            factoryGate.countDown()
            first.allowRelease()
            audio.close()
            replacement.released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            writerScope.cancel()
            cleanupScope.cancel()
            writerDispatcher.close()
            cleanupDispatcher.close()
        }
    }

    @Test
    fun overlayAddFailureClearsStateButRemovalFailureRetainsOwnershipForRetry() = runBlocking {
        val addFailingHost = FakeOverlayHost(failAdd = true)
        val addFailingOverlay = OverlayKeepAlive(addFailingHost, Dispatchers.Unconfined)

        addFailingOverlay.setEnabled(true)
        addFailingOverlay.setEnabled(true)

        assertEquals(2, addFailingHost.addCount)

        val removeFailingHost = FakeOverlayHost(removeFailuresRemaining = 1)
        val removeFailingOverlay = OverlayKeepAlive(removeFailingHost, Dispatchers.Unconfined)
        removeFailingOverlay.setEnabled(true)
        removeFailingOverlay.close()
        removeFailingOverlay.setEnabled(true)

        assertEquals(1, removeFailingHost.addCount)
        assertEquals(1, removeFailingHost.removeCount)

        removeFailingOverlay.close()
        removeFailingOverlay.setEnabled(true)

        assertEquals(2, removeFailingHost.addCount)
        assertEquals(2, removeFailingHost.removeCount)
    }

    /**
     * A cancelled teardown still gets the window off the screen.
     *
     * Removal has to run on the main thread. When that thread was busy, the teardown deadline used
     * to cancel the removal mid-suspend and drop the handle with it, stranding one overlay window
     * per reload with nothing left holding a reference to it. Cancellation must now hand the
     * removal to the main looper rather than abandon it.
     */
    /**
     * A cancelled teardown still gets the window off the screen.
     *
     * Removal has to run on the main thread. When that thread was busy, the teardown deadline
     * cancelled the removal mid-suspend and dropped the handle with it, stranding one
     * `TYPE_APPLICATION_OVERLAY` per reload with nothing left holding a reference to it.
     * Cancellation must hand the removal off instead of abandoning it.
     *
     * The main thread is occupied, the deadline gives up and cancels, and only then does the main
     * thread free up — the order that matters, because a suspended `withContext` cannot even
     * unwind until its dispatcher runs again.
     */
    @Test
    fun cancellingTheOverlayTeardownStillHandsOffTheRemoval() {
        val mainExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, MAIN_THREAD_NAME)
        }
        val mainDispatcher = mainExecutor.asCoroutineDispatcher()
        val mainThreadBusy = CountDownLatch(1)
        val host = FakeOverlayHost()
        val overlay = OverlayKeepAlive(host, mainDispatcher)

        try {
            runBlocking {
                overlay.setEnabled(true)
                val handle = host.lastHandle!!
                assertEquals(0, handle.deferredRemovals)

                mainExecutor.submit { mainThreadBusy.await() }

                val closing = launch(Dispatchers.Default) { overlay.close() }
                // Parked waiting for the main thread, exactly where the deadline used to strand it.
                assertNull(withTimeoutOrNull(TIMEOUT_MILLIS) { closing.join() })

                closing.cancel()
                mainThreadBusy.countDown()
                closing.join()

                // Handed to the looper rather than abandoned, and the window really does go away.
                assertEquals(1, handle.deferredRemovals)
                assertEquals(1, host.removeCount)
            }
        } finally {
            mainThreadBusy.countDown()
            mainDispatcher.close()
            mainExecutor.shutdownNow()
        }
    }

    @Test
    fun overlayWindowWorkStaysOnTheMainDispatcherWhateverThreadClosesIt() {
        val mainExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, MAIN_THREAD_NAME)
        }
        val mainDispatcher = mainExecutor.asCoroutineDispatcher()
        val host = ThreadRecordingOverlayHost()
        val overlay = OverlayKeepAlive(host, mainDispatcher)

        try {
            // Enabling happens on the main thread in production, teardown on a background scope.
            runBlocking(mainDispatcher) { overlay.setEnabled(true) }
            runBlocking(Dispatchers.IO) { overlay.close() }

            assertEquals(listOf(MAIN_THREAD_NAME, MAIN_THREAD_NAME), host.recordedThreads())
        } finally {
            mainDispatcher.close()
            mainExecutor.shutdownNow()
        }
    }

    @Test
    fun wakeLockUsesBoundedRenewableLeaseWithoutDuplicateSchedulers() = runBlocking {
        val lease = FakeWakeLockLease()
        val scheduler = FakeRenewalScheduler()
        val wakeLock = WakeLockKeepAlive(lease, scheduler)

        wakeLock.setEnabled(true)
        wakeLock.setEnabled(true)

        assertEquals(listOf(WakeLockKeepAlive.LEASE_MILLIS), lease.acquireTimeouts)
        assertEquals(WakeLockKeepAlive.RENEWAL_MILLIS, scheduler.initialDelayMillis)
        assertEquals(WakeLockKeepAlive.RENEWAL_MILLIS, scheduler.intervalMillis)
        assertEquals(1, scheduler.scheduleCount)

        scheduler.fire()
        assertEquals(
            listOf(WakeLockKeepAlive.LEASE_MILLIS, WakeLockKeepAlive.LEASE_MILLIS),
            lease.acquireTimeouts
        )

        wakeLock.close()
        wakeLock.close()
        scheduler.fire()

        assertEquals(1, scheduler.cancelCount)
        assertEquals(1, lease.releaseCount)
        assertEquals(2, lease.acquireTimeouts.size)

        wakeLock.setEnabled(true)
        assertEquals(2, scheduler.scheduleCount)
        assertEquals(3, lease.acquireTimeouts.size)
    }

    @Test
    fun vpnTransitionsAreIdempotentAndCloseStopsExistingService() = runBlocking {
        val host = FakeVpnHost()
        val vpn = PlaceholderVpnKeepAlive(host)

        vpn.setEnabled(true)
        vpn.setEnabled(true)
        vpn.setEnabled(false)
        vpn.setEnabled(false)
        vpn.close()
        vpn.close()

        assertEquals(1, host.startCount)
        assertEquals(2, host.stopCount)

        vpn.setEnabled(true)
        assertEquals(2, host.startCount)
    }

    private class FailingPlatformLogSink(private val beforeError: () -> Unit = {}) : PlatformLogSink {
        val errors = AtomicInteger()

        override fun info(message: String) {
            throw IllegalStateException("platform info sink failed")
        }

        override fun error(message: String, throwable: Throwable?) {
            errors.incrementAndGet()
            beforeError()
            throw IllegalStateException("platform error sink failed")
        }
    }

    private class RecordingResource : KeepAliveResource {
        var enabled = false
        var closed = false
        var enableCount = 0
        var disableCount = 0
        var closeCount = 0

        override suspend fun setEnabled(enabled: Boolean) {
            closed = false
            if (this.enabled == enabled) return
            this.enabled = enabled
            if (enabled) enableCount++ else disableCount++
        }

        override suspend fun close() {
            if (closed) return
            closed = true
            enabled = false
            closeCount++
        }
    }

    private class ThrowingResource(
        private val failSetEnabled: Boolean = false,
        private val failClose: Boolean = false
    ) : KeepAliveResource {
        var setEnabledCount = 0
        var closeCount = 0

        override suspend fun setEnabled(enabled: Boolean) {
            setEnabledCount++
            if (failSetEnabled) throw IllegalStateException("setEnabled failed")
        }

        override suspend fun close() {
            closeCount++
            if (failClose) throw IllegalStateException("close failed")
        }
    }

    private class CancellationResource(
        private val cancelSetEnabled: Boolean = false,
        private val cancelClose: Boolean = false
    ) : KeepAliveResource {
        override suspend fun setEnabled(enabled: Boolean) {
            if (cancelSetEnabled) throw CancellationException("setEnabled cancelled")
        }

        override suspend fun close() {
            if (cancelClose) throw CancellationException("close cancelled")
        }
    }

    private class BlockingCloseResource : KeepAliveResource {
        val started = CountDownLatch(1)
        private val gate = CountDownLatch(1)

        override suspend fun setEnabled(enabled: Boolean) = Unit

        override suspend fun close() {
            started.countDown()
            gate.await()
        }

        fun unblock() {
            gate.countDown()
        }
    }

    /** Hangs at a suspension point, so a per-resource timeout can actually cut it short. */
    private class SuspendingCloseResource : KeepAliveResource {
        @Volatile var closeStarted = false
            private set

        override suspend fun setEnabled(enabled: Boolean) = Unit

        override suspend fun close() {
            closeStarted = true
            awaitCancellation()
        }
    }

    private class LatchingCloseResource : KeepAliveResource {
        var closeCount = 0
        val closed = CountDownLatch(1)

        override suspend fun setEnabled(enabled: Boolean) = Unit

        override suspend fun close() {
            closeCount++
            closed.countDown()
        }
    }

    private class BlockingAudioOutput(
        private val activeCount: AtomicInteger,
        private val maxActiveCount: AtomicInteger
    ) : AudioOutput {
        override val bufferSizeSamples = 8
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        private val writeGate = CountDownLatch(1)

        override fun start() {
            val active = activeCount.incrementAndGet()
            maxActiveCount.updateAndGet { current -> maxOf(current, active) }
            started.countDown()
        }

        override fun write(samples: ShortArray): Int {
            writeGate.await()
            return samples.size
        }

        override fun interrupt() {
            writeGate.countDown()
        }

        override fun release() {
            activeCount.decrementAndGet()
            released.countDown()
        }
    }

    private class FailingAudioOutput : AudioOutput {
        override val bufferSizeSamples = 8
        val released = CountDownLatch(1)
        val releaseCount = AtomicInteger()

        override fun start() = throw IllegalStateException("start failed")

        override fun write(samples: ShortArray): Int = samples.size

        override fun interrupt() = Unit

        override fun release() {
            releaseCount.incrementAndGet()
            released.countDown()
        }
    }

    private class WriteFailingAudioOutput : AudioOutput {
        override val bufferSizeSamples = 8
        val released = CountDownLatch(1)
        val releaseCount = AtomicInteger()

        override fun start() = Unit

        override fun write(samples: ShortArray): Int = -1

        override fun interrupt() = Unit

        override fun release() {
            releaseCount.incrementAndGet()
            released.countDown()
        }
    }

    private class InterruptThrowingAudioOutput : AudioOutput {
        override val bufferSizeSamples = 8
        val started = CountDownLatch(1)
        val releaseAttempted = CountDownLatch(1)
        val interruptThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val releaseThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        private val writeGate = CountDownLatch(1)

        override fun start() {
            started.countDown()
        }

        override fun write(samples: ShortArray): Int {
            writeGate.await()
            return samples.size
        }

        override fun interrupt() {
            interruptThread.set(Thread.currentThread())
            throw IllegalStateException("interrupt failed")
        }

        override fun release() {
            releaseThread.set(Thread.currentThread())
            releaseAttempted.countDown()
            writeGate.countDown()
        }

        fun finishWrite() {
            writeGate.countDown()
        }
    }

    private class PathologicalAudioOutput : AudioOutput {
        override val bufferSizeSamples = 8
        val started = CountDownLatch(1)
        val interruptAttempted = CountDownLatch(1)
        val releaseAttempted = CountDownLatch(1)
        private val writeGate = CountDownLatch(1)
        private val interruptGate = CountDownLatch(1)
        private val releaseGate = CountDownLatch(1)

        override fun start() {
            started.countDown()
        }

        override fun write(samples: ShortArray): Int {
            writeGate.await()
            return samples.size
        }

        override fun interrupt() {
            interruptAttempted.countDown()
            interruptGate.await()
        }

        override fun release() {
            releaseAttempted.countDown()
            releaseGate.await()
        }

        fun finishCleanup() {
            interruptGate.countDown()
            releaseGate.countDown()
            writeGate.countDown()
        }
    }

    private class FactoryBlockedAudioOutput : AudioOutput {
        override val bufferSizeSamples = 8
        val releaseAttempted = CountDownLatch(1)
        private val releaseGate = CountDownLatch(1)

        override fun start() = error("cancelled output must not start")

        override fun write(samples: ShortArray): Int = samples.size

        override fun interrupt() = Unit

        override fun release() {
            releaseAttempted.countDown()
            releaseGate.await()
        }

        fun allowRelease() {
            releaseGate.countDown()
        }
    }

    /**
     * Runs the deferred removal inline instead of posting it, so a test can observe that the
     * overlay was handed off rather than dropped.
     */
    private class FakeOverlayHandle(private val onRemove: () -> Boolean) : OverlayHandle {
        var deferredRemovals = 0
            private set

        override fun remove(): Boolean = onRemove()

        override fun removeWhenMainThreadIsFree() {
            deferredRemovals++
            onRemove()
        }
    }

    private class FakeOverlayHost(
        private val failAdd: Boolean = false,
        private var removeFailuresRemaining: Int = 0
    ) : OverlayHost {
        var addCount = 0
        var removeCount = 0
        var lastHandle: FakeOverlayHandle? = null
            private set

        override fun hasPermission(): Boolean = true

        override fun add(): OverlayHandle {
            addCount++
            if (failAdd) throw IllegalStateException("add failed")
            return FakeOverlayHandle {
                removeCount++
                if (removeFailuresRemaining > 0) {
                    removeFailuresRemaining--
                    throw IllegalStateException("remove failed")
                }
                true
            }.also { lastHandle = it }
        }
    }

    /** Records which thread the window API was touched from; both calls must agree. */
    private class ThreadRecordingOverlayHost : OverlayHost {
        private val threads = mutableListOf<String>()

        override fun hasPermission(): Boolean = true

        override fun add(): OverlayHandle {
            record()
            return FakeOverlayHandle {
                record()
                true
            }
        }

        fun recordedThreads(): List<String> = synchronized(threads) { threads.toList() }

        private fun record() {
            // Coroutine debug mode appends " @coroutine#N" to the thread name; drop it.
            val threadName = Thread.currentThread().name.substringBefore(" @")
            synchronized(threads) { threads += threadName }
        }
    }

    private class FakeWakeLockLease : WakeLockLease {
        val acquireTimeouts = mutableListOf<Long>()
        var releaseCount = 0

        override fun acquire(timeoutMillis: Long) {
            acquireTimeouts += timeoutMillis
        }

        override fun release() {
            releaseCount++
        }
    }

    private class FakeRenewalScheduler : RenewalScheduler {
        var initialDelayMillis = 0L
        var intervalMillis = 0L
        var scheduleCount = 0
        var cancelCount = 0
        private var action: (() -> Unit)? = null

        override fun schedule(
            initialDelayMillis: Long,
            intervalMillis: Long,
            action: () -> Unit
        ): RenewalHandle {
            this.initialDelayMillis = initialDelayMillis
            this.intervalMillis = intervalMillis
            this.action = action
            scheduleCount++
            return RenewalHandle { cancelCount++ }
        }

        fun fire() {
            action?.invoke()
        }
    }

    private class FakeVpnHost : PlaceholderVpnHost {
        var startCount = 0
        var stopCount = 0

        override fun startIfPermitted() {
            startCount++
        }

        override fun stop() {
            stopCount++
        }
    }

    private companion object {
        const val MAIN_THREAD_NAME = "fake-main"
        const val TIMEOUT_SECONDS = 2L
        const val TIMEOUT_MILLIS = 200L
    }
}
