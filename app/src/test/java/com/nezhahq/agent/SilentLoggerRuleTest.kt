package com.nezhahq.agent

import com.nezhahq.agent.util.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SilentLoggerRuleTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    @Test
    fun loggingInsideATestReachesTheRuleRatherThanTheAndroidLog() {
        Logger.i("info line")
        Logger.e("error line", IllegalStateException("cause"))

        assertEquals(listOf("info line", "error line (Error=IllegalStateException)"), silentLogger.messages)
    }

    /** The rule makes platform output observable without depending on Android logging stubs. */
    @Test
    fun theInstalledSinkIsNotTheAndroidOne() {
        assertNotNull(Logger.platformSink)
        assertTrue(Logger.platformSink.javaClass.name.contains("SilentLoggerRule"))
    }
}
