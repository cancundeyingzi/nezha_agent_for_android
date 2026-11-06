package com.nezhahq.agent.mcp

import com.nezhahq.agent.mcp.helper.HelperSessionIdentity
import com.nezhahq.agent.mcp.transport.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.ref.Reference
import java.lang.ref.WeakReference
import org.junit.Assert.*
import org.junit.Test

class DetachedChildExitProofTest {
    @Test fun liveDetachedChildTransfersBeforeRequestWithoutClaimingExit() {
        val process = PendingProcess()
        val platform = Platform(process)
        val ordinary = WeakReference(PendingProcess())
        platform.registry[101] = ordinary
        val proof = capture(process, platform)
        assertSame(platform.originalReference, platform.registry[100])
        platform.detach()
        assertTrue(proof.prepareForRequest(platform.helloIdentity()))
        assertNull(platform.originalReference.get())
        assertFalse(platform.registry.containsKey(100))
        assertSame(ordinary, platform.registry[101])
        assertFalse(proof.confirmExited())
        assertEquals(0, platform.waits)
        assertEquals(0, platform.publications)
    }

    @Test fun alreadyDetachedChildTransfersDuringCapture() {
        val process = PendingProcess()
        val platform = Platform(process).apply { detach() }
        val proof = capture(process, platform)
        assertTrue(platform.registry.isEmpty())
        assertNull(platform.originalReference.get())
        assertTrue(proof.prepareForRequest(platform.helloIdentity()))
        assertFalse(proof.confirmExited())
        assertEquals(0, platform.waits)
    }

    @Test fun ordinaryChildKeepsPlatformRegistrationAndDestroyRunsOutsideItsLock() {
        val process = PendingProcess()
        val platform = Platform(process)
        process.onDestroy = { assertFalse(Thread.holdsLock(platform.registry)) }
        val proof = capture(process, platform)
        assertFalse(proof.confirmExited())
        assertFalse(proof.prepareForRequest(platform.helloIdentity()))
        proof.destroyUnaccepted()
        assertEquals(1, process.destroys)
        assertSame(platform.originalReference, platform.registry[100])
        assertSame(process, platform.originalReference.get())
        assertEquals(0, platform.kills)
        assertEquals(0, platform.waits)
        process.exit = 0 // The platform remains responsible for publication.
        assertTrue(proof.confirmExited())
    }

    @Test fun detachedZombieIsReapedAndPublishedUnderTheOriginalRegistryLock() {
        val process = PendingProcess()
        val platform = Platform(process)
        val proof = capture(process, platform)
        platform.detachAndExit()
        assertTrue(proof.confirmExited())
        assertEquals(9, process.exitValue())
        assertEquals(1, platform.waits)
        assertEquals(1, platform.publications)
        assertNull(platform.originalReference.get())
        assertTrue(proof.confirmExited())
        assertEquals(1, platform.waits)
    }

    @Test fun cancellationBeforeHelloTransfersThenSignalsOnlyTheOriginalDetachedChild() {
        val process = PendingProcess()
        val platform = Platform(process)
        val proof = capture(process, platform)
        platform.detach()
        proof.destroyUnaccepted()
        assertEquals(1, platform.kills)
        assertEquals(0, process.destroys)
        assertTrue(platform.registry.isEmpty())
        assertNull(platform.originalReference.get())
        assertNull(process.exit)
        assertFalse(proof.confirmExited()) // A successful kill itself is not exit proof.
        platform.detachAndExit()
        assertTrue(proof.confirmExited())
    }

    @Test fun zombieZeroWaitWrongPidOrNonterminalStatusCannotReleaseOwnership() {
        for (result in listOf(NativeChildWait(0, null), NativeChildWait(101, 9), NativeChildWait(100, null))) {
            val process = PendingProcess()
            val platform = Platform(process)
            val proof = capture(process, platform)
            platform.detachAndExit()
            platform.waitResult = result
            assertFalse(proof.confirmExited())
            assertNull(process.exit)
            assertEquals(0, platform.publications)
        }
    }

