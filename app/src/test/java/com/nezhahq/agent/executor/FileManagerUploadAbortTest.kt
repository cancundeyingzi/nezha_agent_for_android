package com.nezhahq.agent.executor

import android.content.ContextWrapper
import com.google.protobuf.ByteString
import com.nezhahq.agent.SilentLoggerRule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import proto.Nezha
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections

/**
 * The dispatcher decides "file content or opcode?" from one piece of state, so what that state says
 * after a failed upload is the whole ball game: the dashboard usually has the rest of the file
 * already in flight when the rejection reaches it, and the old `uploadSession = null` made every one
 * of those chunks look like a fresh command.
 */
class FileManagerUploadAbortTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `final save into an existing directory aborts and drains download and upload residue`() = runBlocking {
        val directory = temporaryFolder.newFolder("directory-target")
        val original = File(directory, "keep.txt").apply { writeText("original") }
        val residualTarget = File(temporaryFolder.newFolder("after-save-failure"), "must-not-exist.bin")
        val context = cacheDirContext()
        val source = MapDownloadSource(mapOf("legit" to "hello".toByteArray()))
        val output = Collections.synchronizedList(mutableListOf<ByteArray>())
        val manager = FileManager(
            context = context,
            streamId = "final-save-failure",
            openIoStream = scriptedSession(
                frames = listOf(
                    uploadRequest(directory.absolutePath, 3),
                    byteArrayOf(7, 8, 9),
                    downloadRequest("legit"),
                    uploadRequest(residualTarget.absolutePath, 0)
                ),
                expectedOutputFrames = 2,
                output = output
            ),
            downloadSourceOverride = source
        )

        withTimeout(TEST_TIMEOUT_MS) { manager.run() }

