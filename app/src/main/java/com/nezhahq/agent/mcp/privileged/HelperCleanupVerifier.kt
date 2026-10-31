package com.nezhahq.agent.mcp.privileged

import com.nezhahq.agent.mcp.helper.HelperSessionIdentity
import com.nezhahq.agent.mcp.transport.HelperProtocol

internal data class CleanupProcessRecord(
    val uid: Int, val stat: HelperSessionIdentity.Snapshot, val arguments: List<String>
) {
    fun same(other: CleanupProcessRecord) = uid == other.uid && stat.pid == other.stat.pid &&
        stat.startTimeTicks == other.stat.startTimeTicks && stat.processGroupId == other.stat.processGroupId &&
        stat.sessionId == other.stat.sessionId && arguments == other.arguments
}

/** null means proven absent/different UID. Unreadable same-UID identity must throw, not return null. */
internal interface CleanupProcessControl {
    fun pids(): List<Int>?
    fun read(pid: Int, expectedUid: Int): CleanupProcessRecord?
    fun killGroup(pid: Int)
    fun killProxy(pid: Int)
}

/** Pure recovery algorithm shared by the short privileged probe and injected JVM tests. */
internal class HelperCleanupVerifier(private val control: CleanupProcessControl) {
    fun clean(nonce: String, uid: Int, pid: Int, start: Long): Boolean {
        if (HelperProtocol.parseNonce(nonce) == null || uid !in setOf(0, 2000) ||
            (pid != 0 && pid <= 1) || start < 0) return false
        val helper = "nezha-mcp-$nonce"
        val proxy = "nezha-mcp-proxy-$nonce"
        fun inspect(): List<CleanupProcessRecord>? {
            val all = control.pids() ?: return null
            if (all.size > 4_096) return null
            return all.mapNotNull { control.read(it, uid) }.filter { it.uid == uid }
        }
        val records = inspect() ?: return false
        val matches = records.filter { record ->
            record.arguments.firstOrNull() == helper || proxy in record.arguments
        }
        val groups = matches.filter { it.arguments.firstOrNull() == helper }.map { it.stat.pid }.toMutableSet()
        if (pid > 1) groups += pid
        for (record in matches.filter { it.arguments.firstOrNull() == helper }) {
            if (pid != 0 && (record.stat.pid != pid || record.stat.startTimeTicks != start)) return false
            if (!record.stat.ownsSession) return false
            if (!record.stat.isLive) continue
            val current = control.read(record.stat.pid, uid) ?: continue
            if (!record.same(current) || current.arguments.firstOrNull() != helper) return false
            control.killGroup(current.stat.pid)
        }
        for (record in matches.filter { proxy in it.arguments }) {
            val current = control.read(record.stat.pid, uid) ?: continue
            if (!record.same(current) || proxy !in current.arguments) return false
            if (current.stat.isLive) control.killProxy(current.stat.pid)
        }
        val until = System.nanoTime() + 300_000_000L
        while (true) {
            // Check the complete same-UID group again, including descendants whose argv carries
            // no marker and children created after the original scan. This read never signals a
            // cached group ID. A residual/reused group remains UNKNOWN and retains capacity.
            val current = inspect() ?: return false
            val live = current.any { record ->
                record.stat.isLive && (
                    (record.stat.processGroupId in groups && record.stat.sessionId == record.stat.processGroupId) ||
                    matches.any { original -> record.stat.pid == original.stat.pid &&
                        record.stat.startTimeTicks == original.stat.startTimeTicks }
                )
            }
            if (!live) return true
            if (System.nanoTime() >= until) return false
            Thread.sleep(10)
        }
    }
}
