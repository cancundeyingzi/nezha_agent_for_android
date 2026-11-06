package com.nezhahq.agent.executor

import android.content.ContextWrapper
import com.google.protobuf.ByteString
import com.nezhahq.agent.SilentLoggerRule
import com.nezhahq.agent.util.ShellCommandResult
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import proto.Nezha.IOStreamData

class FileManagerSaveAndDirectoryTest {
    @get:Rule val logger = SilentLoggerRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun aSameLengthOldFileCannotTurnACopyFailureIntoUploadSuccess() = runBlocking {
        val target = temporary.newFile().apply { writeText("old") }
        val response = upload(target, ShellCommandResult("sensitive shell detail", 72, outputComplete = true))
        assertError(response, "复制文件失败")
        assertEquals("old", target.readText())
        assertFalse(logger.messages.any { "sensitive shell detail" in it })
        assertFalse(response.toString(Charsets.UTF_8).contains("sensitive shell detail"))
    }

    @Test fun chmodFailureDoesNotEmitCompletionEvenAfterTheCopySucceeded() = runBlocking {
        val target = temporary.newFile().apply { writeText("old") }
        val response = upload(target, ShellCommandResult(exitCode = 73, outputComplete = true)) {
            target.writeText("new")
        }
        assertError(response, "设置目标权限失败")
        assertEquals("new", target.readText())
    }

    @Test fun missingExitTimeoutAndIncompleteOutputAllRejectTheOldSameLengthTarget() = runBlocking {
        val target = temporary.newFile().apply { writeText("old") }
        for (result in listOf(
            ShellCommandResult(),
            ShellCommandResult(exitCode = 0, timedOut = true, outputComplete = true),
            ShellCommandResult(exitCode = 0, outputComplete = false),
            ShellCommandResult(exitCode = 0, outputComplete = true, truncated = true),
            ShellCommandResult(exitCode = 71, outputComplete = true)
        )) assertError(upload(target, result), "文件保存失败")
        assertEquals("old", target.readText())
    }

    @Test fun aSuccessfulPrivilegedSaveStillRequiresTheExpectedLength() = runBlocking {
        val target = temporary.newFile().apply { writeText("old") }
        val success = upload(target, ShellCommandResult(exitCode = 0, outputComplete = true)) { target.writeText("new") }
        assertArrayEquals("NZUP".toByteArray(), success)
        val wrongLength = upload(target, ShellCommandResult(exitCode = 0, outputComplete = true)) { target.writeText("shortened") }
        assertError(wrongLength, "长度校验失败")
    }

    @Test fun aJavaEmptyDirectoryReturnsItsOwnPathAndZeroEntries() = runBlocking {
        val directory = temporary.newFolder("empty")
        val result = exchange(listOf(directoryRequest(directory.path)), privileged = false)
        assertEmptyDirectory(result, directory.path)
    }

    @Test fun aSuccessfulEmptyShellListingDoesNotFallBackToAnotherPath() = runBlocking {
        val path = File(temporary.root, "only-visible-to-shell").path
        var shellCalls = 0
        val result = exchange(listOf(directoryRequest(path)), privileged = true, shell = { _, _ ->
            shellCalls++
            ShellCommandResult(exitCode = 0, outputComplete = true)
        })
        assertEmptyDirectory(result, path)
        assertEquals(1, shellCalls)
    }

    @Test fun failedOrTruncatedShellListingIsNotAcceptedAsASuccessfulEmptyDirectory() = runBlocking {
        val path = File(temporary.root, "inaccessible").path
        for (shellResult in listOf(
            ShellCommandResult(exitCode = 1, outputComplete = true),
            ShellCommandResult(exitCode = 0, outputComplete = true, truncated = true)
        )) {
            val result = exchange(listOf(directoryRequest(path)), privileged = true, shell = { _, _ -> shellResult })
            val text = result.toString(Charsets.UTF_8)
            assertTrue("must fail or use the existing fallback policy", text.startsWith("NERR") || !text.contains(path))
        }
    }

    private suspend fun upload(target: File, result: ShellCommandResult, onShell: () -> Unit = {}): ByteArray {
        val frames = listOf(byteArrayOf(2) + ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(3).array() +
            target.path.toByteArray(), "new".toByteArray())
        return exchange(frames, privileged = true, shell = { _, _ -> onShell(); result },
            copy = { _, _ -> throw IOException("synthetic ordinary-copy denial") })
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun exchange(
        frames: List<ByteArray>,
        privileged: Boolean,
        shell: suspend (String, Long) -> ShellCommandResult = { _, _ -> error("unexpected privileged command") },
        copy: (File, File) -> Unit = { source, target -> source.copyTo(target, overwrite = true); Unit }
    ): ByteArray {
        val cache = temporary.newFolder()
        val output = mutableListOf<ByteArray>()
        val context = object : ContextWrapper(null) { override fun getCacheDir() = cache }
        val manager = FileManager(context, "file-regression", { outgoing -> channelFlow {
            val complete = CompletableDeferred<Unit>()
            val reader = launch { outgoing.collect {
                if (!it.data.isEmpty) {
                    output += it.data.toByteArray()
                    if (output.size == 2) complete.complete(Unit)
                }
            } }
            try {
                frames.forEach { send(IOStreamData.newBuilder().setData(ByteString.copyFrom(it)).build()) }
                withTimeout(5_000) { complete.await() }
            } finally { reader.cancel() }
        } }, null, { privileged }, shell, copy)
        withTimeout(5_000) { manager.run() }
        assertEquals(2, output.size)
        assertTrue("upload cache leaked", cache.listFiles().orEmpty().isEmpty())
        return output[1]
    }

    private fun directoryRequest(path: String) = byteArrayOf(0) + path.toByteArray()
    private fun assertError(response: ByteArray, reason: String) {
        val text = response.toString(Charsets.UTF_8)
        assertTrue(text, text.startsWith("NERR"))
        assertTrue(text, reason in text)
    }
    private fun assertEmptyDirectory(response: ByteArray, path: String) {
        assertEquals("NZFN", response.copyOfRange(0, 4).toString(Charsets.UTF_8))
        val pathSize = ByteBuffer.wrap(response, 4, 4).order(ByteOrder.BIG_ENDIAN).int
        assertEquals(path.trimEnd('/') + "/", response.copyOfRange(8, 8 + pathSize).toString(Charsets.UTF_8))
        assertEquals(8 + pathSize, response.size)
    }
}
