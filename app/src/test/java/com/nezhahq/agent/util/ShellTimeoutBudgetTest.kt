package com.nezhahq.agent.util

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the timing relationship that keeps a slow privileged command from killing the connection.
 *
 * The metrics loop reads `/proc` through the shared shell every
 * [DashboardSessionWatchdog.STATE_REPORT_INTERVAL_MS], so a command holding that shell delays the
 * next state report by however long it runs. Once the gap between receipts reaches
 * [DashboardSessionWatchdog.STATE_RECEIPT_TIMEOUT_MS] the agent tears the session down — including
 * any file transfer or terminal in flight. A 120-second upload copy on the shared shell made that
 * a certainty, which is what `RootShell.executeIsolated` now exists to prevent.
 *
 * These two constants live in modules that cannot see each other, so nothing but this test stops
 * one from drifting past the other.
 */
class ShellTimeoutBudgetTest {

    @Test
    fun oneSharedShellCommandCannotOutlastTheStateReceiptTimeout() {
        val headroom = DashboardSessionWatchdog.STATE_RECEIPT_TIMEOUT_MS -
            DashboardSessionWatchdog.STATE_REPORT_INTERVAL_MS

        assertTrue(
            "A command may hold the shared shell for ${DEFAULT_SHELL_TIMEOUT_MS}ms, but the " +
                "metrics loop only has ${headroom}ms of slack before the dashboard stops " +
                "receiving state reports. Either lower DEFAULT_SHELL_TIMEOUT_MS or move the " +
                "caller that needs longer to RootShell.executeIsolated.",
            DEFAULT_SHELL_TIMEOUT_MS < headroom
        )
    }
}
