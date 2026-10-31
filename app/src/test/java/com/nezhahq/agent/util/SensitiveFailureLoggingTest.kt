package com.nezhahq.agent.util

import com.nezhahq.agent.SilentLoggerRule
import com.nezhahq.agent.executor.logTaskFailure
import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SensitiveFailureLoggingTest {
    @get:Rule
    val silentLogger = SilentLoggerRule()

    @Test
    fun commandAndTaskFailuresKeepOnlyTheCategoryAndTaskId() {
        val secret = "private-env-stdin-body-48736"
        val failure = IOException("sh -c 'TOKEN=$secret; cat'", IllegalStateException(secret))

        logShellCommandFailure(failure)
        logTaskFailure(731, failure)

        val logs = silentLogger.messages.joinToString("\n")
        assertTrue(logs.contains("IOException"))
        assertTrue(logs.contains("TaskID=731"))
        assertFalse(logs.contains(secret))
        assertFalse(logs.contains("sh -c"))
        assertFalse("exception message leaked into the UI log", Logger.getLogString().contains(secret))
    }
}
