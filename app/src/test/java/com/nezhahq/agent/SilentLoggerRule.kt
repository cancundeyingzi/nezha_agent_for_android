package com.nezhahq.agent

import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.PlatformLogSink
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Points [Logger]'s platform write at a recorder instead of `android.util.Log` for one test.
 *
 * Android logging is unavailable in JVM tests. Logger now isolates platform failures; this recorder
 * keeps the actual safe platform output observable instead of silently swallowing a mocked-API
 * error. The sink receives strings only, matching Logcat's production security boundary.
 */
class SilentLoggerRule : TestRule {
    private val recorded = mutableListOf<String>()

    /** Messages logged during the test, in order. */
    val messages: List<String>
        get() = synchronized(recorded) { recorded.toList() }

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val previous = Logger.platformSink
                Logger.platformSink = RecordingSink()
                try {
                    base.evaluate()
                } finally {
                    Logger.platformSink = previous
                }
            }
        }

    private inner class RecordingSink : PlatformLogSink {
        override fun info(message: String) = record(message)

        override fun error(message: String) = record(message)
    }

    private fun record(message: String) {
        synchronized(recorded) { recorded += message }
    }
}
