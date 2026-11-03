package com.nezhahq.agent.mcp

import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.mcp.helper.HelperSessionIdentity
import com.nezhahq.agent.mcp.privileged.PrivilegedGateway
import com.nezhahq.agent.mcp.privileged.PrivilegedHelperAuthority
import com.nezhahq.agent.mcp.privileged.PrivilegedHelperLauncher
import com.nezhahq.agent.mcp.privileged.PrivilegedHelperLease
import com.nezhahq.agent.mcp.privileged.PrivilegedService
import com.nezhahq.agent.mcp.privileged.TrustedHelperProgram
import com.nezhahq.agent.mcp.transport.ApplicationHelperLease
import com.nezhahq.agent.mcp.transport.ApplicationProcessControl
import com.nezhahq.agent.mcp.transport.ApplicationProcessIdentity
import com.nezhahq.agent.mcp.transport.HelperBackend
import com.nezhahq.agent.mcp.transport.HelperBlockingPool
import com.nezhahq.agent.mcp.transport.HelperCall
import com.nezhahq.agent.mcp.transport.HelperDeadline
import com.nezhahq.agent.mcp.transport.HelperFailure
import com.nezhahq.agent.mcp.transport.HelperLaunchSpec
import com.nezhahq.agent.mcp.transport.HelperLauncher
import com.nezhahq.agent.mcp.transport.HelperLifecycleOwner
import com.nezhahq.agent.mcp.transport.HelperProcessCleanup
import com.nezhahq.agent.mcp.transport.HelperProcessLease
import com.nezhahq.agent.mcp.transport.HelperProtocol
import com.nezhahq.agent.mcp.transport.HelperShutdownPendingException
import com.nezhahq.agent.mcp.transport.McpHelperTransport
import com.nezhahq.agent.mcp.transport.hasPendingHelperShutdown
import com.nezhahq.agent.service.AgentRuntimeController
import com.nezhahq.agent.service.AgentRuntimeFactory
import com.nezhahq.agent.service.AgentRuntimeHandle
import com.nezhahq.agent.service.config
import com.nezhahq.agent.util.ShellBackendPolicy
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperLifecycleOwnerTest {
    @Test
    fun lateNativeLaunchAndRetainedExitProofBlockReplacementAfterSupervisorHasJoined() = runBlocking {
        val owner = HelperLifecycleOwner()
        val cleanup = HelperProcessCleanup(1)
        val pool = HelperBlockingPool("test-late-helper", 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val process = ControlledProcess()
        val fixture = RuntimeFixture(owner) {
            val lease = pool.run(owner, abandoned = { it.close() }) {
                ApplicationHelperLease.start(10213, ApplicationControl, cleanup, owner) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    process
                }
            }
            try { awaitCancellation() } finally { lease.close() }
        }

        try {
            fixture.start()
            entered.awaitChecked()
            assertPending(fixture)
            assertTrue(fixture.supervisor.isCompleted)
            assertEquals(1, fixture.creates.get())

            release.countDown()
            process.controlClosed.awaitChecked()
            assertPending(fixture)
            assertEquals(1, cleanup.retainedProcesses)

            process.exit = 0
            fixture.stopWaitMillis = 2_000
            fixture.controller.reload(config("replacement"))
            assertEquals(2, fixture.creates.get())
            assertEquals(0, cleanup.retainedProcesses)
        } finally {
            release.countDown()
            process.exit = 0
            fixture.close()
        }
    }

    @Test
    fun queuedWorkCannotStartAfterShutdownAndUnrelatedWorkerDoesNotBlockTheOwner() = runBlocking {
        val pool = HelperBlockingPool("test-queued-helper", 1)
        val unrelated = HelperLifecycleOwner()
        val owner = HelperLifecycleOwner()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val nativeStarts = AtomicInteger()
        pool.discard(unrelated) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        entered.awaitChecked()
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            pool.run(owner) { nativeStarts.incrementAndGet() }
        }

        try {
            owner.close(100)
            withTimeout(2_000) { queued.join() }
            assertTrue(queued.isCancelled)
            assertEquals(1L, release.count)
            release.countDown()
            withTimeout(2_000) { pool.run { Unit } }
            assertEquals(0, nativeStarts.get())
        } finally {
            release.countDown()
            queued.cancelAndJoin()
            unrelated.close(2_000)
        }
    }

    @Test
    fun aWorkerDequeuedDuringShutdownCannotStartWhileEarlierDiscardCallbackIsStillRunning() = runBlocking {
        val pool = HelperBlockingPool("test-closing-admission", 1)
        val owner = HelperLifecycleOwner()
        val unrelated = HelperLifecycleOwner()
        val unrelatedEntered = CountDownLatch(1)
        val releaseUnrelated = CountDownLatch(1)
        val discardEntered = CountDownLatch(1)
        val releaseDiscard = CountDownLatch(1)
        owner.registerActivity {
            discardEntered.countDown()
            check(releaseDiscard.await(5, TimeUnit.SECONDS))
        }
        pool.discard(unrelated) {
            unrelatedEntered.countDown()
            check(releaseUnrelated.await(5, TimeUnit.SECONDS))
        }
        unrelatedEntered.awaitChecked()
        val nativeStarts = AtomicInteger()
        val queued = async(start = CoroutineStart.UNDISPATCHED) { pool.run(owner) { nativeStarts.incrementAndGet() } }
        val shutdown = async(Dispatchers.IO) { owner.beginShutdown() }

        try {
            discardEntered.awaitChecked()
            releaseUnrelated.countDown()
            withTimeout(2_000) {
                // Queue capacity stays fixed; retry marker admission until the old queued task is
                // dequeued. Marker completion proves that task passed its start gate already.
                while (true) {
                    try { pool.run { Unit }; break }
                    catch (_: HelperFailure) { delay(1) }
                }
            }
            assertEquals(0, nativeStarts.get())
            assertFalse(shutdown.isCompleted)
            releaseDiscard.countDown()
            withTimeout(2_000) { shutdown.await(); queued.join() }
            owner.close(100)
            assertTrue(queued.isCancelled)
        } finally {
            releaseUnrelated.countDown()
            releaseDiscard.countDown()
            shutdown.cancelAndJoin()
            queued.cancelAndJoin()
            owner.close(2_000)
            unrelated.close(2_000)
        }
    }

    @Test
    fun shutdownDuringAuthorityAcquisitionRejectsLateTicketBeforeAnyNativeLaunch() = runBlocking {
        val owner = HelperLifecycleOwner()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val gateway = RootGateway().apply {
            discover = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        val authority = PrivilegedHelperAuthority(gateway, ShellBackendPolicy()).apply { configure(true) }
        val launcher = PrivilegedHelperLauncher("/data/app/test/base.apk", emptyMap(), authority, gateway, owner)
        val fixture = RuntimeFixture(owner) { launcher.launch(HelperLaunchSpec(ByteArray(32), 5_000)).close() }

        try {
            fixture.start()
            entered.awaitChecked()
            assertPending(fixture)
            assertTrue(fixture.supervisor.isCompleted)
            assertEquals(0, gateway.nativeStarts.get())

            release.countDown()
            fixture.stopWaitMillis = 2_000
            fixture.controller.reload(config("replacement"))
            assertEquals(0, gateway.nativeStarts.get())
            assertEquals(2, fixture.creates.get())
        } finally {
            release.countDown()
            fixture.close()
            authority.close()
        }
    }

    @Test
    fun deliveredBusinessLeaseAndBlockedProtocolWriterOutliveCoroutineCancellation() = runBlocking {
        val owner = HelperLifecycleOwner()
        val cleanup = HelperProcessCleanup(1)
        val pool = HelperBlockingPool("test-business-helper", 1)
        val writing = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val raw = object : OutputStream() {
            override fun write(value: Int) {
                writing.countDown()
                check(releaseWriter.await(5, TimeUnit.SECONDS))
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) = write(0)
        }
        val process = ControlledProcess(BufferedOutputStream(raw, 8))
        val launcher = object : HelperLauncher {
            override val backend = HelperBackend.APPLICATION
            override suspend fun launch(spec: HelperLaunchSpec): HelperProcessLease =
                pool.run(owner, abandoned = { it.close() }) {
                    process.output = ByteArrayInputStream(ByteArrayOutputStream().also { output ->
                        HelperProtocol.Writer(output).frame(HelperProtocol.Kind.HELLO, HelperProtocol.encodeHello(
                            HelperProtocol.Hello(HelperProtocol.VERSION, spec.nonce, 10213, 10213, 123, 123, 100)
                        ))
                    }.toByteArray())
                    ApplicationHelperLease.start(10213, ApplicationControl, cleanup, owner) { process }
                }
        }
        val fixture = RuntimeFixture(owner) {
            McpHelperTransport(owner).exchange(
                launcher, HelperCall(1, 15, "{}"), HelperDeadline.afterMillis(5_000)
            ) { AutoCloseable {} }
        }

        try {
            fixture.start()
            writing.awaitChecked()
            assertPending(fixture)
            assertTrue(fixture.supervisor.isCompleted)
            assertEquals(1, fixture.creates.get())
            assertEquals(0, process.destroys.get())

            releaseWriter.countDown()
            process.controlClosed.awaitChecked()
            assertPending(fixture)
            assertEquals(1, cleanup.retainedProcesses)

            process.exit = 0
            fixture.stopWaitMillis = 2_000
            fixture.controller.reload(config("replacement"))
            assertEquals(2, fixture.creates.get())
            assertEquals(0, cleanup.retainedProcesses)
        } finally {
            releaseWriter.countDown()
            process.exit = 0
            fixture.close()
        }
    }

    @Test
    fun processExitAndPipeCloseProofCannotHideAStillRunningDiagnosticReader() = runBlocking {
        val owner = HelperLifecycleOwner()
        val cleanup = HelperProcessCleanup(1)
        val pool = HelperBlockingPool("test-owned-diagnostic", 1)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val process = ControlledProcess().apply {
            diagnostic = object : InputStream() {
                override fun read(): Int {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    return -1
                }
                override fun close() = Unit // A broken/native pipe may not unblock its reader.
            }
        }
        val lease = ApplicationHelperLease.start(10213, ApplicationControl, cleanup, owner) { process }
        pool.discard(owner) { lease.stderr.read() }

        try {
            entered.awaitChecked()
            process.exit = 0
            lease.close()
            withTimeout(2_000) { while (!lease.cleanupComplete) delay(1) }
            assertEquals(0, cleanup.retainedProcesses)
            val failure = runCatching { owner.close(50) }.exceptionOrNull()
            assertTrue(failure is HelperShutdownPendingException)

            release.countDown()
            owner.close(2_000)
        } finally {
            release.countDown()
            process.exit = 0
            lease.close()
            owner.close(2_000)
        }
    }

    @Test
    fun failedCapturedPrivilegedPipeIsRetriedBeforeExitProofCanReleaseTheRuntime() = runBlocking {
        val owner = HelperLifecycleOwner()
        val cleanup = HelperProcessCleanup(1)
        val pool = HelperBlockingPool("test-privileged-pipe", 1)
        val closeAttempts = AtomicInteger()
        val retryEntered = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val control = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun close() {
                if (closeAttempts.incrementAndGet() == 1) throw IOException("transient pipe close failure")
                retryEntered.countDown()
                check(allowClose.await(5, TimeUnit.SECONDS))
            }
        }
        val process = ControlledProcess(control)
        val authority = PrivilegedHelperAuthority(RootGateway(), ShellBackendPolicy()).apply { configure(true) }
        val delivered = CompletableDeferred<Unit>()
        val fixture = RuntimeFixture(owner) {
            val lease = pool.run(owner, abandoned = { it.close() }) {
                PrivilegedHelperLease.start(
                    authority.acquire(),
                    TrustedHelperProgram("/data/app/test/base.apk", emptyMap(), HelperLaunchSpec(ByteArray(32), 5_000)),
                    cleanup, owner
                ) { process }
            }
            lease.acceptIdentity(HelperProtocol.Hello(HelperProtocol.VERSION, ByteArray(32), 0, 0, 123, 123, 100))
            delivered.complete(Unit)
            try { awaitCancellation() } finally { lease.close() }
        }

        try {
            fixture.start()
            withTimeout(2_000) { delivered.await() }
            process.exit = 0
            assertPending(fixture)
            retryEntered.awaitChecked()
            assertEquals(1, cleanup.retainedProcesses)
            assertEquals(1, fixture.creates.get())

            allowClose.countDown()
            fixture.stopWaitMillis = 2_000
            fixture.controller.reload(config("replacement"))
            assertEquals(2, closeAttempts.get())
            assertEquals(listOf(1, 1, 1), process.getters.map { it.get() })
            assertEquals(1, process.outputCloses.get())
            assertEquals(1, process.diagnosticCloses.get())
            assertEquals(2, fixture.creates.get())
        } finally {
            allowClose.countDown()
            process.exit = 0
            fixture.close()
            authority.close()
        }
    }

    @Test
    fun helperPendingClassificationHandlesCombinedFailuresCyclesAndDeepCauses() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)
        assertFalse(first.hasPendingHelperShutdown())
        second.addSuppressed(HelperShutdownPendingException())
        assertTrue(first.hasPendingHelperShutdown())
        var deep: Throwable = IllegalStateException("leaf")
        repeat(1_000) { deep = IllegalStateException("wrapper", deep) }
        assertFalse(deep.hasPendingHelperShutdown())
    }

    private suspend fun assertPending(fixture: RuntimeFixture) {
        val failure = runCatching { fixture.controller.reload(config("replacement")) }.exceptionOrNull()
        assertTrue("Expected helper ownership to block replacement: $failure", failure is HelperShutdownPendingException)
        assertFalse(fixture.controller.isRunning)
    }

    private class RuntimeFixture(private val owner: HelperLifecycleOwner, work: suspend () -> Unit) {
        val supervisor = SupervisorJob()
        private val scope = CoroutineScope(Dispatchers.Default + supervisor)
        val creates = AtomicInteger()
        var stopWaitMillis = 50L
        val controller = AgentRuntimeController(AgentRuntimeFactory {
            if (creates.incrementAndGet() == 1) Runtime(
                onStart = { scope.launch { work() }; Unit },
                onStop = {
                    owner.beginShutdown()
                    supervisor.cancelAndJoin()
                    owner.close(stopWaitMillis)
                }
            ) else Runtime()
        })

        suspend fun start() = controller.reload(config("original"))

        suspend fun close() {
            stopWaitMillis = 2_000
            controller.stop()
            supervisor.cancelAndJoin()
        }
    }

    private class Runtime(
        private val onStart: suspend () -> Unit = {},
        private val onStop: suspend () -> Unit = {}
    ) : AgentRuntimeHandle {
        override suspend fun start() = onStart()
        override suspend fun stop() = onStop()
        override suspend fun updateCapabilities(capabilities: RemoteCapabilities) = Unit
    }

    private class RootGateway : PrivilegedGateway {
        var discover: () -> Unit = {}
        val nativeStarts = AtomicInteger()
        override fun service(): PrivilegedService? { discover(); return null }
        override fun serviceToken(): Any? = null
        override fun executableSu() = "/system/xbin/su"
        override fun launchSu(path: String, command: String, environment: Map<String, String>, directory: String): Process {
            nativeStarts.incrementAndGet()
            error("Late native admission must be rejected")
        }
    }

    private class ControlledProcess(private val control: OutputStream = ByteArrayOutputStream()) : Process() {
        @Volatile var exit: Int? = null
        var output: InputStream = ByteArrayInputStream(ByteArray(0))
        var diagnostic: InputStream = ByteArrayInputStream(ByteArray(0))
        val controlClosed = CountDownLatch(1)
        val destroys = AtomicInteger()
        val getters = Array(3) { AtomicInteger() }
        val outputCloses = AtomicInteger()
        val diagnosticCloses = AtomicInteger()
        private val controlPipe = object : OutputStream() {
            override fun write(value: Int) = control.write(value)
            override fun write(bytes: ByteArray, offset: Int, length: Int) = control.write(bytes, offset, length)
            override fun flush() = control.flush()
            override fun close() { control.close(); controlClosed.countDown() }
        }
        private val outputPipe = object : InputStream() {
            override fun read() = output.read()
            override fun read(bytes: ByteArray, offset: Int, length: Int) = output.read(bytes, offset, length)
            override fun close() { outputCloses.incrementAndGet(); output.close() }
        }
        private val diagnosticPipe = object : InputStream() {
            override fun read() = diagnostic.read()
            override fun read(bytes: ByteArray, offset: Int, length: Int) = diagnostic.read(bytes, offset, length)
            override fun close() { diagnosticCloses.incrementAndGet(); diagnostic.close() }
        }
        override fun getOutputStream(): OutputStream = controlPipe.also { getters[0].incrementAndGet() }
        override fun getInputStream(): InputStream = outputPipe.also { getters[1].incrementAndGet() }
        override fun getErrorStream(): InputStream = diagnosticPipe.also { getters[2].incrementAndGet() }
        override fun exitValue(): Int = exit ?: throw IllegalThreadStateException()
        override fun waitFor(): Int = exitValue()
        override fun destroy() { destroys.incrementAndGet() }
    }

    private object ApplicationControl : ApplicationProcessControl {
        override fun identity(pid: Int) = ApplicationProcessIdentity(
            10213, HelperSessionIdentity.Snapshot(123, 123, 123, 100, 'S')
        )
        override fun killGroup(pid: Int) = Unit
    }

    private suspend fun CountDownLatch.awaitChecked() = withContext(Dispatchers.IO) {
        assertTrue("Controlled native operation did not reach the expected point", await(2, TimeUnit.SECONDS))
    }
}
