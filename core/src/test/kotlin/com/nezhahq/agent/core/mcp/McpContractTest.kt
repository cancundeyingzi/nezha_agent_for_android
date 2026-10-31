package com.nezhahq.agent.core.mcp

import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.core.model.RemoteCapability
import com.nezhahq.agent.core.task.TaskTypes
import org.junit.Assert.*
import org.junit.Test

class McpContractTest {
    @Test
    fun execDefaultsZeroAndUint32MaximumAreNormalizedOnlyAfterValidation() {
        assertEquals(ExecRequest("cmd"), prepared(ExecRequest("cmd")))
        assertEquals(ExecRequest("cmd"), prepared(ExecRequest("cmd", timeoutSeconds = 0, maxOutputBytes = 0)))
        val maximum = prepared(ExecRequest(
            "cmd", timeoutSeconds = McpLimits.UINT32_MAX, maxOutputBytes = McpLimits.UINT32_MAX
        )) as ExecRequest
        assertEquals(300L, maximum.timeoutSeconds)
        assertEquals(1_048_576L, maximum.maxOutputBytes)
        assertTrue(McpRequestRules.prepare(ExecRequest(" \t\n")) is McpValidation.Invalid)
        assertTrue(McpRequestRules.prepare(ExecRequest("cmd", timeoutSeconds = -1)) is McpValidation.Invalid)
        assertTrue(McpRequestRules.prepare(ExecRequest("cmd", maxOutputBytes = 4_294_967_296)) is McpValidation.Invalid)
    }

    @Test
    fun fsReadDefaultsLimitsAndSignedLongBoundaryDoNotOverflow() {
        assertEquals(FsReadRequest("/a"), prepared(FsReadRequest("/a", length = 0, encoding = "")))
        assertEquals(
            FsReadRequest("/a", offset = Long.MAX_VALUE, encoding = "base64"),
            prepared(FsReadRequest("/a", offset = Long.MAX_VALUE, length = Long.MAX_VALUE, encoding = "base64"))
        )
        listOf(
            FsReadRequest("/a", offset = -1),
            FsReadRequest("/a", length = -1),
            FsReadRequest("/a", encoding = "private-encoding"),
            FsReadRequest("relative"),
            FsReadRequest("/a\u0000b")
        ).forEach { assertTrue(McpRequestRules.prepare(it) is McpValidation.Invalid) }
        assertEquals("unknown encoding", (McpRequestRules.prepare(
            FsReadRequest("/a", encoding = "private-encoding")
        ) as McpValidation.Invalid).error.message)
        assertTrue(McpRequestRules.prepare(FsListRequest("")) is McpValidation.Invalid)
        assertEquals(FsListRequest("/", true), prepared(FsListRequest("/", true)))
    }

    @Test
    fun r1PolicyUsesCurrentGrantAndAdvertisesOnlyInstalledHandlers() {
        assertEquals(McpTaskFamily.EXEC, McpPolicy.family(15))
        assertEquals(McpTaskFamily.FS_LIST, McpPolicy.family(16))
        assertEquals(McpTaskFamily.FS_READ, McpPolicy.family(17))
        assertEquals(RemoteCapability.SHELL, McpPolicy.requiredCapability(15))
        assertEquals(RemoteCapability.FILE_MANAGER, McpPolicy.requiredCapability(16))
        assertEquals(RemoteCapability.FILE_MANAGER, McpPolicy.requiredCapability(17))
        listOf(4L, 12L, 13L, 14L, 18L, 19L, 20L, Long.MIN_VALUE).forEach {
            assertNull(McpPolicy.family(it))
        }
        assertEquals(McpErrorCode.PERMISSION_DENIED, McpPolicy.denial(15, RemoteCapabilities())?.code)
        assertNull(McpPolicy.denial(15, RemoteCapabilities(shellEnabled = true)))
        assertNotNull(McpPolicy.denial(15, RemoteCapabilities(fileManagerEnabled = true)))
        assertNull(McpPolicy.denial(16, RemoteCapabilities(fileManagerEnabled = true)))
        assertNotNull(McpPolicy.denial(16, RemoteCapabilities()))
        listOf(12L, 13L, 14L, 18L, 19L, 20L).forEach { assertTrue(TaskTypes.isKnownUnsupportedOnAndroid(it)) }
        (15L..17L).forEach { assertFalse(TaskTypes.isKnownUnsupportedOnAndroid(it)) }
    }

    @Test
    fun dashboard261CompatibilityFixtureRetainsAndroidIdentityAndActualVersion() {
        val fixture = javaClass.getResourceAsStream("/mcp/dashboard-2.6.1-versions.tsv")!!
            .bufferedReader().use { it.readLines() }
        fixture.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
            val (hostVersion, expected) = line.split('\t')
            assertEquals(hostVersion, expected.toInt(), dashboard261Compare(hostVersion, "v2.1.0"))
        }
        assertEquals("2.1.0+android.A0.10.1", McpProtocolVersion.hostVersion("A0.10.1"))
        assertEquals(0, dashboard261Compare(McpProtocolVersion.hostVersion("A0.10.1"), "v2.1.0"))
        assertEquals("2.1.0+android.A0.11.0", McpProtocolVersion.hostVersion("A0.11.0"))
        listOf("", "A0.10.1+other", "A0 10", "A0\n10").forEach {
            try {
                McpProtocolVersion.hostVersion(it)
                fail("Expected invalid version")
            } catch (_: IllegalArgumentException) { }
        }
    }

    /** Literal port of v2.6.1 controller/mcp_capability.go, not a generic SemVer library. */
    private fun dashboard261Compare(left: String, right: String): Int {
        fun parts(version: String): List<Int> {
            val trimmed = version.removePrefix("v").substringBefore('-').substringBefore('+')
            val numbers = trimmed.split('.')
            return (0..2).map { index ->
                var value = 0
                for (char in numbers.getOrElse(index) { "" }) {
                    if (char !in '0'..'9') break
                    value = value * 10 + (char - '0')
                }
                value
            }
        }
        return parts(left).zip(parts(right)).firstOrNull { it.first != it.second }
            ?.let { it.first.compareTo(it.second) } ?: 0
    }

    private fun prepared(request: McpRequest): McpRequest =
        (McpRequestRules.prepare(request) as McpValidation.Valid).request
}
