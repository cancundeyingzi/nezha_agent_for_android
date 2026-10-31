package com.nezhahq.agent.mcp

import com.nezhahq.agent.core.mcp.McpBudgetResult
import com.nezhahq.agent.core.mcp.McpLease
import com.nezhahq.agent.core.mcp.McpTaskAdmission
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Coroutine waiting belongs to the registration; core accounting remains nonblocking and pure. */
internal class McpResultReservations(private val admission: McpTaskAdmission) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var changed = CompletableDeferred<Unit>()

    suspend fun reserve(bytes: Long): AutoCloseable {
        while (true) {
            currentCoroutineContext().ensureActive()
            val attempt = synchronized(lock) {
                if (closed) throw CancellationException("MCP registration is closed")
                when (val result = admission.tryReserveResult(bytes)) {
                    is McpBudgetResult.Reserved -> Attempt.Acquired(releasing(result.lease))
                    McpBudgetResult.Busy -> Attempt.Wait(changed)
                    McpBudgetResult.InvalidSize -> error("Invalid MCP result reservation size")
                }
            }
            when (attempt) {
                is Attempt.Acquired -> return attempt.lease
                is Attempt.Wait -> attempt.generation.await()
            }
        }
    }

    private fun releasing(lease: McpLease): AutoCloseable {
        val released = AtomicBoolean()
        return AutoCloseable {
            if (released.compareAndSet(false, true)) {
                val previous = synchronized(lock) {
                    lease.close()
                    changed.also { changed = CompletableDeferred() }
                }
                // Every waiter from that generation must retry, including differently sized ones.
                previous.complete(Unit)
            }
        }
    }

    override fun close() {
        val previous = synchronized(lock) {
            if (closed) return
            closed = true
            changed
        }
        previous.complete(Unit)
    }

    private sealed interface Attempt {
        class Acquired(val lease: AutoCloseable) : Attempt
        class Wait(val generation: CompletableDeferred<Unit>) : Attempt
    }
}
