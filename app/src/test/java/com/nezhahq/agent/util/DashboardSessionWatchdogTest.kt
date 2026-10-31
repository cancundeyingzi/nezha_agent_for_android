package com.nezhahq.agent.util

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardSessionWatchdogTest {
    @Test
    fun officialTimeoutDefaultsArePreserved() {
        assertEquals(5_000L, DashboardSessionWatchdog.HANDSHAKE_TIMEOUT_MS)
        assertEquals(10_000L, DashboardSessionWatchdog.STATE_RECEIPT_TIMEOUT_MS)
        assertEquals(300_000L, DashboardSessionWatchdog.TASK_IDLE_TIMEOUT_MS)
        assertEquals(2_000L, DashboardSessionWatchdog.STATE_REPORT_INTERVAL_MS)
    }

    /**
     * The task stream must never be the tighter deadline of the two.
     *
     * The state stream is the connection's liveness detector; the task-stream deadline only exists
     * to replace a stream the Dashboard has stopped serving. If they ever crossed, an idle task
     * stream would start deciding when sessions are rebuilt, which is the failure these two values
     * were re-chosen to prevent.
     */
    @Test
    fun theIdleTaskStreamOutlivesTheStateReceiptDeadlineByAWideMargin() {
        assertTrue(
            DashboardSessionWatchdog.TASK_IDLE_TIMEOUT_MS >
                DashboardSessionWatchdog.STATE_RECEIPT_TIMEOUT_MS * 10
        )
    }

    @Test
    fun receiveWithinReturnsMessageBeforeDeadline() = runBlocking {
        val channel = Channel<String>()
        try {
            launch {
                delay(10)
                channel.send("alive")
            }

            val value = DashboardSessionWatchdog.receiveWithin(
                channel,
                timeoutMs = 1_000,
                streamName = "RequestTask stream"
            )

            assertEquals("alive", value)
        } finally {
            channel.close()
        }
    }

    @Test
    fun receiveWithinThrowsClearTimeoutWhenStreamIsIdle() {
        val channel = Channel<String>()
        try {
            val error = assertThrows(DashboardSessionTimeoutException::class.java) {
                runBlocking {
                    DashboardSessionWatchdog.receiveWithin(
                        channel,
                        timeoutMs = 1,
                        streamName = "RequestTask stream"
                    )
                }
            }

            assertTrue(error.message?.contains("RequestTask stream") == true)
        } finally {
            channel.close()
        }
    }

    @Test
    fun callWithinWrapsSlowOperationsAsDashboardTimeouts() {
        val error = assertThrows(DashboardSessionTimeoutException::class.java) {
            runBlocking {
                DashboardSessionWatchdog.callWithin<String>(
                    timeoutMs = 1,
                    operationName = "ReportSystemInfo2"
                ) {
                    delay(50)
                    "late"
                }
            }
        }

        assertTrue(error.message?.contains("ReportSystemInfo2") == true)
    }
}
