package com.nezhahq.agent.service

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Completion-tracked ordered teardown for one runtime.
 *
 * Successful phases are not repeated. Failed phases remain retryable, and every phase is attempted
 * before failures are propagated.
 */
internal class RuntimeShutdownCoordinator(
    private val cancelAndJoinWork: suspend () -> Unit,
    private val closeNetwork: () -> Unit,
    private val closeKeepAlive: suspend () -> Unit,
    private val closeGrpc: () -> Unit
) {
    private val closeMutex = Mutex()
    private var workClosed = false
    private var networkClosed = false
    private var keepAliveClosed = false
    private var grpcClosed = false

    suspend fun close() = withContext(NonCancellable) {
        closeMutex.withLock {
            val failures = mutableListOf<Throwable>()
            if (!workClosed) {
                attempt(failures, onSuccess = { workClosed = true }, cancelAndJoinWork)
            }
            if (!networkClosed) {
                attempt(failures, onSuccess = { networkClosed = true }) { closeNetwork() }
            }
            if (!keepAliveClosed) {
                attempt(failures, onSuccess = { keepAliveClosed = true }, closeKeepAlive)
            }
            if (!grpcClosed) {
                attempt(failures, onSuccess = { grpcClosed = true }) { closeGrpc() }
            }
            failures.throwCombinedIfAny()
        }
    }

    private suspend fun attempt(
        failures: MutableList<Throwable>,
        onSuccess: () -> Unit,
        action: suspend () -> Unit
    ) {
        try {
            action()
            onSuccess()
        } catch (failure: Throwable) {
            failures += failure
        }
    }

    private fun List<Throwable>.throwCombinedIfAny() {
        val primary = firstOrNull() ?: return
        drop(1).filter { it !== primary }.forEach(primary::addSuppressed)
        throw primary
    }
}