    @Test fun echildOrOtherWaitFailureIsNotExitProof() {
        val process = PendingProcess()
        val platform = Platform(process)
        val proof = capture(process, platform)
        platform.detachAndExit()
        platform.waitFailure = IOException("ECHILD")
        assertThrows(IOException::class.java) { proof.confirmExited() }
        assertNull(process.exit)
        assertEquals(0, platform.publications)
    }

    @Test fun changedIdentityOrRegistrationNeverReapsOrSignalsANumericPid() {
        val changes: List<(Platform) -> Unit> = listOf(
            { it.current = it.current!!.copy(uid = 999) },
            { it.current = it.current!!.copy(stat = it.current!!.stat.copy(pid = 101)) },
            { it.current = it.current!!.copy(stat = it.current!!.stat.copy(parentPid = 21)) },
            { it.current = it.current!!.copy(stat = it.current!!.stat.copy(startTimeTicks = 9999)) },
            { it.current = it.current!!.copy(stat = it.current!!.stat.copy(processGroupId = 50, sessionId = 50)) },
            { it.current = null },
            { it.registry[100] = WeakReference(PendingProcess()) }
        )
        for (change in changes) {
            val process = PendingProcess()
            val platform = Platform(process)
            val proof = capture(process, platform)
            platform.detachAndExit()
            change(platform)
            assertFalse(proof.confirmExited())
            proof.destroyUnaccepted()
            assertEquals(0, platform.waits)
            assertEquals(0, platform.publications)
            assertEquals(0, platform.kills)
            assertEquals(0, process.destroys)
        }
    }

    @Test fun invalidDetachedIdentityNeverFallsBackToPlatformDestroy() {
        val process = PendingProcess()
        val platform = Platform(process).apply { detach() }
        val proof = capture(process, platform)
        platform.current = platform.current!!.copy(stat = platform.current!!.stat.copy(processGroupId = 50, sessionId = 50))
        proof.destroyUnaccepted()
        assertFalse(proof.confirmExited())
        assertEquals(0, process.destroys)
        assertEquals(0, platform.kills)
        assertEquals(0, platform.waits)
    }

    @Test fun helloForADifferentChildCannotTransferTheOriginalRegistration() {
        val changes: List<(ApplicationProcessIdentity) -> ApplicationProcessIdentity> = listOf(
            { it.copy(uid = 999) },
            { it.copy(stat = it.stat.copy(pid = 101)) },
            { it.copy(stat = it.stat.copy(parentPid = 21)) },
            { it.copy(stat = it.stat.copy(startTimeTicks = 9999)) },
            { it.copy(stat = it.stat.copy(processGroupId = 999)) },
            { it.copy(stat = it.stat.copy(sessionId = 999)) },
            { it.copy(stat = it.stat.copy(state = 'Z')) }
        )
        for (change in changes) {
            val process = PendingProcess()
            val platform = Platform(process)
            val proof = capture(process, platform)
            platform.detach()
            assertFalse(proof.prepareForRequest(change(platform.helloIdentity())))
            assertSame(platform.originalReference, platform.registry[100])
            assertSame(process, platform.originalReference.get())
            proof.destroyUnaccepted() // Rejected HELLO still owns cleanup of the original child.
            assertEquals(1, platform.kills)
        }
    }

    @Test fun replacingEvenTheSameProcessReferenceBeforeTransferFailsClosed() {
        val process = PendingProcess()
        val platform = Platform(process)
        val proof = capture(process, platform)
        val replacementReference = WeakReference(process)
        platform.registry[100] = replacementReference
        platform.detach()
        assertFalse(proof.prepareForRequest(platform.helloIdentity()))
        proof.destroyUnaccepted()
        assertSame(replacementReference, platform.registry[100])
        assertSame(process, platform.originalReference.get())
        assertEquals(0, platform.kills)
    }

    @Test fun unavailableCaptureObservationRetainsAProofForLaterCleanup() {
        val process = PendingProcess()
        val platform = Platform(process)
        val original = platform.current
        platform.current = null
        val proof = capture(process, platform)
        assertFalse(proof.confirmExited())
        proof.destroyUnaccepted()
        assertEquals(0, process.destroys)
        assertEquals(0, platform.kills)
        platform.current = original
        platform.detachAndExit()
        assertTrue(proof.confirmExited())
        assertEquals(1, platform.waits)
    }