        assertTrue(directory.isDirectory)
        assertEquals("original", original.readText())
        assertEquals(emptyList<String>(), source.openedPaths)
        assertFalse(residualTarget.exists())
        assertEquals(2, output.size)
        assertTrue(output[1].startsWith(ERROR_MAGIC))
        assertTrue(String(output[1], Charsets.UTF_8).contains("重新打开文件管理会话"))
        assertTrue(context.cacheDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a cache open failure drains residue without logging the failure contents`() = runBlocking {
        val secret = "private-upload-path-48291"
        val source = MapDownloadSource(mapOf("legit" to "hello".toByteArray()))
        val output = Collections.synchronizedList(mutableListOf<ByteArray>())
        val manager = FileManager(
            context = object : ContextWrapper(null) {
                override fun getCacheDir(): File = throw IOException(secret)
            },
            streamId = "cannot-open-cache",
            openIoStream = scriptedSession(
                frames = listOf(uploadRequest("/target", 4), downloadRequest("legit")),
                expectedOutputFrames = 2,
                output = output
            ),
            downloadSourceOverride = source
        )

        withTimeout(TEST_TIMEOUT_MS) { manager.run() }

        assertEquals(emptyList<String>(), source.openedPaths)
        assertEquals(2, output.size)
        assertFalse(silentLogger.messages.joinToString().contains(secret))
        assertTrue(String(output[1], Charsets.UTF_8).contains("重新打开文件管理会话"))
    }

    @Test
    fun `chunks arriving after an aborted upload are not executed as commands`() = runBlocking {
        val source = MapDownloadSource(mapOf("legit" to "hello".toByteArray()))
        val output = Collections.synchronizedList(mutableListOf<ByteArray>())
        // Declared size 0 completes the moment the request is parsed, so if this frame were ever
        // treated as a command the file would be on disk by the time the session ends.
        val residualUploadTarget = File(temporaryFolder.newFolder("residual"), "must-not-appear.bin")
        val manager = FileManager(
            context = cacheDirContext(),
            streamId = "abort-discard",
            openIoStream = scriptedSession(
                frames = listOf(
                    uploadRequest(targetPath = "/never/written", declaredSize = 4),
                    // Over-send: rejected before a byte is written, which aborts the session.
                    ByteArray(8) { 0x41 },
                    // Everything below is residual file content whose leading byte happens to be a
                    // valid opcode — exactly what the dashboard keeps pushing after the error.
                    listDirRequest("/residual/dir"),
                    downloadRequest("legit"),
                    uploadRequest(residualUploadTarget.absolutePath, declaredSize = 0)
                ),
                expectedOutputFrames = 2,
                output = output
            ),
            downloadSourceOverride = source
        )

        withTimeout(TEST_TIMEOUT_MS) { manager.run() }

        assertEquals("a discarded chunk opened a download", emptyList<String>(), source.openedPaths)
        assertFalse("a discarded chunk started a new upload", residualUploadTarget.exists())
        // Handshake plus the one error the rejection produced. A residual listDir would have added
        // its own error frame, and a residual download its NZTD header.
        assertEquals("residual chunks produced responses: ${describe(output)}", 2, output.size)
        assertTrue(output[0].startsWith(STREAM_MAGIC))
        assertTrue(output[1].startsWith(ERROR_MAGIC))
    }

    @Test
    fun `chunks arriving after a request rejected before the session opened are not executed`() =
        runBlocking {
            val source = MapDownloadSource(mapOf("legit" to "hello".toByteArray()))
            val output = Collections.synchronizedList(mutableListOf<ByteArray>())
            val residualUploadTarget =
                File(temporaryFolder.newFolder("residual-early"), "must-not-appear.bin")
            val manager = FileManager(
                context = cacheDirContext(),
                streamId = "abort-before-open",
                openIoStream = scriptedSession(
                    frames = listOf(
                        // Rejected by UploadSession.validateDeclaredSize, i.e. before any session
                        // exists. The over-send test above aborts from Active; this one has to abort
                        // straight out of Idle, which is the path the state machine originally
                        // missed — the dashboard pushes the file either way.
                        uploadRequest("/never/written", declaredSize = OVERSIZE_DECLARED_BYTES),
                        listDirRequest("/residual/dir"),
                        downloadRequest("legit"),
                        uploadRequest(residualUploadTarget.absolutePath, declaredSize = 0)
                    ),
                    expectedOutputFrames = 2,
                    output = output
                ),
                downloadSourceOverride = source
            )

            withTimeout(TEST_TIMEOUT_MS) { manager.run() }

            assertEquals("a discarded chunk opened a download", emptyList<String>(), source.openedPaths)
            assertFalse("a discarded chunk started a new upload", residualUploadTarget.exists())
            assertEquals("residual chunks produced responses: ${describe(output)}", 2, output.size)
            assertTrue(output[0].startsWith(STREAM_MAGIC))
            assertTrue(output[1].startsWith(ERROR_MAGIC))
        }

    @Test
    fun `delayed residual opcodes never reopen an aborted file session`() = runBlocking {
        val source = MapDownloadSource(mapOf("legit" to "hello".toByteArray()))
        val output = Collections.synchronizedList(mutableListOf<ByteArray>())
        val target = File(temporaryFolder.newFolder("delayed"), "must-not-exist.bin")
        val context = cacheDirContext()
        val manager = FileManager(
            context = context,
            streamId = "delayed-residue",
            openIoStream = scriptedSession(
                frames = listOf(
                    uploadRequest("/never/written", declaredSize = 4),
                    ByteArray(8) { 0x41 },
                    listDirRequest("/residual/dir"),
                    downloadRequest("legit"),
                    uploadRequest(target.absolutePath, declaredSize = 0)
                ),
                expectedOutputFrames = 2,
                output = output,
                delayBeforeResidualMillis = 3_100
            ),
            downloadSourceOverride = source
        )

        withTimeout(TEST_TIMEOUT_MS) { manager.run() }

        assertEquals(emptyList<String>(), source.openedPaths)
        assertFalse(target.exists())
        assertEquals(2, output.size)
        assertTrue(String(output[1], Charsets.UTF_8).contains("重新打开文件管理会话"))
        assertTrue("upload cache was not removed", context.cacheDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `an ordinary upload chunk is written verbatim even when it starts with an opcode`() =
        runBlocking {
            val target = File(temporaryFolder.newFolder("target"), "uploaded.bin")
            // 0x00 / 0x01 / 0x02 are the list, download and upload opcodes: while a session is
            // active they must never be looked at, only written.
            val payload = byteArrayOf(0x00, 0x01, 0x02)
            val output = Collections.synchronizedList(mutableListOf<ByteArray>())
            val manager = FileManager(
                context = cacheDirContext(),
                streamId = "upload-verbatim",
                openIoStream = scriptedSession(
                    frames = listOf(
                        uploadRequest(target.absolutePath, declaredSize = payload.size.toLong()),
                        payload
                    ),
                    expectedOutputFrames = 2,
                    output = output
                ),
                downloadSourceOverride = MapDownloadSource(emptyMap())
            )

            withTimeout(TEST_TIMEOUT_MS) { manager.run() }

            assertTrue(output[1].startsWith(COMPLETE_MAGIC))
            assertArrayEquals(payload, target.readBytes())
        }

    // ── 辅助 ──────────────────────────────────────────────────────────────────

    private fun cacheDirContext(): ContextWrapper {
        val cache = temporaryFolder.newFolder("cache")
        // ContextWrapper(null) would throw on any real framework call; only getCacheDir is reached,
        // and overriding it keeps the test off the Android runtime entirely.
        return object : ContextWrapper(null) {
            override fun getCacheDir(): File = cache
        }
    }

    /**
     * Feeds [frames] into the manager, records everything it sends back, and ends the session once
     * [expectedOutputFrames] have arrived.
     *
     * The extra settle window matters for the discard test: proving that *no* response follows needs
     * a window to observe across, and the frames it is waiting on are already queued.
     */
    private fun scriptedSession(
        frames: List<ByteArray>,
        expectedOutputFrames: Int,
        output: MutableList<ByteArray>,
        delayBeforeResidualMillis: Long = 0
    ): (Flow<Nezha.IOStreamData>) -> Flow<Nezha.IOStreamData> = { outgoing ->
        channelFlow {
            val outputComplete = CompletableDeferred<Unit>()
            val outputJob = launch {
                outgoing.collect { message ->
                    val bytes = message.data.toByteArray()
                    if (bytes.isNotEmpty()) {
                        output += bytes
                        if (output.size >= expectedOutputFrames) outputComplete.complete(Unit)
                    }
                }
            }
            try {
                frames.forEachIndexed { index, bytes ->
                    if (index == 2 && delayBeforeResidualMillis > 0) delay(delayBeforeResidualMillis)
                    send(dataFrame(bytes))
                }
                withTimeout(TEST_TIMEOUT_MS) { outputComplete.await() }
                delay(SETTLE_MS)
            } finally {
                outputJob.cancel()
                withContext(NonCancellable) {
                    withTimeout(TEST_TIMEOUT_MS) { outputJob.join() }
                }
            }
        }
    }

    private fun dataFrame(bytes: ByteArray): Nezha.IOStreamData =
        Nezha.IOStreamData.newBuilder().setData(ByteString.copyFrom(bytes)).build()

    private fun uploadRequest(targetPath: String, declaredSize: Long): ByteArray =
        byteArrayOf(0x02) +
            ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(declaredSize).array() +
            targetPath.toByteArray()

    private fun listDirRequest(path: String): ByteArray = byteArrayOf(0x00) + path.toByteArray()

    private fun downloadRequest(path: String): ByteArray = byteArrayOf(0x01) + path.toByteArray()

    private fun ByteArray.startsWith(magic: ByteArray): Boolean =
        size >= magic.size && copyOfRange(0, magic.size).contentEquals(magic)

    private fun describe(frames: List<ByteArray>): String =
        frames.joinToString { frame -> String(frame.copyOfRange(0, minOf(4, frame.size))) }

    private class MapDownloadSource(private val files: Map<String, ByteArray>) : DownloadFileSource {
        val openedPaths: List<String> get() = synchronized(opened) { opened.toList() }
        private val opened = mutableListOf<String>()

        override suspend fun size(path: String): Long? = files[path]?.size?.toLong()

        override suspend fun open(path: String): InputStream? {
            val bytes = files[path] ?: return null
            synchronized(opened) { opened += path }
            return ByteArrayInputStream(bytes)
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 10_000L

        /** How long a wrongly-executed residual chunk is given to reveal itself. */
        const val SETTLE_MS = 200L

        /**
         * One byte past the accepted maximum, derived rather than hard-coded so that raising the
         * limit cannot quietly turn the rejection test into an ordinary-upload test.
         */
        const val OVERSIZE_DECLARED_BYTES: Long = UploadSession.MAX_UPLOAD_BYTES + 1

        val STREAM_MAGIC = byteArrayOf(0xFF.toByte(), 0x05, 0xFF.toByte(), 0x05)
        val ERROR_MAGIC = byteArrayOf(0x4E, 0x45, 0x52, 0x52)
        val COMPLETE_MAGIC = byteArrayOf(0x4E, 0x5A, 0x55, 0x50)
    }
}
