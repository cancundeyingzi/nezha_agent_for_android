package com.nezhahq.agent.service

/** One Service instance's notification admission; close is synchronous on the lifecycle thread. */
internal class ServiceNotificationSession(
    private val postToMain: (() -> Unit) -> Unit,
    private val publish: (String) -> Unit,
    private val stopForeground: () -> Unit,
    private val cancelNotification: () -> Unit,
    private val onFailure: (String, Throwable) -> Unit
) {
    private val lock = Any()
    private var closed = false

    fun update(text: String) = synchronized(lock) {
        if (closed) return@synchronized
        attempt("queue") {
            postToMain {
                synchronized(lock) {
                    // A status admitted before onDestroy may still be waiting in the main queue.
                    if (!closed) attempt("update") { publish(text) }
                }
            }
        }
    }

    fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        // Never defer removal to runtime teardown: its successor can already own the shared ID.
        attempt("stopForeground") { stopForeground() }
        // Android may already have detached this Service's foreground record before onDestroy.
        // Also remove an ordinary residue, even when stopForeground itself failed.
        attempt("cancel") { cancelNotification() }
    }

    private inline fun attempt(operation: String, action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            // Notification and logging failures cannot prevent the caller's runtime cleanup.
            reportLifecycleEvent { onFailure(operation, failure) }
        }
    }
}
