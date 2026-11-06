package com.nezhahq.agent.service

import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ServiceNotificationSessionTest {
    @Test fun aQueuedOldUpdateIsDroppedAndUpdatesAfterCloseAreNotQueued() {
        val main = MainQueue()
        val events = mutableListOf<String>()
        val session = ServiceNotificationSession(main::post,
            publish = { events += "notify:$it" }, stopForeground = { events += "stopForeground" },
            cancelNotification = { events += "cancel" },
            onFailure = { _, failure -> throw AssertionError(failure) })
        session.update("already queued")
        assertEquals(1, main.size)
        session.close()
        session.update("arrived after destroy")
        assertEquals(1, main.size)
        main.drain()
        session.close()
        assertEquals(listOf("stopForeground", "cancel"), events)
    }

    @Test fun oldCallbacksAndRepeatedOldCloseCannotOverwriteOrRemoveTheSuccessorNotification() {
        val main = MainQueue()
        var visible: String? = null
        var oldRemovals = 0
        val old = ServiceNotificationSession(main::post,
            publish = { visible = "old:$it" }, stopForeground = {},
            cancelNotification = { oldRemovals++; visible = null },
            onFailure = { _, failure -> throw AssertionError(failure) })
        old.update("delayed")
        old.close()
        val successor = ServiceNotificationSession(main::post,
            publish = { visible = "new:$it" }, stopForeground = {}, cancelNotification = { visible = null },
            onFailure = { _, failure -> throw AssertionError(failure) })
        successor.update("running")
        main.drain()
        assertEquals("new:running", visible)

        old.update("late runtime status")
        old.close()
        main.drain()
        assertEquals(1, oldRemovals)
        assertEquals("new:running", visible)
        successor.close()
        assertNull(visible)
    }

    @Test fun closingWaitsForAnAdmittedPublishAndRemovesAfterItCompletes() {
        val workers = Executors.newFixedThreadPool(2)
        val publishing = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val closeAttempted = CountDownLatch(1)
        val removed = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val session = ServiceNotificationSession(postToMain = { it() },
            publish = {
                events += "publish-start"
                publishing.countDown()
                check(releasePublish.await(5, TimeUnit.SECONDS))
                events += "publish-finished"
            },
            stopForeground = { events += "stopForeground" },
            cancelNotification = { events += "cancel"; removed.countDown() },
            onFailure = { _, failure -> failures += failure })
        try {
            val update = workers.submit { session.update("admitted") }
            assertTrue(publishing.await(5, TimeUnit.SECONDS))
            val close = workers.submit { closeAttempted.countDown(); session.close() }
            assertTrue(closeAttempted.await(5, TimeUnit.SECONDS))
            assertFalse("removal cannot race an in-flight notify", removed.await(100, TimeUnit.MILLISECONDS))
            releasePublish.countDown()
            update.get(5, TimeUnit.SECONDS)
            close.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("publish-start", "publish-finished", "stopForeground", "cancel"), events)
            assertTrue(failures.isEmpty())
        } finally {
            releasePublish.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun publicationRemovalAndReportingFailuresDoNotEscapeOrReopenTheSession() {
        val main = MainQueue()
        val failures = mutableListOf<String>()
        var foregroundStops = 0
        var cancellations = 0
        val session = ServiceNotificationSession(main::post,
            publish = { error("synthetic notification failure") },
            stopForeground = { foregroundStops++; error("synthetic foreground removal failure") },
            cancelNotification = { cancellations++; error("synthetic notification cancellation failure") },
            onFailure = { operation, _ -> failures += operation; error("synthetic logger failure") })
        session.update("fails")
        main.drain()
        session.close()
        session.update("must stay closed")
        session.close()
        main.drain()
        assertEquals(listOf("update", "stopForeground", "cancel"), failures)
        assertEquals(1, foregroundStops)
        assertEquals(1, cancellations)
        assertEquals(0, main.size)
    }

    @Test fun aRejectedPostIsReportedWithoutPreventingLaterPublicationOrClose() {
        val main = MainQueue()
        val failures = mutableListOf<String>()
        val events = mutableListOf<String>()
        var posts = 0
        val session = ServiceNotificationSession(
            postToMain = { if (++posts == 1) error("synthetic dispatch failure") else main.post(it) },
            publish = { events += it }, stopForeground = {}, cancelNotification = { events += "remove" },
            onFailure = { operation, _ -> failures += operation })
        session.update("rejected")
        session.update("accepted")
        main.drain()
        session.close()
        assertEquals(listOf("queue"), failures)
        assertEquals(listOf("accepted", "remove"), events)
    }

    private class MainQueue {
        private val actions = ArrayDeque<() -> Unit>()
        val size: Int get() = actions.size
        fun post(action: () -> Unit) { actions.addLast(action) }
        fun drain() { while (actions.isNotEmpty()) actions.removeFirst().invoke() }
    }
}
