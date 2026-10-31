package com.nezhahq.agent.mcp

import com.nezhahq.agent.core.mcp.McpLimits
import com.nezhahq.agent.core.mcp.McpTaskAdmission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class McpResultReservationsTest {
    @Test
    fun releasingOneGenerationWakesEveryWaiterAndLeasesReleaseExactlyOnce() = runBlocking {
        val admission = McpTaskAdmission()
        val reservations = McpResultReservations(admission)
        val first = reservations.reserve(McpLimits.MAX_TASK_RESULT_BYTES.toLong())
        val second = reservations.reserve(McpLimits.MAX_TASK_RESULT_BYTES.toLong())
        val acquired = Channel<AutoCloseable>(Channel.UNLIMITED)
        val waiters = List(3) {
            launch(start = CoroutineStart.UNDISPATCHED) { acquired.send(reservations.reserve(1024)) }
        }
        assertTrue(acquired.tryReceive().isFailure)
        assertEquals(McpLimits.RESULT_BUDGET_BYTES, admission.retainedResultBytes)
        first.close()
        first.close()
        val leases = withTimeout(5_000) { List(3) { acquired.receive() } }
        waiters.joinAll()
        assertEquals(McpLimits.MAX_TASK_RESULT_BYTES + 3 * 1024L, admission.retainedResultBytes)
        leases.forEach { it.close(); it.close() }
        second.close()
        reservations.close()
        assertEquals(0L, admission.retainedResultBytes)
    }

    @Test
    fun closingWakesAllWaitersAndPreventsAnyNewReservation() = runBlocking {
        val admission = McpTaskAdmission()
        val reservations = McpResultReservations(admission)
        val held = List(2) { reservations.reserve(McpLimits.MAX_TASK_RESULT_BYTES.toLong()) }
        val cancelled = Channel<Unit>(Channel.UNLIMITED)
        val waiters = List(4) {
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    reservations.reserve(1024)
                    fail("Closed registration returned a result lease")
                } catch (_: CancellationException) { cancelled.send(Unit) }
            }
        }
        reservations.close()
        reservations.close()
        withTimeout(5_000) { repeat(4) { cancelled.receive() }; waiters.joinAll() }
        try {
            reservations.reserve(1)
            fail("Closed reservation owner accepted a lease")
        } catch (_: CancellationException) { }
        held.forEach(AutoCloseable::close)
        assertEquals(0L, admission.retainedResultBytes)
    }
}
