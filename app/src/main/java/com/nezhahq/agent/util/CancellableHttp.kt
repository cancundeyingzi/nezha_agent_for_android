package com.nezhahq.agent.util

import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/** Also bounds time spent waiting in OkHttp's dispatcher or in a platform DNS lookup. */
internal suspend fun <T> Call.awaitResultWithin(timeoutMillis: Long, read: (Response) -> T): T {
    val completed = withTimeoutOrNull(timeoutMillis) { HttpResult(awaitResult(read)) }
        ?: throw SocketTimeoutException("HTTP probe total deadline exceeded")
    return completed.value
}

private data class HttpResult<T>(val value: T)

/** Consumes and closes the response before resuming, including a late response after cancellation. */
internal suspend fun <T> Call.awaitResult(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) {
                response.close()
                return
            }
            continuation.resumeWith(runCatching { response.use(read) })
        }
    })
}
