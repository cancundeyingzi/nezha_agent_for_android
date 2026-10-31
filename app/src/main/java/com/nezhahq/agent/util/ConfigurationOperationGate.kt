package com.nezhahq.agent.util

/** Serializes UI configuration changes, including asynchronous clipboard parsing, on the UI thread. */
internal class ConfigurationOperationGate(private val onBusyChanged: (Boolean) -> Unit) {
    var isBusy: Boolean = false
        private set

    fun tryBegin(): Lease? {
        if (isBusy) return null
        isBusy = true
        onBusyChanged(true)
        return Lease {
            isBusy = false
            onBusyChanged(false)
        }
    }

    class Lease internal constructor(private val release: () -> Unit) : AutoCloseable {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            release()
        }
    }
}