    @Test fun captureObservationExceptionCannotRebindToAReplacementRegistration() {
        val process = PendingProcess()
        val platform = Platform(process).apply { observeFailure = IOException("stat unreadable") }
        val proof = capture(process, platform)
        platform.observeFailure = null
        // Even a new reference to the same Java object cannot replace the originally captured one.
        val replacement = WeakReference(process)
        platform.registry[100] = replacement
        platform.detach()
        assertFalse(proof.prepareForRequest(platform.helloIdentity()))
        proof.destroyUnaccepted()
        platform.detachAndExit()
        assertFalse(proof.confirmExited())
        assertSame(replacement, platform.registry[100])
        assertEquals(0, platform.kills)
        assertEquals(0, process.destroys)
        assertEquals(0, platform.waits)
    }

    @Test fun captureObservationExceptionWithOriginalRegistrationCanRecoverDuringCleanup() {
        val process = PendingProcess()
        val platform = Platform(process).apply { observeFailure = IOException("stat unreadable") }
        val proof = capture(process, platform)
        platform.observeFailure = null
        platform.detach()
        proof.destroyUnaccepted()
        assertEquals(1, platform.kills)
        platform.detachAndExit()
        assertTrue(proof.confirmExited())
        assertEquals(1, platform.waits)
    }

    @Test fun captureIdentityFailureCannotAuthorizeTransferSignalOrWait() {
        val changes: List<(ChildProcessObservation) -> ChildProcessObservation> = listOf(
            { it.copy(uid = 0) },
            { it.copy(stat = it.stat.copy(parentPid = 99)) },
            { it.copy(stat = it.stat.copy(startTimeTicks = 0)) }
        )
        for (change in changes) {
            val process = PendingProcess()
            val platform = Platform(process).apply { detach() }
            platform.current = change(platform.current!!)
            val proof = capture(process, platform)
            assertFalse(proof.prepareForRequest(platform.helloIdentity()))
            proof.destroyUnaccepted()
            assertFalse(proof.confirmExited())
            assertSame(platform.originalReference, platform.registry[100])
            assertEquals(0, platform.kills)
            assertEquals(0, process.destroys)
            assertEquals(0, platform.waits)
        }
    }

    @Test fun captureKeepsOwnershipWhenRemoveFailsBeforeOrAfterMutation() {
        for (failure in listOf(RemoveFailure.BEFORE, RemoveFailure.AFTER)) {
            val process = PendingProcess()
            val platform = Platform(process).apply { detach(); registry.failure = failure }
            val proof = capture(process, platform)
            assertNull(platform.originalReference.get())
            assertEquals(failure == RemoveFailure.BEFORE, platform.registry.containsKey(100))
            platform.registry.failure = null
            assertTrue(proof.prepareForRequest(platform.helloIdentity()))
            assertTrue(platform.registry.isEmpty())
            platform.detachAndExit()
            assertTrue(proof.confirmExited())
            assertEquals(1, platform.waits)
        }
    }

    @Test fun handshakeTransferFailureRetainsOwnershipForCancellationCleanup() {
        val process = PendingProcess()
        val platform = Platform(process)
        val proof = capture(process, platform)
        platform.detach()
        platform.registry.failure = RemoveFailure.AFTER
        assertThrows(IOException::class.java) { proof.prepareForRequest(platform.helloIdentity()) }
        assertNull(platform.originalReference.get())
        platform.registry.failure = null
        proof.destroyUnaccepted()
        assertEquals(1, platform.kills)
        platform.detachAndExit()
        assertTrue(proof.confirmExited())
    }

    @Test fun originalJavaExitWinsWithoutTouchingAReusedPid() {
        val process = PendingProcess()
        val platform = Platform(process)
        val proof = capture(process, platform)
        process.exit = 0
        val replacement = WeakReference(PendingProcess())
        platform.registry[100] = replacement
        platform.current = null
        assertTrue(proof.confirmExited())
        proof.destroyUnaccepted()
        assertSame(replacement, platform.registry[100])
        assertEquals(0, platform.waits)
        assertEquals(0, platform.kills)
    }

