package com.nezhahq.agent.core.mcp

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class McpLimitsTest {
    @Test
    fun byteBudgetRejectsInvalidInputAndSaturationWithoutOverflow() {
        val budget = McpByteBudget(Long.MAX_VALUE)
        assertSame(McpBudgetResult.InvalidSize, budget.tryReserve(-1))
        val lease = (budget.tryReserve(Long.MAX_VALUE) as McpBudgetResult.Reserved).lease
        assertEquals(Long.MAX_VALUE, budget.usedBytes)
        assertSame(McpBudgetResult.Busy, budget.tryReserve(1))
        lease.close()
        lease.close()
        assertTrue(lease.isClosed)
        assertEquals(0L, budget.usedBytes)
        (budget.tryReserve(0) as McpBudgetResult.Reserved).lease.close()
        (budget.tryReserve(1) as McpBudgetResult.Reserved).lease.close()
        assertEquals(0L, budget.usedBytes)
    }

    @Test
    fun fourMibRequestAndSixMibResultBudgetsHaveIndependentLeases() {
        val admission = McpTaskAdmission()
        val tasks = listOf(McpTaskFamily.EXEC, McpTaskFamily.EXEC, McpTaskFamily.FS_LIST, McpTaskFamily.FS_READ)
            .map { (admission.tryAcquire(it, McpLimits.MAX_REQUEST_BYTES) as McpAdmissionResult.Admitted).lease }
        assertEquals(4L * 1024 * 1024, admission.retainedRequestBytes)
        assertEquals(2, admission.activeExecTasks)
        assertEquals(2, admission.activeFileTasks)
        val outputs = (0..1).map {
            (admission.tryReserveResult(McpLimits.MAX_TASK_RESULT_BYTES.toLong()) as McpBudgetResult.Reserved).lease
        }
        assertEquals(6L * 1024 * 1024, admission.retainedResultBytes)
        assertSame(McpBudgetResult.Busy, admission.tryReserveResult(1))
        assertSame(McpBudgetResult.InvalidSize, admission.tryReserveResult(-1))
        assertSame(McpBudgetResult.InvalidSize, admission.tryReserveResult(McpLimits.MAX_TASK_RESULT_BYTES + 1L))
        tasks.forEach { it.close(); it.close() }
        assertEquals(0L, admission.retainedRequestBytes)
        assertEquals(6L * 1024 * 1024, admission.retainedResultBytes)
        outputs.forEach { it.close(); it.close() }
        assertEquals(0L, admission.retainedResultBytes)
        assertEquals(0, admission.activeExecTasks)
        assertEquals(0, admission.activeFileTasks)
    }

    @Test
    fun familyAdmissionIsNonblockingAndSharesBothFileOperations() {
        val admission = McpTaskAdmission()
        assertSame(McpAdmissionResult.InvalidRequestSize, admission.tryAcquire(McpTaskFamily.EXEC, -1))
        assertSame(McpAdmissionResult.InvalidRequestSize, admission.tryAcquire(McpTaskFamily.EXEC, 1_048_577))
        val exec = (0..1).map { (admission.tryAcquire(McpTaskFamily.EXEC, 10) as McpAdmissionResult.Admitted).lease }
        val files = listOf(McpTaskFamily.FS_LIST, McpTaskFamily.FS_READ).map {
            (admission.tryAcquire(it, 10) as McpAdmissionResult.Admitted).lease
        }
        listOf(McpTaskFamily.EXEC, McpTaskFamily.FS_LIST, McpTaskFamily.FS_READ).forEach {
            assertEquals(McpAdmissionResult.Busy(McpBusyReason.FAMILY_LIMIT), admission.tryAcquire(it, 0))
        }
        files.first().close()
        val replacement = admission.tryAcquire(McpTaskFamily.FS_READ, 10) as McpAdmissionResult.Admitted
        replacement.lease.close()
        (exec + files).forEach { it.close() }
        assertEquals(0L, admission.retainedRequestBytes)
    }

    @Test
    fun concurrentAcquireAndRepeatedCloseCannotOverAdmitOrUnderflow() {
        val admission = McpTaskAdmission()
        val pool = Executors.newFixedThreadPool(12)
        val start = CountDownLatch(1)
        val attempted = CountDownLatch(12)
        val release = CountDownLatch(1)
        val acquired = AtomicInteger()
        try {
            val futures = (0 until 12).map {
                pool.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    val decision = admission.tryAcquire(McpTaskFamily.EXEC, 100)
                    if (decision is McpAdmissionResult.Admitted) acquired.incrementAndGet()
                    attempted.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    if (decision is McpAdmissionResult.Admitted) repeat(10) { decision.lease.close() }
                }
            }
            start.countDown()
            assertTrue(attempted.await(5, TimeUnit.SECONDS))
            assertEquals(2, acquired.get())
            assertEquals(2, admission.activeExecTasks)
            assertEquals(200L, admission.retainedRequestBytes)
            release.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(0, admission.activeExecTasks)
            assertEquals(0L, admission.retainedRequestBytes)
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun concurrentCleanupOfTheSameLeaseReturnsItsBytesExactlyOnce() {
        val budget = McpByteBudget(100)
        val lease = (budget.tryReserve(100) as McpBudgetResult.Reserved).lease
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 8).map {
                pool.submit {
                    check(start.await(5, TimeUnit.SECONDS))
                    repeat(10) { lease.close() }
                }
            }
            start.countDown()
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
            assertTrue(lease.isClosed)
            assertEquals(0L, budget.usedBytes)
            val replacement = budget.tryReserve(100) as McpBudgetResult.Reserved
            assertSame(McpBudgetResult.Busy, budget.tryReserve(1))
            replacement.lease.close()
            assertEquals(0L, budget.usedBytes)
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }
}
