package com.nezhahq.agent.mcp.transport

import com.nezhahq.agent.mcp.helper.HelperSessionIdentity
import java.lang.ref.Reference

internal interface ApplicationProcessExitProof {
    /** Bind HELLO to the original child and prepare its exit owner before sending a request. */
    fun prepareForRequest(identity: ApplicationProcessIdentity): Boolean = true
    fun destroyUnaccepted()
    /** True only after the original native child has been reaped and its Java exit is published. */
    fun confirmExited(): Boolean
}

internal fun interface ApplicationProcessExitFactory {
    fun capture(process: Process): ApplicationProcessExitProof
}

internal fun javaProcessExitProof(process: Process) = object : ApplicationProcessExitProof {
    override fun destroyUnaccepted() {
        try { process.exitValue() }
        catch (_: IllegalThreadStateException) { process.destroy() }
    }
    override fun confirmExited(): Boolean { process.exitValue(); return true }
}

internal data class OwnedChildIdentity(val pid: Int, val uid: Int, val parentPid: Int, val startTimeTicks: Long)
internal data class ChildProcessObservation(val uid: Int, val stat: HelperSessionIdentity.Snapshot) {
    val identity get() = OwnedChildIdentity(stat.pid, uid, stat.parentPid, stat.startTimeTicks)
}
internal data class NativeChildWait(val pid: Int, val exitCode: Int?)

/** The registry lock also serializes ProcessManager.exec and its regular child-exit publisher. */
internal interface DetachedChildReaper {
    val registry: MutableMap<*, *>
    fun observe(pid: Int): ChildProcessObservation?
    fun kill(pid: Int)
    fun waitNoHang(pid: Int): NativeChildWait
    fun publishExit(process: Process, exitCode: Int)
}

/**
 * Android 6's waitpid(0) cannot collect a child after setsid(), and spins while its registration
 * remains. Transfer only the original isolated child, then reap it and publish to that Process.
 */
internal class DetachedChildExitProof private constructor(
    private val process: Process,
    private val pid: Int,
    private val uid: Int,
    private val parentPid: Int,
    private val reaper: DetachedChildReaper
) : ApplicationProcessExitProof {
    private var identity: OwnedChildIdentity? = null
    private var originalReference: Reference<*>? = null
    // Set BEFORE changing the registry; even a partial transfer remains owned by this proof.
    private var transferStarted = false
    // If publication fails after a successful wait, retry publication without another numeric wait.
    private var reapedExit: Int? = null

    override fun prepareForRequest(identity: ApplicationProcessIdentity): Boolean = synchronized(reaper.registry) {
        if (!identity.matches(uid, pid) || javaExited() || reapedExit != null) return@synchronized false
        val current = observeOriginal() ?: return@synchronized false
        val expected = OwnedChildIdentity(identity.stat.pid, identity.uid, identity.stat.parentPid, identity.stat.startTimeTicks)
        current.identity == expected && current.stat.ownsSession && current.stat.isLive && transfer()
    }

    override fun destroyUnaccepted() {
        val platformOwned = synchronized(reaper.registry) {
            if (javaExited() || reapedExit != null) return
            val current = observeOriginal() ?: return
            if (current.stat.ownsSession) {
                // Only our exclusively owned detached child may receive a numeric signal. Its
                // reaping is serialized by this lock; pipe closure stays in the cleanup worker.
                if (transfer() && current.stat.isLive) reaper.kill(pid)
                false
            } else !transferStarted && current.stat.isLive
        }
        // Ordinary children retain the platform's Process semantics. destroy also closes pipes,
        // so it must run outside the global registry lock, in the existing bounded cleanup worker.
        if (platformOwned) process.destroy()
    }

    override fun confirmExited(): Boolean {
        if (javaExited()) return true
        return synchronized(reaper.registry) {
            if (javaExited()) return@synchronized true
            val knownExit = reapedExit
            if (knownExit != null) {
                reaper.publishExit(process, knownExit)
                return@synchronized javaExited()
            }
            val current = observeOriginal() ?: return@synchronized false
            if (!current.stat.ownsSession || !transfer() || current.stat.state != 'Z') return@synchronized false
            // A zombie alone is not exit proof. WNOHANG reaps the original detached child without
            // blocking ProcessManager.exec; only a successful native wait permits publication.
            val waited = reaper.waitNoHang(pid)
            val exit = waited.exitCode
            if (waited.pid != pid || exit == null) return@synchronized false
            reapedExit = exit
            reaper.publishExit(process, exit)
            javaExited()
        }
    }

    /** Called under the registry lock, including retries after a partial clear/remove. */
    private fun observeOriginal(): ChildProcessObservation? {
        if (transferStarted) {
            if (!transfer() || reaper.registry[pid] != null) return null
        } else {
            val registered = reaper.registry[pid] as? Reference<*> ?: return null
            if (registered.get() !== process || originalReference?.let { it !== registered } == true) return null
            originalReference = registered
        }
        val current = reaper.observe(pid) ?: return null
        if (current.stat.pid != pid || current.uid != uid || current.stat.parentPid != parentPid || current.stat.startTimeTicks <= 0) return null
        val captured = identity
        if (captured != null && current.identity != captured) return null
        // A normal-group zombie may already be in the platform's native wait/onExit handoff.
        if (captured == null && !current.stat.isLive && !current.stat.ownsSession) return null
        identity = current.identity
        return current
    }

    private fun transfer(): Boolean {
        val reference = checkNotNull(originalReference)
        transferStarted = true
        // A cleared reference cannot later enter ReferenceQueue and erase a reused PID's entry.
        reference.clear()
        if (reaper.registry[pid] === reference) reaper.registry.remove(pid)
        return reaper.registry[pid] !== reference
    }

    private fun javaExited(): Boolean = try { process.exitValue(); true }
        catch (_: IllegalThreadStateException) { false }

    companion object {
        fun capture(process: Process, pid: Int, uid: Int, parentPid: Int, reaper: DetachedChildReaper): ApplicationProcessExitProof {
            require(pid > 1)
            return DetachedChildExitProof(process, pid, uid, parentPid, reaper).also { proof ->
                synchronized(reaper.registry) {
                    // Preserve the proof on observation/transfer failure. HELLO must retry and
                    // verify it; cancellation still owns the same native child and cleanup ticket.
                    try {
                        if (!proof.javaExited() && proof.observeOriginal()?.stat?.ownsSession == true) proof.transfer()
                    } catch (_: Exception) { /* Deferred to handshake or cleanup. */ }
                }
            }
        }
    }
}
