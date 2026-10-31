package com.nezhahq.agent.mcp.privileged

import com.nezhahq.agent.mcp.helper.HelperSessionIdentity
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HelperCleanupVerifierTest {
    @Test fun cleanupOnlySignalsFreshUidNonceSessionAndStartTokensThenConfirmsExit() {
        val control = FakeControl()
        control.records[123] = record(123, listOf("nezha-mcp-$nonce"))
        control.records[456] = record(456, listOf("/system/bin/sh", "-c", "fixed script", "nezha-mcp-proxy-$nonce"))
        control.records[789] = record(789, listOf("unrelated"))
        assertTrue(HelperCleanupVerifier(control).clean(nonce, 2000, 123, 100))
        assertEquals(listOf("group:123", "proxy:456"), control.signals)
        assertTrue(control.records.containsKey(789))
    }

    @Test fun reusedPidWrongStartUidOrNonceNeverAuthorizesASignal() {
        for (different in listOf(
            record(123, listOf("nezha-mcp-$nonce"), start = 999),
            record(123, listOf("nezha-mcp-$nonce"), uid = 0),
            record(123, listOf("nezha-mcp-${"1".repeat(64)}"))
        )) {
            val control = FakeControl().apply { records[123] = different }
            HelperCleanupVerifier(control).clean(nonce, 2000, 123, 100)
            assertTrue(control.signals.isEmpty())
        }
    }

    @Test fun identityChangeBetweenScanAndSignalFailsClosed() {
        val control = FakeControl().apply {
            records[123] = record(123, listOf("nezha-mcp-$nonce"))
            afterRead = { pid, count -> if (pid == 123 && count == 1) records[pid] = records[pid]!!.copy(
                stat = records[pid]!!.stat.copy(startTimeTicks = 999)
            ) }
        }
        assertFalse(HelperCleanupVerifier(control).clean(nonce, 2000, 123, 100))
        assertTrue(control.signals.isEmpty())
    }

    @Test fun unreadableSameUidIsUnknownAndCannotClaimCapacityRecovered() {
        val control = FakeControl().apply { records[123] = record(123, listOf("nezha-mcp-$nonce")); unreadable = true }
        assertThrows(IOException::class.java) { HelperCleanupVerifier(control).clean(nonce, 2000, 123, 100) }
        assertTrue(control.signals.isEmpty())
    }

    @Test fun helperMustOwnItsSessionAndProxyMarkerMustMatchAnEntireArgument() {
        val control = FakeControl().apply {
            records[123] = record(123, listOf("nezha-mcp-$nonce")).let { it.copy(stat = it.stat.copy(sessionId = 999)) }
            records[456] = record(456, listOf("prefix-nezha-mcp-proxy-$nonce-suffix"))
        }
        assertFalse(HelperCleanupVerifier(control).clean(nonce, 2000, 123, 100))
        assertTrue(control.signals.isEmpty())
    }

    @Test fun residualDescendantWithoutNonceCannotBeReportedAsRecovered() {
        val control = FakeControl().apply {
            records[123] = record(123, listOf("nezha-mcp-$nonce"))
            records[789] = record(789, listOf("ordinary descendant")).let {
                it.copy(stat = it.stat.copy(processGroupId = 123, sessionId = 123))
            }
        }
        assertFalse(HelperCleanupVerifier(control).clean(nonce, 2000, 123, 100))
        assertEquals(listOf("group:123"), control.signals)
    }

    private class FakeControl : CleanupProcessControl {
        val records = linkedMapOf<Int, CleanupProcessRecord>()
        val signals = mutableListOf<String>()
        val reads = mutableMapOf<Int, Int>()
        var afterRead: (Int, Int) -> Unit = { _, _ -> }
        var unreadable = false
        override fun pids() = records.keys.toList()
        override fun read(pid: Int, expectedUid: Int): CleanupProcessRecord? {
            if (unreadable) throw IOException("unreadable")
            val count = (reads[pid] ?: 0) + 1
            reads[pid] = count
            return records[pid]?.takeIf { it.uid == expectedUid }.also { afterRead(pid, count) }
        }
        override fun killGroup(pid: Int) { signals += "group:$pid"; records.remove(pid) }
        override fun killProxy(pid: Int) { signals += "proxy:$pid"; records.remove(pid) }
    }

    companion object {
        private val nonce = "0".repeat(64)
        private fun record(pid: Int, args: List<String>, uid: Int = 2000, start: Long = 100) = CleanupProcessRecord(
            uid, HelperSessionIdentity.Snapshot(pid, pid, pid, start, 'S'), args
        )
    }
}
