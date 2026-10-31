package com.nezhahq.agent.mcp.transport

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.Collections

/**
 * Reserve BEFORE native launch. The ticket outlives cancellation, including a pipe close waiting
 * for a BufferedOutputStream writer lock. At most capacity processes/cleanup tasks can be retained.
 */
internal class HelperProcessCleanup(val capacity: Int = 4) {
    init { require(capacity in 1..4) }
    private val slots = Semaphore(capacity)
    private val active = Collections.newSetFromMap(ConcurrentHashMap<Ticket, Boolean>())
    private val sequence = AtomicInteger()
    private val executor = ThreadPoolExecutor(
        capacity, capacity, 30, TimeUnit.SECONDS, ArrayBlockingQueue(capacity),
        ThreadFactory { task -> Thread(task, "mcp-helper-cleanup-${sequence.incrementAndGet()}").apply { isDaemon = true } }
    ).apply { allowCoreThreadTimeOut(true) }
    val retainedProcesses: Int get() = capacity - slots.availablePermits()

    fun tryAcquire(): Ticket? {
        if (!slots.tryAcquire()) return null
        return Ticket().also { active.add(it) }
    }

    inner class Ticket internal constructor() {
        private val state = AtomicInteger(0) // reserved, scheduled, released
        @Volatile private var cleanup: (() -> Unit)? = null
        @Volatile private var finished = false
        val released: Boolean get() = finished

        fun releaseUnused() {
            if (state.compareAndSet(0, 2)) release()
        }

        fun schedule(action: () -> Unit) {
            if (!state.compareAndSet(0, 1)) return
            cleanup = action
            try {
                executor.execute {
                    try { cleanup?.invoke() }
                    finally {
                        cleanup = null
                        state.set(2)
                        release()
                    }
                }
            } catch (_: RejectedExecutionException) {
                // There are at most capacity jobs and a capacity-sized queue; normal operation
                // cannot reject. Retain the ticket/action if scheduling nevertheless fails rather
                // than admitting an unlimited number of unclosed processes.
            }
        }

        private fun release() {
            active.remove(this)
            slots.release()
            finished = true
        }
    }
}

internal object HelperProcessResources {
    val cleanup = HelperProcessCleanup()
}
