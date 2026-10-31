package com.nezhahq.agent.mcp.privileged

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.nezhahq.agent.mcp.helper.HelperSessionIdentity
import com.nezhahq.agent.mcp.transport.HelperProtocol
import java.io.File
import java.io.FileInputStream

/**
 * Short fixed recovery probe. It accepts only program-generated identity, never commands/paths.
 * Runs under the newly authorized captured backend. It never signals an unverified numeric PID.
 */
object HelperCleanupMain {
    @JvmStatic fun main(args: Array<String>) {
        Thread({ Thread.sleep(1_200); Os.kill(Os.getpid(), OsConstants.SIGKILL) }, "mcp-cleanup-watchdog")
            .apply { isDaemon = true; start() }
        try {
            if (args.size != 4 || HelperProtocol.parseNonce(args[0]) == null) return
            val uid = args[1].toIntOrNull()?.takeIf { it == 0 || it == 2000 } ?: return
            if (Os.getuid() != uid || Os.geteuid() != uid) return
            val pid = args[2].toIntOrNull()?.takeIf { it == 0 || it > 1 } ?: return
            val start = args[3].toLongOrNull()?.takeIf { it >= 0 } ?: return
            val nonce = args[0]
            // Distinguish a real selected UID from an authorized probe whose old task has not
            // converged yet. A PENDING cleanup must not be treated as another su auth denial.
            System.out.print("READY:$nonce:$uid\n")
            System.out.flush()
            val clean = HelperCleanupVerifier(AndroidCleanupProcessControl).clean(nonce, uid, pid, start)
            if (clean) { System.out.print("CLEAN:$nonce\n"); System.out.flush() }
        } catch (_: Throwable) {
            // Fail closed; no raw paths, credentials or exception messages are emitted.
        }
    }

}

private object AndroidCleanupProcessControl : CleanupProcessControl {
    override fun pids(): List<Int>? = File("/proc").listFiles()?.mapNotNull {
        it.name.toIntOrNull()?.takeIf { pid -> pid > 1 }
    }
    override fun killGroup(pid: Int) = Os.kill(-pid, OsConstants.SIGKILL)
    override fun killProxy(pid: Int) = Os.kill(pid, OsConstants.SIGKILL)

    override fun read(pid: Int, expectedUid: Int): CleanupProcessRecord? {
        val directory = "/proc/$pid"
        return try {
            val uid = Os.stat(directory).st_uid
            if (uid != expectedUid) return null
            val stat = FileInputStream("$directory/stat").use { HelperSessionIdentity.readSnapshot(it, pid) }
                ?: throw java.io.IOException("Unverifiable process identity")
            val arguments = FileInputStream("$directory/cmdline").use { input ->
                val buffer = ByteArray(16_384)
                var used = 0
                while (used < buffer.size) {
                    val count = input.read(buffer, used, buffer.size - used)
                    if (count < 0) break
                    if (count == 0) throw java.io.IOException("Unverifiable process arguments")
                    used += count
                }
                if (used == buffer.size) throw java.io.IOException("Unverifiable process arguments")
                buffer.copyOf(used).toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
            }
            CleanupProcessRecord(uid, stat, arguments)
        } catch (failure: ErrnoException) {
            if (failure.errno == OsConstants.ENOENT || failure.errno == OsConstants.ESRCH) null else throw failure
        } catch (failure: java.io.FileNotFoundException) {
            // A same-UID record that still exists but cannot be read is UNKNOWN, never absence.
            try { Os.stat(directory); throw failure } catch (gone: ErrnoException) {
                if (gone.errno == OsConstants.ENOENT || gone.errno == OsConstants.ESRCH) null else throw gone
            }
        }
    }
}
