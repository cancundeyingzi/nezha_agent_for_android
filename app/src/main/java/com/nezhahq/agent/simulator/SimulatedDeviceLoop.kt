package com.nezhahq.agent.simulator

import com.nezhahq.agent.core.model.SimulatedDeviceConfig

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SimulatedDeviceLoop(
    private val reporter: SimulatedDeviceReporter,
    private val deviceFactory: () -> SimulatedDevice = { RandomDeviceFactory.create() },
    private val intervalMs: Long = 1_000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val delayNext: suspend (Long) -> Unit = { delay(it) }
) {
    suspend fun run(
        config: SimulatedDeviceConfig,
        shouldContinue: () -> Boolean,
        onSuccess: suspend () -> Unit,
        onFailure: suspend (Throwable) -> Unit
    ) {
        val threadCount = config.threadCount
            .coerceIn(1, SimulatedDeviceConfig.MAX_THREAD_COUNT)
        val staggerMs = if (threadCount > 1) {
            (intervalMs / threadCount).coerceAtLeast(1L)
        } else {
            0L
        }

        coroutineScope {
            repeat(threadCount) { workerIndex ->
                launch {
                    if (staggerMs > 0L && workerIndex > 0) {
                        delayNext(staggerMs * workerIndex)
                    }
                    runWorker(config, shouldContinue, onSuccess, onFailure)
                }
            }
        }
    }

    private suspend fun runWorker(
        config: SimulatedDeviceConfig,
        shouldContinue: () -> Boolean,
        onSuccess: suspend () -> Unit,
        onFailure: suspend (Throwable) -> Unit
    ) {
        var pendingDevice: SimulatedDevice? = null
        while (shouldContinue()) {
            val device = pendingDevice ?: deviceFactory().also {
                pendingDevice = it
            }
            val startedAt = nowMs()
            try {
                reporter.reportOne(config, device)
                pendingDevice = null
                onSuccess()
            } catch (e: CancellationException) {
                throw e
            } catch (failure: SimulatorCleanupException) {
                // A native connection still owned by this session is a terminal failure. Retrying
                // here would create more channels while earlier workers have not exited.
                throw failure
            } catch (t: Throwable) {
                onFailure(t)
            }

            val remaining = intervalMs - (nowMs() - startedAt)
            if (shouldContinue() && remaining > 0L) {
                delayNext(remaining)
            }
        }
    }
}
