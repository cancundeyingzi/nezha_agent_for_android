package com.nezhahq.agent.device

import androidx.activity.result.ActivityResultRegistry
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tests the controlled AndroidX host on API 23 and 35; never opens an OS permission dialog. */
@RunWith(AndroidJUnit4::class)
class NotificationPermissionHostRegressionTest {
    @get:Rule(order = 0) val compose = createAndroidComposeRule<NotificationPermissionTestActivity>()
    @get:Rule(order = 1) val evidence = DeviceEvidenceRule()

    @Test fun recreationRestoresTheActualLaunchedRequestAndRoutesItsResultOnce() {
        val beforeActivity = AtomicReference<NotificationPermissionTestActivity>()
        val beforeRegistry = AtomicReference<ActivityResultRegistry>()
        compose.activityRule.scenario.onActivity {
            beforeActivity.set(it)
            beforeRegistry.set(it.registryInstanceForTest())
            it.setPendingRequest(41L)
        }
        compose.waitForIdle()
        var requestCode = 0
        compose.activityRule.scenario.onActivity {
            assertEquals(1, it.launchCount())
            requestCode = it.launchedRequestCode(0)
            assertTrue(it.deliveredRequestIds().isEmpty())
        }

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity {
            assertNotSame(beforeActivity.get(), it)
            assertNotSame(beforeRegistry.get(), it.registryInstanceForTest())
            assertEquals("restored in-flight request must not launch again", 1, it.launchCount())
            assertTrue(it.deliveredRequestIds().isEmpty())
            assertTrue("new registry must recognize the pre-recreation request code", it.dispatchPermissionResult(requestCode, true))
        }
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity {
            assertArrayEquals(longArrayOf(41L), it.deliveredRequestIds())
            assertArrayEquals(booleanArrayOf(true), it.deliveredGrants())
            assertEquals(1, it.launchCount())
            // The boolean reports whether this code still has a registry route. After consumption
            // or re-registration, false is a valid rejection; the contract is no second delivery.
            it.dispatchPermissionResult(requestCode, true)
        }
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity {
            assertArrayEquals("duplicate dispatch cannot deliver the consumed request twice", longArrayOf(41L), it.deliveredRequestIds())
        }
    }

    @Test fun stoppedRequestAfterRecreationKeepsItsOldIdBeforeTheSuccessorLaunches() {
        compose.activityRule.scenario.onActivity { it.setPendingRequest(101L) }
        compose.waitForIdle()
        var oldRequestCode = 0
        compose.activityRule.scenario.onActivity {
            assertEquals(1, it.launchCount())
            oldRequestCode = it.launchedRequestCode(0)
            it.setPendingRequest(null) // Stop invalidates the intent, but the OS request is in flight.
        }
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { it.setPendingRequest(202L) }
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { assertEquals(1, it.launchCount()) }

        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity {
            assertEquals("a successor must wait for the actual old request to return", 1, it.launchCount())
            assertTrue(it.dispatchPermissionResult(oldRequestCode, false))
        }
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity {
            assertArrayEquals("old result must not borrow the new pending ID", longArrayOf(101L), it.deliveredRequestIds())
            assertArrayEquals(booleanArrayOf(false), it.deliveredGrants())
            assertEquals(2, it.launchCount())
            assertTrue(it.dispatchPermissionResult(it.launchedRequestCode(1), true))
        }
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity {
            assertArrayEquals(longArrayOf(101L, 202L), it.deliveredRequestIds())
            assertArrayEquals(booleanArrayOf(false, true), it.deliveredGrants())
            assertEquals(2, it.launchCount())
        }
    }
}