    @Test fun publicationRetryUsesOriginalObjectAfterPidReuseAndNeverRepeatsWaitOrSignal() {
        val process = PendingProcess()
        val platform = Platform(process)
        val proof = capture(process, platform)
        platform.detachAndExit()
        platform.failPublication = true
        assertThrows(IOException::class.java) { proof.confirmExited() }
        val replacement = PendingProcess()
        val replacementReference = WeakReference(replacement)
        platform.registry[100] = replacementReference
        platform.current = platform.current!!.copy(stat = platform.current!!.stat.copy(startTimeTicks = 9999))
        proof.destroyUnaccepted()
        assertEquals(0, platform.kills)
        assertEquals(0, process.destroys)
        platform.failPublication = false
        assertTrue(proof.confirmExited())
        assertEquals(1, platform.waits)
        assertEquals(9, process.exitValue())
        assertNull(replacement.exit)
        assertSame(replacementReference, platform.registry[100])
        assertNull(platform.originalReference.get())
    }

    private fun capture(process: Process, platform: Platform) =
        DetachedChildExitProof.capture(process, 100, 10061, 20, platform)

    private class PendingProcess : Process() {
        var exit: Int? = null
        var destroys = 0
        var onDestroy: () -> Unit = {}
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = exitValue()
        override fun exitValue(): Int = exit ?: throw IllegalThreadStateException()
        override fun destroy() { destroys++; onDestroy() }
    }

    private enum class RemoveFailure { BEFORE, AFTER }
    private class Registry : HashMap<Int, Reference<*>>() {
        var failure: RemoveFailure? = null
        override fun remove(key: Int): Reference<*>? {
            assertTrue(Thread.holdsLock(this))
            if (failure == RemoveFailure.BEFORE) throw IOException("remove failed before mutation")
            val removed = super.remove(key)
            if (failure == RemoveFailure.AFTER) throw IOException("remove failed after mutation")
            return removed
        }
    }

    private class Platform(private val original: PendingProcess) : DetachedChildReaper {
        val originalReference = WeakReference(original)
        override val registry = Registry().apply { put(100, originalReference) }
        var current: ChildProcessObservation? = ChildProcessObservation(
            10061, HelperSessionIdentity.Snapshot(100, 50, 50, 1234, 'S', 20)
        )
        var waitResult = NativeChildWait(100, 9)
        var observeFailure: IOException? = null
        var waitFailure: IOException? = null
        var failPublication = false
        var waits = 0
        var publications = 0
        var kills = 0
        fun detach() { current = current!!.copy(stat = current!!.stat.copy(processGroupId = 100, sessionId = 100)) }
        fun detachAndExit() { detach(); current = current!!.copy(stat = current!!.stat.copy(state = 'Z')) }
        fun helloIdentity() = current!!.let { ApplicationProcessIdentity(it.uid, it.stat) }
        override fun observe(pid: Int): ChildProcessObservation? {
            assertTrue(Thread.holdsLock(registry))
            assertEquals(100, pid)
            observeFailure?.let { throw it }
            return current
        }
        override fun kill(pid: Int) {
            assertTrue(Thread.holdsLock(registry))
            assertEquals(100, pid)
            assertFalse(registry.containsKey(pid))
            assertNull(originalReference.get())
            assertTrue(current!!.stat.ownsSession)
            assertTrue(current!!.stat.isLive)
            kills++
        }
        override fun waitNoHang(pid: Int): NativeChildWait {
            assertTrue(Thread.holdsLock(registry))
            assertEquals(100, pid)
            assertFalse(registry.containsKey(pid))
            assertNull(originalReference.get())
            waits++
            waitFailure?.let { throw it }
            return waitResult
        }
        override fun publishExit(process: Process, exitCode: Int) {
            assertTrue(Thread.holdsLock(registry))
            assertSame(original, process)
            if (failPublication) throw IOException("publication failed")
            publications++
            (process as PendingProcess).exit = exitCode
        }
    }
}
