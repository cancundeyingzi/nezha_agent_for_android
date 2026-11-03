package com.nezhahq.agent.service

/**
 * Reports an outcome without changing it. Required lifecycle actions must stay outside this block;
 * coroutine owners check their own cancellation before continuing after a report.
 */
internal inline fun reportLifecycleEvent(report: () -> Unit) {
    runCatching(report)
}
