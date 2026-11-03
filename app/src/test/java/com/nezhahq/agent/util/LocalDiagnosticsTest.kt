package com.nezhahq.agent.util

import com.nezhahq.agent.SilentLoggerRule
import io.grpc.Status
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()
    @get:Rule val logs = SilentLoggerRule()

    @Test
    fun diagnosticsAreOptInLocalAndRemovedWhenDisabled() = withRecorder { recorder, directory ->
        val failure = IOException("raw-private-body-8542").apply {
            stackTrace = arrayOf(StackTraceElement("com.nezhahq.agent.Connection", "onlyLocalFrame", "/private/source-8542", 17))
        }
        Logger.e("诊断测试：尚未开启", failure)
        recorder.awaitIdle()
        assertFalse(recorder.state.value.enabled)
        assertTrue(logFiles(directory).isEmpty())

        recorder.setEnabled(true)
        Logger.e("诊断测试：连接失败", failure)
        Logger.i("诊断测试：受限上下文 https://diagnostic-private-8542.example?token=credential-8542")
        recorder.awaitIdle()

        val files = logFiles(directory)
        assertTrue(files.isNotEmpty())
        assertTrue(files.all { it.parentFile == directory })
        val contents = files.joinToString { it.readText() }
        assertTrue(contents.contains("onlyLocalFrame:17"))
        assertFalse(contents.contains("raw-private-body-8542"))
        assertFalse(contents.contains("/private/source-8542"))
        assertFalse(contents.contains("diagnostic-private-8542"))
        assertFalse(contents.contains("credential-8542"))
        assertFalse(Logger.getLogString().contains("onlyLocalFrame"))
        assertFalse(Logger.logs.value.any { "onlyLocalFrame" in it.text })
        assertFalse(logs.messages.any { "onlyLocalFrame" in it })

        recorder.setEnabled(false)
        Logger.e("诊断测试：已经关闭", failure)
        recorder.awaitIdle()
        assertTrue(logFiles(directory).isEmpty())
        assertFalse(recorder.state.value.cleaning)
    }

    @Test
    fun processStartsDisabledAndCleansThePreviousProcessFilesOffTheCallingThread() {
        val directory = temporary.newFolder()
        File(directory, "diagnostic-123-0.log").writeText("previous-process-record")
        val callerThread = Thread.currentThread()
        var providerThread: Thread? = null
        withRecorder(directory, provider = { providerThread = Thread.currentThread(); directory }) { recorder, _ ->
            assertFalse(recorder.state.value.enabled)
            assertTrue(logFiles(directory).isEmpty())
            assertNotNull(providerThread)
            assertTrue(providerThread !== callerThread)
        }
    }

    @Test
    fun contextGuardAlsoProtectsOptInFilesWhilePreservingBusinessDescriptionAndStatusCode() = withRecorder { recorder, directory ->
        recorder.setEnabled(true)
        val cases = sensitiveLogContextCases("8542")
        cases.forEach { (input, _) -> Logger.i("诊断测试：受限上下文 $input") }
        val stageDescription = "GpuCollector: P1.5 动态扫描命中"
        Logger.i(stageDescription)
        val description = "诊断测试：Root 或 Shizuku 指标读取失败"
        Logger.e(description, Status.UNAVAILABLE.withDescription("private-status-body-8542").asRuntimeException())
        recorder.awaitIdle()

        val contents = logFiles(directory).joinToString { it.readText() }
        val outputs = listOf(Logger.logs.value.joinToString("\n") { it.text }, Logger.getLogString(), logs.messages.joinToString("\n"), contents)
        outputs.forEach { output ->
            cases.forEach { (_, marker) -> assertFalse("output leaked $marker", marker in output) }
            assertFalse(output.contains("private-status-body-8542"))
            assertTrue(output.contains(stageDescription))
            assertTrue(output.contains(description))
            assertTrue(output.contains("Error=StatusRuntimeException, Code=UNAVAILABLE"))
        }
        assertTrue(contents.contains("诊断测试：受限上下文"))
    }

    @Test
    fun disableAndReenableCleanInFlightAndFullQueuedOldGenerations() {
        val directory = BlockingDirectory(temporary.newFolder())
        withRecorder(directory, limits = DiagnosticLimits(maxPending = 2)) { recorder, _ ->
            recorder.setEnabled(true)
            recorder.awaitIdle()
            directory.blockNextList.set(true)
            try {
                Logger.i("诊断测试：old-generation-in-flight")
                assertTrue(directory.blocked.await(2, TimeUnit.SECONDS))
                repeat(20) { Logger.i("诊断测试：old-generation-queued-$it") }
                recorder.setEnabled(false)
                recorder.setEnabled(true)
                Logger.i("诊断测试：new-generation-record")
            } finally { directory.release.countDown() }
            recorder.awaitIdle()

            val content = logFiles(directory).joinToString { it.readText() }
            assertFalse(content.contains("old-generation"))
            assertTrue(content.contains("new-generation-record"))
            assertTrue(recorder.state.value.enabled)
        }
    }

    @Test
    fun disabledCleanupCannotBeDroppedByAFullQueue() {
        val directory = BlockingDirectory(temporary.newFolder())
        withRecorder(directory, limits = DiagnosticLimits(maxPending = 1)) { recorder, _ ->
            recorder.setEnabled(true)
            recorder.awaitIdle()
            directory.blockNextList.set(true)
            try {
                Logger.i("诊断测试：old-full-queue")
                assertTrue(directory.blocked.await(2, TimeUnit.SECONDS))
                repeat(20) { Logger.i("诊断测试：queued-$it") }
                recorder.setEnabled(false)
            } finally { directory.release.countDown() }
            recorder.awaitIdle()
            assertTrue(logFiles(directory).isEmpty())
            assertFalse(recorder.state.value.enabled)
            assertFalse(recorder.state.value.cleaning)
        }
    }

    @Test
    fun exceptionCyclesSuppressedTreesAndStacksAreBoundedWithoutRawMessages() = withRecorder { recorder, directory ->
        val first = IOException("first-private-8542")
        val second = IllegalArgumentException("second-private-8542")
        second.stackTrace = emptyArray()
        first.initCause(second)
        second.initCause(first)
        second.addSuppressed(first)
        repeat(40) { first.addSuppressed(IllegalStateException("suppressed-private-8542-$it").apply { stackTrace = emptyArray() }) }
        first.stackTrace = Array(100) { StackTraceElement("com.nezhahq.agent.Connection", "boundedFrame", "/source-private-8542", it) }

        recorder.setEnabled(true)
        Logger.e("诊断测试：异常环", first)
        recorder.awaitIdle()
        val contents = logFiles(directory).joinToString { it.readText() }
        assertTrue(contents.contains("IOException"))
        assertTrue(contents.contains("suppressed="))
        assertTrue(Regex("at com\\.nezhahq\\.agent\\.Connection#boundedFrame").findAll(contents).count() <= 12)
        assertFalse(contents.contains("private-8542"))
        assertFalse(Logger.getLogString().contains("private-8542"))
        assertFalse(logs.messages.any { "private-8542" in it })
        assertTrue(logFiles(directory).all { file -> file.readLines().all { it.toByteArray().size + 1 <= 2_048 } })
    }

    @Test
    fun fileRotationEnforcesUtf8LineFileCountAndTotalLimits() {
        val directory = temporary.newFolder()
        val limits = DiagnosticLimits(maxLineBytes = 80, maxFileBytes = 160, maxTotalBytes = 250, maxFiles = 2)
        val store = DiagnosticFileStore(directory, limits)
        repeat(20) { index -> store.append(10_000 + index.toLong(), "entry-$index " + "诊断".repeat(100), 10_000 + index.toLong()) }
        val files = logFiles(directory)
        assertTrue(files.isNotEmpty())
        assertTrue(files.size <= 2)
        assertTrue(files.sumOf { it.length() } <= 250)
        assertTrue(files.all { it.length() <= 160 })
        assertTrue(files.all { file -> file.readLines().all { it.toByteArray().size + 1 <= 80 && '\uFFFD' !in it } })
        assertTrue(files.any { it.readText().contains("entry-19") })
        assertFalse(files.any { it.readText().contains("entry-0 ") })
    }

    @Test
    fun activeFileExpiresFromItsStartTimeEvenWhenRecentAppendsChangeMtime() {
        val directory = temporary.newFolder()
        val store = DiagnosticFileStore(directory, DiagnosticLimits(retentionMillis = 1_000))
        store.append(10_000, "old-record", 10_000)
        store.append(10_900, "recent-record", 10_900)
        assertEquals(1, logFiles(directory).size)
        assertTrue(logFiles(directory).single().setLastModified(System.currentTimeMillis()))
        store.expire(11_000)
        assertTrue(logFiles(directory).isEmpty())
        store.append(11_001, "fresh-record", 11_001)
        assertEquals("fresh-record\n", logFiles(directory).single().readText())
    }

    @Test
    fun idleDiagnosticsExpireWithoutWaitingForAnotherRecord() {
        val now = AtomicLong(10_000)
        withRecorder(limits = DiagnosticLimits(retentionMillis = 50), clock = now::get) { recorder, directory ->
            recorder.setEnabled(true)
            Logger.i("诊断测试：等待到期")
            recorder.awaitIdle()
            assertTrue(logFiles(directory).isNotEmpty())
            now.set(10_050)
            withTimeout(2_000) {
                while (logFiles(directory).isNotEmpty()) delay(10)
            }
            assertTrue(recorder.state.value.enabled)
        }
    }

    @Test
    fun writeFailureDisablesDiagnosticsAndCannotFailBusinessLogging() = withRecorder { recorder, directory ->
        recorder.setEnabled(true)
        recorder.awaitIdle()
        assertTrue(directory.delete())
        directory.writeText("not-a-directory")
        Logger.e("诊断测试：业务照常完成", IOException("private-disk-failure-8542"))
        recorder.awaitIdle()
        assertFalse(recorder.state.value.enabled)
        assertNotNull(recorder.state.value.error)
        assertTrue(Logger.getLogString().contains("业务照常完成"))
        assertFalse(recorder.state.value.error!!.contains("private-disk-failure-8542"))
    }

    private fun withRecorder(
        directory: File = temporary.newFolder(),
        limits: DiagnosticLimits = DiagnosticLimits(),
        provider: () -> File = { directory },
        clock: () -> Long = System::currentTimeMillis,
        block: suspend (LocalDiagnostics, File) -> Unit
    ) = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val recorder = LocalDiagnostics(provider, dispatcher = dispatcher, clock = clock, limits = limits)
        val previous = Logger.diagnostics
        Logger.diagnostics = recorder
        try {
            recorder.awaitIdle()
            block(recorder, directory)
        } finally {
            Logger.diagnostics = previous
            try { recorder.shutdown() } finally { dispatcher.close() }
        }
    }

    private fun logFiles(directory: File): List<File> = directory.listFiles()?.filter { it.name.endsWith(".log") }.orEmpty()

    private class BlockingDirectory(directory: File) : File(directory.absolutePath) {
        val blockNextList = AtomicBoolean(false)
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun listFiles(): Array<File>? {
            if (blockNextList.compareAndSet(true, false)) {
                blocked.countDown()
                check(release.await(3, TimeUnit.SECONDS)) { "test did not release diagnostic IO" }
            }
            return super.listFiles()
        }
    }
}
