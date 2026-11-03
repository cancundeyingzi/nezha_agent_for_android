package com.nezhahq.agent.service

import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.RemoteCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal interface AgentRuntimeHandle {
    suspend fun start()
    /** Returns only after owned work and connections have stopped; failure must remain retryable. */
    suspend fun stop()
    suspend fun updateCapabilities(capabilities: RemoteCapabilities)
}

internal fun interface AgentRuntimeFactory {
    fun create(config: AgentConfig): AgentRuntimeHandle
}

/**
 * Serializes runtime replacement. The old runtime is fully stopped before a replacement exists.
 *
 * Every method runs to completion in the caller's context. Moving teardown off the main thread is
 * [RuntimeShutdownGate]'s job, so this class never owns a background scope of its own.
 */
internal class AgentRuntimeController(
    private val factory: AgentRuntimeFactory,
    private val applyPrivilegedAccess: (Boolean) -> Unit = {},
    private val finalCleanup: () -> Unit = {},
    private val onTeardownFailure: (Throwable) -> Unit = {},
    private val onFinalCleanupComplete: () -> Unit = {}
) {
    private val transitionMutex = Mutex()
    private var current: AgentRuntimeHandle? = null
    // At most one: a replacement cannot be created until this handle has stopped successfully.
    private var pendingTeardown: AgentRuntimeHandle? = null
    private var terminal = false
    private var finalCleanupComplete = false

    val isRunning: Boolean
        get() = synchronized(this) { current != null }

    /**
     * Replaces the runtime with one built from [config].
     *
     * Failed teardown retains ownership for the next retry. It is no longer a usable connection,
     * so [isRunning] stays false while cleanup prevents a replacement from being created.
     */
    suspend fun reload(config: AgentConfig) {
        transitionMutex.withLock {
            check(!terminal) { "A stopped runtime controller cannot be reused." }
            val teardownFailure = stopCurrent()
            // Cancellation wins even when the cleanup attempt also failed. Ownership is retained.
            currentCoroutineContext().ensureActive()
            teardownFailure?.let { failure ->
                runCatching { onTeardownFailure(failure) }.exceptionOrNull()
                    ?.takeIf { it !== failure }?.let(failure::addSuppressed)
                throw if (failure is CancellationException) RuntimeShutdownPendingException(failure)
                else failure
            }

            // Applied before the runtime exists: its collectors query the privileged shell as soon
            // as they start, and the release side lives in this class too so both ends are visible.
            finalCleanupComplete = false
            applyPrivilegedAccess(config.rootMode)

            val replacement = factory.create(config)
            startOrDiscard(replacement)
            synchronized(this) {
                current = replacement
            }
        }
    }

    suspend fun updateCapabilities(capabilities: RemoteCapabilities) {
        transitionMutex.withLock {
            synchronized(this) { current }?.updateCapabilities(capabilities)
        }
    }

    /**
     * Terminal teardown. Process-owned resources are released even when the runtime failed to stop.
     *
     * [RuntimeShutdownGate] retries failed teardown after the service has gone. Independent process
     * cleanup is still attempted on each failure, and only successful phases are forgotten.
     */
    suspend fun stop() {
        transitionMutex.withLock {
            terminal = true
            val failures = listOfNotNull(stopCurrent(), releaseProcessResources())
            currentCoroutineContext().ensureActive()
            val primary = failures.firstOrNull() ?: return@withLock
            failures.drop(1).filter { it !== primary }.forEach(primary::addSuppressed)
            throw primary
        }
    }

    /**
     * Moves the active runtime out of service before cleanup, retaining it until stop succeeds.
     */
    private suspend fun stopCurrent(): Throwable? {
        val old = synchronized(this) {
            current?.let {
                check(pendingTeardown == null)
                pendingTeardown = it
                current = null
            }
            pendingTeardown
        } ?: return null
        return withContext(NonCancellable) {
            runCatching {
                old.stop()
                synchronized(this@AgentRuntimeController) { pendingTeardown = null }
            }.exceptionOrNull()
        }
    }

    private suspend fun startOrDiscard(replacement: AgentRuntimeHandle) {
        try {
            replacement.start()
            currentCoroutineContext().ensureActive()
        } catch (failure: Throwable) {
            synchronized(this) { pendingTeardown = replacement }
            val cleanupFailure = stopCurrent()
            if (cleanupFailure != null && cleanupFailure !== failure) {
                failure.addSuppressed(cleanupFailure)
            }
            currentCoroutineContext().ensureActive()
            throw failure
        }
    }

    private suspend fun releaseProcessResources(): Throwable? {
        if (finalCleanupComplete) return null
        return withContext(NonCancellable) {
            val failure = runCatching {
                finalCleanup()
                finalCleanupComplete = true
            }.exceptionOrNull()
            if (failure == null) reportLifecycleEvent(onFinalCleanupComplete)
            failure
        }
    }
}
