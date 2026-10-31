package com.nezhahq.agent.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RootShellLifecycleTest {
    @Test
    fun detachedRootShellIsRetiredBeforeSlowCleanupStarts() {
        val shellField = RootShell::class.java.getDeclaredField("persistentShell").apply { isAccessible = true }
        val lifecycleLock = checkNotNull(RootShell::class.java.getDeclaredField("authorizationLifecycleLock")
            .apply { isAccessible = true }.get(RootShell))
        val detach = RootShell::class.java.getDeclaredMethod("detachLegacyResources").apply { isAccessible = true }
        val originalShell = shellField.get(RootShell)
        val marker = ShellMarker("detached_root_shell_session")
        val process = FakeProcess("ready${marker.prefix}0${marker.suffix}")
        val starts = AtomicInteger()
        val oldShell = PersistentShell(
            sessionFactory = { starts.incrementAndGet(); ShellSession(process, "old") },
            markerFactory = { marker }
        )
        var replacement: PersistentShell? = null
        try {
            assertEquals("ready", oldShell.execute("before-detach", timeoutMs = 1_000))
            val writtenBeforeDetach = process.writtenUtf8()
            val resources = synchronized(lifecycleLock) {
                shellField.set(RootShell, oldShell)
                val detached = checkNotNull(detach.invoke(RootShell))
                replacement = shellField.get(RootShell) as PersistentShell
                detached
            }

            assertNotSame(oldShell, replacement)
            assertFalse(process.destroyed)
            assertEquals("", oldShell.execute("before-cleanup", timeoutMs = 1_000))
            assertEquals(writtenBeforeDetach, process.writtenUtf8())

            resources.javaClass.getDeclaredMethod("close").apply { isAccessible = true }.invoke(resources)
            assertTrue(process.destroyed)
            assertEquals("", oldShell.execute("after-cleanup", timeoutMs = 1_000))
            assertEquals(1, starts.get())
        } finally {
            synchronized(lifecycleLock) { shellField.set(RootShell, originalShell) }
            oldShell.shutdown()
            replacement?.shutdown()
        }
    }

    @Test
    fun staleShellFailureCannotDetachNewShellOrManagedProcess() {
        // Exercise the owner check without invoking Android/Shizuku APIs or adding test hooks.
        val shellField = RootShell::class.java.getDeclaredField("persistentShell").apply { isAccessible = true }
        val managedField = RootShell::class.java.getDeclaredField("managedProcesses").apply { isAccessible = true }
        val close = RootShell::class.java.getDeclaredMethod(
            "closePrivilegedResources", PersistentShell::class.java
        ).apply { isAccessible = true }
        val originalShell = shellField.get(RootShell)
        @Suppress("UNCHECKED_CAST")
        val managedProcesses = managedField.get(RootShell) as MutableSet<RootShell.ManagedProcess>
        val marker = ShellMarker("current_root_shell_session")
        val shellProcess = FakeProcess("ready${marker.prefix}0${marker.suffix}")
        val managedProcess = FakeProcess("")
        val managed = RootShell.ManagedProcess(managedProcess, "new")
        val currentShell = PersistentShell(
            sessionFactory = { ShellSession(shellProcess, "new") },
            markerFactory = { marker }
        )
        val staleShell = PersistentShell(sessionFactory = { error("stale shell reopened") }, markerFactory = { marker })
        try {
            shellField.set(RootShell, currentShell)
            managedProcesses += managed

            close.invoke(RootShell, staleShell)

            assertSame(currentShell, shellField.get(RootShell))
            assertTrue(managed in managedProcesses)
            assertFalse(managedProcess.destroyed)
            assertEquals("ready", currentShell.execute("new-epoch-command", timeoutMs = 1_000))
            assertFalse(shellProcess.destroyed)
        } finally {
            shellField.set(RootShell, originalShell)
            managed.close()
            staleShell.shutdown()
            currentShell.shutdown()
        }
    }

    private class FakeProcess(payload: String) : Process() {
        private val input = ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8))
        private val output = ByteArrayOutputStream()
        @Volatile var destroyed = false
            private set
        override fun getInputStream() = input
        override fun getOutputStream() = output
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = throw UnsupportedOperationException()
        override fun exitValue(): Int {
            if (!destroyed) throw IllegalThreadStateException("still running")
            return 0
        }
        override fun destroy() { destroyed = true }
        fun writtenUtf8(): String = output.toString(Charsets.UTF_8.name())
    }
}
