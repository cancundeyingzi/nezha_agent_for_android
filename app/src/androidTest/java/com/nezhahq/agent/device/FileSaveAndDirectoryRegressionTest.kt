package com.nezhahq.agent.device

import android.content.ContextWrapper
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import com.nezhahq.agent.executor.FileManager
import com.nezhahq.agent.util.ShellCommandResult
import com.nezhahq.agent.util.executeIsolatedShellCommand
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import proto.Nezha.IOStreamData

@RunWith(AndroidJUnit4::class)
class FileSaveAndDirectoryRegressionTest {
    @get:Rule val evidence = DeviceEvidenceRule()

    @Test fun realShellExitStatusAndTimeoutAreReportedOnEverySupportedApi() = runBlocking {
        val empty = shell("true", 5_000)
        assertTrue(empty.successful)
        assertEquals("", empty.output)
        val failed = shell("printf synthetic; exit 7", 5_000)
        assertFalse(failed.successful)
        assertEquals(7, failed.exitCode)
        assertEquals("synthetic", failed.output)
        val timeout = withTimeout(5_000) { shell("while :; do :; done", 150) }
        assertTrue(timeout.timedOut)
        assertFalse(timeout.successful)
    }

    @Test fun realCopyFailureAgainstSameLengthReadOnlyTargetNeverReportsUploadComplete() = runBlocking {
        val directory = newDirectory()
        val target = File(directory, "target.bin").apply { writeText("old") }
        Os.chmod(target.path, 256) // 0400: owner read only; both files still have length 3.
        try {
            val header = byteArrayOf(2) + ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(3).array() +
                target.path.toByteArray()
            val response = exchange(directory, listOf(header, "new".toByteArray()), shellMode = true,
                copy = { _, _ -> throw IOException("exercise the authorized shell fallback") })
            assertTrue(response.toString(Charsets.UTF_8), response.toString(Charsets.UTF_8).startsWith("NERR"))
            assertTrue(response.toString(Charsets.UTF_8).contains("复制文件失败"))
            assertEquals("old", target.readText())
        } finally {
            Os.chmod(target.path, 384)
            assertTrue(directory.deleteRecursively())
        }
    }

    @Test fun emptyDirectoryIsReturnedByBothJavaAndRealShellPaths() = runBlocking {
        val directory = newDirectory()
        val empty = File(directory, "empty").apply { check(mkdir()) }
        try {
            for (shellMode in listOf(false, true)) {
                val response = exchange(directory, listOf(byteArrayOf(0) + empty.path.toByteArray()), shellMode)
                assertEquals("NZFN", response.copyOfRange(0, 4).toString(Charsets.UTF_8))
                val length = ByteBuffer.wrap(response, 4, 4).order(ByteOrder.BIG_ENDIAN).int
                assertEquals(empty.path + "/", response.copyOfRange(8, 8 + length).toString(Charsets.UTF_8))
                assertEquals(8 + length, response.size)
            }
        } finally { assertTrue(directory.deleteRecursively()) }
    }

    private fun newDirectory() = File(deviceApplication.cacheDir, "file-semantics-${UUID.randomUUID()}").apply { check(mkdir()) }

    /** Exercises the production runner with a real ordinary-UID shell, without requesting su/Shizuku. */
    private suspend fun shell(command: String, timeout: Long): ShellCommandResult {
        val process = ProcessBuilder("sh").redirectErrorStream(true).start()
        val closed = AtomicBoolean()
        val close = {
            if (closed.compareAndSet(false, true)) {
                process.destroy()
                listOf(process.outputStream, process.inputStream, process.errorStream).forEach { runCatching { it.close() } }
            }
            Unit
        }
        return try { executeIsolatedShellCommand(process, command, timeout, close) }
        finally {
            close()
            awaitCondition("isolated shell process exited", 5_000) {
                try { process.exitValue(); true } catch (_: IllegalThreadStateException) { false }
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun exchange(
        directory: File, frames: List<ByteArray>, shellMode: Boolean,
        copy: (File, File) -> Unit = { source, target -> source.copyTo(target, overwrite = true); Unit }
    ): ByteArray {
        val cache = File(directory, "cache-${UUID.randomUUID()}").apply { check(mkdir()) }
        val context = object : ContextWrapper(deviceApplication) { override fun getCacheDir() = cache }
        val output = mutableListOf<ByteArray>()
        val manager = FileManager(context, "device-file-semantics", { outgoing -> channelFlow {
            val complete = CompletableDeferred<Unit>()
            val reader = launch { outgoing.collect {
                if (!it.data.isEmpty) {
                    output += it.data.toByteArray()
                    if (output.size == 2) complete.complete(Unit)
                }
            } }
            try {
                frames.forEach { send(IOStreamData.newBuilder().setData(ByteString.copyFrom(it)).build()) }
                withTimeout(10_000) { complete.await() }
            } finally { reader.cancel() }
        } }, null, { shellMode }, ::shell, copy)
        withTimeout(15_000) { manager.run() }
        assertEquals(2, output.size)
        assertTrue(cache.listFiles().orEmpty().isEmpty())
        return output[1]
    }
}
