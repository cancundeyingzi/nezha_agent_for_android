package com.nezhahq.agent.executor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NatTargetValidationTest {
    @Test
    fun natTargetsMustHaveAnExplicitValidPortBeforeSessionAdmission() {
        listOf("example.com", "example.com:0", "127.0.0.1:65536", "[::1]", "::1:80").forEach { target ->
            val failure = assertThrows(IllegalArgumentException::class.java) { requireValidNatTarget(target) }
            assertTrue("failure must identify the NAT target", failure.message.orEmpty().contains("NAT target"))
        }
    }

    @Test
    fun validTargetsUseTheSameHostAndPortAsTheNatConnector() {
        assertEquals(HostPort.Result.Parsed("example.com", 443), requireValidNatTarget("example.com:443"))
        assertEquals(HostPort.Result.Parsed("127.0.0.1", 8080), requireValidNatTarget("127.0.0.1:8080"))
        assertEquals(HostPort.Result.Parsed("::1", 80), requireValidNatTarget("[::1]:80"))
    }
}
