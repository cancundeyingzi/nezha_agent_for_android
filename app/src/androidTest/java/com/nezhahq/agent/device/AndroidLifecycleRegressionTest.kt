package com.nezhahq.agent.device

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nezhahq.agent.MainActivity
import com.nezhahq.agent.MainViewModel
import com.nezhahq.agent.NekogramLiquidGlassBarView
import com.nezhahq.agent.grpc.GrpcConnectionState
import com.nezhahq.agent.service.AgentService
import com.nezhahq.agent.service.AgentServiceRunningState
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidLifecycleRegressionTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<MainActivity>()
    // Capture failures while the Compose rule still owns the Activity and its real window.
    @get:Rule(order = 1) val evidence = DeviceEvidenceRule()
    private val application get() = deviceApplication
    private val fixture get() = application.runtimeFixture

    @Before fun ready() {
        stopAndAwaitResources()
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                application.packageName, Manifest.permission.POST_NOTIFICATIONS
            )
            assertEquals(PackageManager.PERMISSION_GRANTED, application.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        }
        compose.waitUntil(15_000) { !viewModel().isConfigLoading }
    }

    @After fun stopService() = stopAndAwaitResources()

    @Test fun serviceStopAndSameProcessRecreationReleaseRealChannelsAndNetworkCallbacks() {
        val pid = Process.myPid()
        val starts = fixture.starts.get()
        val callbacks = fixture.networkCallbacks.get()
        val fetches = fixture.geoIpFetches.get()
        val accepted = fixture.acceptedConnections.get()
        val eventOffset = fixture.events.size

        AgentService.startOrReload(application)
        awaitCondition("first runtime and loopback connection") {
            fixture.starts.get() == starts + 1 && fixture.acceptedConnections.get() > accepted
        }
        awaitCondition("real Android network callback and isolated GeoIP fetch") {
            fixture.networkCallbacks.get() > callbacks && fixture.geoIpFetches.get() > fetches
        }
        assertTrue(AgentServiceRunningState.canRequestReload())
        awaitCondition("foreground notification") { foregroundNotificationPresent() }

        assertTrue(application.stopService(Intent(application, AgentService::class.java)))
        awaitCondition("old Android Service destroyed") { !AgentServiceRunningState.canRequestReload() }
        // Recreate as soon as Android releases the Service, while its asynchronous runtime teardown
        // may still own the channel. The process-wide shutdown gate must order the two instances.
        AgentService.startOrReload(application)
        awaitCondition("replacement runtime") { fixture.starts.get() == starts + 2 }
        assertEquals(pid, Process.myPid())
        assertEquals(1, fixture.liveRuntimes.get())
        val events = synchronized(fixture.events) { fixture.events.drop(eventOffset) }
        assertEquals(3, events.size)
        assertTrue(events[0].startsWith("start:"))
        assertEquals(events[0].replace("start:", "stop:"), events[1])
        assertTrue(events[2].startsWith("start:"))
        assertNotEquals(events[0], events[2])

        stopAndAwaitResources()
        assertEquals(fixture.registrations.get(), fixture.unregistrations.get())
        assertEquals(fixture.acceptedConnections.get(), fixture.closedConnections.get())
        assertEquals(GrpcConnectionState.IDLE, application.container.connectionState.connectionState.value)
        awaitCondition("foreground notification removed") { !foregroundNotificationPresent() }
    }

    @Test fun finishingBackgroundActivityLeavesTheForegroundServiceRunningUntilExplicitStop() {
        val starts = fixture.starts.get()
        AgentService.startOrReload(application)
        awaitCondition("runtime started") { fixture.starts.get() == starts + 1 }
        val stopCount = fixture.stops.get()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.close()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        assertTrue(AgentServiceRunningState.canRequestReload())
        assertEquals(1, fixture.liveRuntimes.get())
        assertEquals(stopCount, fixture.stops.get())
        assertTrue(AgentService.requestCapabilityRefreshIfRunning(application))
        assertTrue(foregroundNotificationPresent())
        stopAndAwaitResources()
        assertEquals(stopCount + 1, fixture.stops.get())
    }

    @Test fun rotationAndActivityRecreationKeepDraftAndSelectedTab() {
        if (Build.VERSION.SDK_INT >= 33) assertGlassBecomesIdleAndRefreshesAfterScroll()
        compose.onNode(hasText("服务端 IP 或域名") and hasSetTextAction())
            .performScrollTo().performTextReplacement("rotation-fixture.invalid")
        compose.onNode(hasText("客户端密钥 (Secret)") and hasSetTextAction())
            .performScrollTo().performTextReplacement("rotation-synthetic-secret")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithContentDescription("工具").performClick().assertIsSelected()
        val before = AtomicReference<MainActivity>()
        val retainedViewModel = viewModel()
        compose.activityRule.scenario.onActivity {
            before.set(it)
            it.requestedOrientation = if (it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        awaitCondition("rotation recreated Activity") {
            var replaced = false
            compose.activityRule.scenario.onActivity { replaced = it !== before.get() }
            replaced
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("工具").assertIsSelected()
        assertSame(retainedViewModel, viewModel())
        assertEquals("rotation-fixture.invalid", viewModel().server)
        assertEquals("rotation-synthetic-secret", viewModel().secret)

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("工具").assertIsSelected()
        assertEquals("rotation-fixture.invalid", viewModel().server)
        assertEquals("rotation-synthetic-secret", viewModel().secret)
    }

    @RequiresApi(33)
    private fun assertGlassBecomesIdleAndRefreshesAfterScroll() {
        compose.waitForIdle()
        val scroll = compose.onNode(
            hasScrollAction() and hasAnyDescendant(hasText("允许面板内网穿透")),
            useUnmergedTree = true
        )
        val axis = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val frames = AtomicInteger()
        val glassDirtyFrames = AtomicInteger()
        lateinit var observer: ViewTreeObserver
        lateinit var listener: ViewTreeObserver.OnPreDrawListener
        compose.activityRule.scenario.onActivity { activity ->
            val glass = checkNotNull(findGlass(activity.window.decorView))
            observer = activity.window.decorView.viewTreeObserver
            listener = ViewTreeObserver.OnPreDrawListener {
                frames.incrementAndGet()
                if (glass.isDirty) glassDirtyFrames.incrementAndGet()
                true
            }
            observer.addOnPreDrawListener(listener)
        }
        fun scrollBy(delta: Float) {
            scroll.performSemanticsAction(SemanticsActions.ScrollBy) { action ->
                assertTrue("Configuration ScrollBy action rejected $delta", action(0f, if (axis.reverseScrolling) -delta else delta))
            }
        }
        fun observeScroll(label: String, requireMovement: Boolean = true, action: () -> Unit): Boolean {
            awaitStaticFrames(frames)
            val (before, maximum) = compose.runOnIdle { axis.value() to axis.maxValue() }
            val framesBefore = frames.get()
            val dirtyBefore = glassDirtyFrames.get()
            action()
            compose.waitForIdle()
            val after = compose.runOnIdle { axis.value() }
            val diagnostic = "$label offset=$before->$after max=$maximum " +
                "frames=$framesBefore->${frames.get()} glassDirty=$dirtyBefore->${glassDirtyFrames.get()}"
            Log.i("DeviceRegression", "Glass scroll observation: $diagnostic")
            val moved = kotlin.math.abs(after - before) >= 1f
            if (requireMovement) assertTrue("Real configuration content did not scroll: $diagnostic", moved)
            if (moved) {
                assertTrue("Source scrolling did not draw a window frame: $diagnostic", frames.get() > framesBefore)
                awaitCondition("real glass view invalidated by source scrolling ($diagnostic)") {
                    glassDirtyFrames.get() > dirtyBefore
                }
            }
            awaitStaticFrames(frames)
            return moved
        }
        try {
            // Exercise the original action before any forced movement, so this also diagnoses a
            // restored viewport in which performScrollTo legitimately has nothing to scroll.
            val target = compose.onNodeWithText("允许面板内网穿透")
            if (!observeScroll("performScrollTo initial", requireMovement = false) { target.performScrollTo() }) {
                val beforeReset = compose.runOnIdle { axis.value() }
                if (beforeReset >= 1f) observeScroll("ScrollBy reset to origin") { scrollBy(-beforeReset) }
                val origin = compose.runOnIdle { axis.value() }
                assertTrue("Configuration scroll origin was not restored: $origin", origin < 1f)
                observeScroll("performScrollTo from verified origin") { target.performScrollTo() }
            }
            val (offsetBefore, maximum) = compose.runOnIdle { axis.value() to axis.maxValue() }
            assertTrue("Configuration content must have a scrollable range: $maximum", maximum >= 2f)
            // Keep the independent small movement probe after the original target-based action.
            val distance = minOf(240f, maximum / 2f)
            val delta = if (offsetBefore <= maximum / 2f) distance else -distance
            observeScroll("ScrollBy delta=$delta") { scrollBy(delta) }
        } finally {
            compose.activityRule.scenario.onActivity { if (observer.isAlive) observer.removeOnPreDrawListener(listener) }
        }
    }

    private fun awaitStaticFrames(frames: AtomicInteger) {
        var observed = frames.get()
        var stableSince = SystemClock.elapsedRealtime()
        awaitCondition("static window stops its redraw feedback loop", 5_000) {
            val now = SystemClock.elapsedRealtime()
            val next = frames.get()
            if (next != observed) { observed = next; stableSince = now }
            now - stableSince >= 250
        }
    }

    @RequiresApi(33)
    private fun findGlass(view: View): View? {
        if (view is NekogramLiquidGlassBarView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findGlass(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun viewModel(): MainViewModel {
        lateinit var model: MainViewModel
        compose.activityRule.scenario.onActivity {
            model = ViewModelProvider(it, MainViewModel.Factory)[MainViewModel::class.java]
        }
        return model
    }

    private fun foregroundNotificationPresent(): Boolean =
        application.getSystemService(NotificationManager::class.java).activeNotifications.any {
            it.id == 1001 && it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0
        }

    private fun stopAndAwaitResources() {
        application.stopService(Intent(application, AgentService::class.java))
        awaitCondition("Service and native runtime resources released") {
            !AgentServiceRunningState.canRequestReload() && fixture.liveRuntimes.get() == 0 &&
                fixture.registrations.get() == fixture.unregistrations.get() &&
                fixture.acceptedConnections.get() == fixture.closedConnections.get()
        }
    }
}
