package com.nezhahq.agent.mcp.helper

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Bounded /proc fallback for a launcher that has already made the helper a session leader. */
object HelperSessionIdentity {
    const val MAX_STAT_BYTES = 4096

    data class Snapshot(
        val pid: Int,
        val processGroupId: Int,
        val sessionId: Int,
        val startTimeTicks: Long,
        val state: Char
    ) {
        val ownsSession: Boolean get() = pid > 1 && processGroupId == pid && sessionId == pid
        val isLive: Boolean get() = state !in "ZXx"
    }

    fun readOwnedSession(input: InputStream, pid: Int): Boolean = readSnapshot(input, pid)?.ownsSession == true

    fun readSnapshot(input: InputStream, pid: Int): Snapshot? {
        val bytes = ByteArrayOutputStream(512)
        val buffer = ByteArray(512)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_STAT_BYTES + 1 - bytes.size()))
            if (count < 0) return snapshot(bytes.toByteArray(), pid)
            if (count == 0) return null
            bytes.write(buffer, 0, count)
            if (bytes.size() > MAX_STAT_BYTES) return null
        }
    }

    fun isOwnedSession(bytes: ByteArray, pid: Int): Boolean = snapshot(bytes, pid)?.ownsSession == true

    fun snapshot(bytes: ByteArray, pid: Int): Snapshot? {
        if (pid <= 1 || bytes.isEmpty() || bytes.size > MAX_STAT_BYTES || bytes.any { it == 0.toByte() }) return null
        val stat = bytes.toString(Charsets.UTF_8)
        val prefix = "$pid ("
        if (!stat.startsWith(prefix)) return null
        // comm may contain spaces, '(' and ')'. Only its LAST ')' terminates the comm field.
        val end = stat.lastIndexOf(')')
        if (end < prefix.length || end + 2 >= stat.length || stat[end + 1] != ' ') return null
        // Field 3 is state; field 22 is starttime. Bound the split as well as the raw read.
        val fields = stat.substring(end + 2).trim().split(Regex("\\s+"), limit = 21)
        if (fields.size < 20 || fields[0].length != 1 || fields[0][0] !in "RSDZTWXxKWPIt") return null
        fun number(index: Int): Int? = fields[index].takeIf { it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } }?.toIntOrNull()
        if (number(1) == null) return null
        val group = number(2) ?: return null
        val session = number(3) ?: return null
        val start = fields[19].takeIf { it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } }?.toLongOrNull() ?: return null
        return Snapshot(pid, group, session, start, fields[0][0])
    }
}
