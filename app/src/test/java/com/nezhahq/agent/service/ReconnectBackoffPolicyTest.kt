package com.nezhahq.agent.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectBackoffPolicyTest {

    /**
     * The ceiling schedule, read through a jitter that hands back whatever it was offered.
     *
     * Pinning it here is deliberate: these two numbers are the ones a user is most likely to want
     * to retune, and a change to either should have to say so out loud.
     */
    @Test
    fun ceilingsDoubleFromFiveSecondsAndStopAtThirty() {
        val ceilings = mutableListOf<Long>()
        val policy = ReconnectBackoffPolicy(
            jitter = { ceiling ->
                ceilings += ceiling
                ceiling
            }
        )

        repeat(6) { policy.nextDelayMillis() }

        assertEquals(
            listOf(5_000L, 10_000L, 20_000L, 30_000L, 30_000L, 30_000L),
            ceilings
        )
    }

    @Test
    fun theWaitIsWhateverTheJitterDrawsInsideTheCeiling() {
        val policy = ReconnectBackoffPolicy(jitter = { ceiling -> ceiling / 4 })

        assertEquals(1_250L, policy.nextDelayMillis())
        assertEquals(2_500L, policy.nextDelayMillis())
    }

    /**
     * A working session has to put the next failure back at the bottom of the ladder.
     *
     * Without this, a device that reconnects cleanly after a rough patch keeps the thirty-second
     * ceiling forever, and the next unrelated blip costs half a minute of downtime it did not earn.
     */
    @Test
    fun aWorkingSessionResetsTheLadderToItsFirstRung() {
        val ceilings = mutableListOf<Long>()
        val policy = ReconnectBackoffPolicy(
            jitter = { ceiling ->
                ceilings += ceiling
                ceiling
            }
        )
        repeat(4) { policy.nextDelayMillis() }

        policy.reset()
        policy.nextDelayMillis()
        policy.nextDelayMillis()

        assertEquals(listOf(5_000L, 10_000L), ceilings.takeLast(2))
    }

    /**
     * The production jitter really does spread waits across the window.
     *
     * This is what stops a Dashboard restart from being answered by every agent at the same
     * instant, so a constant — a policy that quietly returned its ceiling — has to fail here.
     * Resetting between draws holds the ceiling at the base rung, making the bound exact.
     */
    @Test
    fun theDefaultJitterSpreadsWaitsAcrossTheWholeBaseCeiling() {
        val policy = ReconnectBackoffPolicy()

        val draws = List(500) {
            val wait = policy.nextDelayMillis()
            policy.reset()
            wait
        }

        assertTrue(
            "Every wait must land inside [0, ${ReconnectBackoffPolicy.BASE_DELAY_MILLIS}).",
            draws.all { it >= 0L && it < ReconnectBackoffPolicy.BASE_DELAY_MILLIS }
        )
        assertTrue(
            "500 draws that are all identical are not jitter.",
            draws.distinct().size > 1
        )
    }
}
