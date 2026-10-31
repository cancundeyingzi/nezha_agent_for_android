package com.nezhahq.agent.util

import com.nezhahq.agent.core.config.ConfigurationSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** First UI read: suspend for initialization, read only on IO, and return an explicit failure state. */
internal suspend fun awaitInitialConfiguration(
    readiness: ConfigurationReadiness,
    readSnapshot: () -> ConfigurationSnapshot,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    warningTimeoutMillis: Long = 15_000L,
    onSlowInitialization: () -> Unit = {}
): ConfigurationSnapshot {
    readiness.awaitWithin(warningTimeoutMillis) ?: run {
        onSlowInitialization()
        readiness.await()
    }
    // The one-shot outcome may predate a successful reset. Readiness only releases the initial
    // migration barrier; the current snapshot is the authority for whether storage is usable.
    return withContext(ioDispatcher) {
        try {
            readSnapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Logger.e("Configuration: 首次读取失败 (Error=${failure.javaClass.simpleName})")
            ConfigurationSnapshot.DEFAULTS
        }
    }
}
