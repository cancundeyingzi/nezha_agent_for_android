package com.nezhahq.agent.mcp.helper

import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import com.nezhahq.agent.mcp.transport.HelperProtocol
import com.nezhahq.agent.mcp.fs.AndroidFsAccess
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The sole R8-kept app_process entry. There is no Android component or persistent agent. */
object HelperMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val input = FileInputStream(FileDescriptor.`in`)
        val output = FileOutputStream(FileDescriptor.out)
        // Only the explicit frame writer can use stdout. Accidental library printing is discarded;
        // the launcher's separate stderr pipe still handles app_process startup diagnostics.
        val discard = PrintStream(object : OutputStream() { override fun write(value: Int) = Unit })
        System.setOut(discard)
        System.setErr(discard)
        val isolation = AndroidHelperIsolation()
        try {
            if (args.size != 4) return
            val nonce = HelperProtocol.parseNonce(args[0]) ?: return
            val lifetime = args[3].toIntOrNull()?.takeIf { it in 1..HelperProtocol.MAX_LIFETIME_MILLIS } ?: return
            val directory = File(args[1])
            val temporary = File(args[2])
            if (!directory.isAbsolute || !temporary.isAbsolute) return
            HelperSession(isolation, HelperSession.handlers(directory, temporary, AndroidFsAccess()))
                .run(input, output, nonce, lifetime)
        } catch (_: Throwable) {
            // No raw request, environment, output or exception text goes into platform logs.
        } finally { isolation.terminate() }
    }
}

private class AndroidHelperIsolation : HelperIsolation {
    private val ownedGroup = AtomicInteger(0)
    private val terminating = AtomicBoolean(false)

    override fun establish(nonce: ByteArray): HelperProtocol.Hello {
        val pid = Os.getpid()
        val session = try {
            Os.setsid() // Public since API 21. No JNI or hidden getsid()/Process.pid().
        } catch (failure: ErrnoException) {
            // su/another explicitly selected launcher may already have established this session.
            // EPERM alone proves nothing; verify the actual kernel pgrp/session fields boundedly.
            if (failure.errno != OsConstants.EPERM ||
                !FileInputStream("/proc/self/stat").use { HelperSessionIdentity.readOwnedSession(it, pid) }) {
                throw failure
            }
            pid
        }
        check(pid > 1 && session == pid)
        ownedGroup.set(pid)
        Os.kill(-pid, 0)
        val identity = FileInputStream("/proc/self/stat").use { HelperSessionIdentity.readSnapshot(it, pid) }
        check(identity != null && identity.ownsSession && identity.startTimeTicks > 0)
        return HelperProtocol.Hello(
            HelperProtocol.VERSION, nonce, Os.getuid(), Os.geteuid(), pid, session, identity.startTimeTicks
        )
    }

    override fun terminate() {
        if (!terminating.compareAndSet(false, true)) return
        try {
            val group = ownedGroup.get()
            if (group > 1) Os.kill(-group, OsConstants.SIGKILL)
        } finally {
            // Covers isolation failure and a failed group signal without trusting any remote PID.
            Os.kill(Os.getpid(), OsConstants.SIGKILL)
        }
    }
}
