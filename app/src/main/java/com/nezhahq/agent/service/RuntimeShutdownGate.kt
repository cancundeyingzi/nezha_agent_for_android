package com.nezhahq.agent.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Process-level gate that runs service shutdowns in the background, one at a time.
 *
 * `Service.onDestroy` runs on the main thread and must not wait for a shutdown: cancelling a
 * runtime joins stream sessions whose blocking socket reads do not observe coroutine cancellation.
 * Shutdown also releases process-owned resources such as the privileged shell, so the next runtime
 * may only start once the previous shutdown has finished — that is what [awaitIdle] guarantees.
 */
internal class RuntimeShutdownGate(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val delayBeforeRetry: suspend (Long) -> Unit = { delay(it) },
    private val onFailure: (Throwable) -> Unit
) {
    private val lock = Any()
    private val pending = ArrayDeque<Shutdown>()
    private var worker: Job? = null

    /**
     * Queues [shutdown] after any shutdown that has not finished yet.
     *
     * A failing shutdown retains its closure and retries with backoff. The returned job observes
     * completion; cancelling that observation does not cancel the process-owned cleanup worker.
     */
    fun submit(shutdown: suspend () -> Unit): Job = synchronized(lock) {
        val entry = Shutdown(shutdown)
        pending.addLast(entry)
        if (worker?.isActive != true) {
            worker = scope.launch(start = CoroutineStart.LAZY) { drain() }
            worker?.start()
        }
        entry.completion
    }

    private suspend fun drain() {
        while (true) {
            val entry = synchronized(lock) {
                pending.firstOrNull().also { if (it == null) worker = null }
            } ?: return
            val retryPolicy = StartRetryPolicy()
            try {
                while (true) {
                    try {
                        entry.action()
                        break
                    } catch (failure: Throwable) {
                        currentCoroutineContext().ensureActive()
                        entry.outcome.value = Result.failure(RuntimeShutdownPendingException(failure))
                        runCatching { onFailure(failure) }.exceptionOrNull()
                            ?.takeIf { it !== failure }?.let(failure::addSuppressed)
                        delayBeforeRetry(retryPolicy.nextDelayMillis())
                    }
                }
                synchronized(lock) { pending.remove(entry) }
                entry.outcome.value = Result.success(Unit)
                entry.completion.complete(Unit)
            } catch (cancellation: CancellationException) {
                // Cancellation is not proof of cleanup. Keep ownership and the gate closed.
                entry.outcome.value = Result.failure(RuntimeShutdownPendingException(cancellation))
                throw cancellation
            }
        }
    }

    /**
     * Waits for success, or reports a pending failure so the new service can show its retry status.
     * Cancelling this wait does not cancel any shutdown. The oldest unfinished entry is observed so
     * repeated service destruction cannot hide its failure behind a later queued shutdown.
     */
    suspend fun awaitIdle() {
        while (true) {
            val entry = synchronized(lock) { pending.firstOrNull() } ?: return
            entry.outcome.first { it != null }!!.getOrThrow()
        }
    }

    private class Shutdown(val action: suspend () -> Unit) {
        val completion = CompletableDeferred<Unit>()
        val outcome = MutableStateFlow<Result<Unit>?>(null)
    }
}

internal class RuntimeShutdownPendingException(cause: Throwable) :
    IllegalStateException("Previous runtime shutdown is incomplete; retrying cleanup.", cause)
