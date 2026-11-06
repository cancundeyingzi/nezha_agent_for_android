package com.nezhahq.agent.device

import android.content.ContextWrapper
import android.system.Os
import android.system.StructStat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import com.nezhahq.agent.executor.FileManager
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import proto.Nezha.IOStreamData

@RunWith(AndroidJUnit4::class)
class FileUploadCancellationRegressionTest {
    @get:Rule val evidence = DeviceEvidenceRule()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun cancellingPartialUploadClosesRealDescriptorAndPreservesExistingTarget() = runBlocking {
        val directory = File(deviceApplication.cacheDir, "device-upload-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val cache = File(directory, "partial").apply { check(mkdirs()) }
        val target = File(directory, "target.bin").apply { writeText("original synthetic contents") }
        val original = target.readBytes()
        val context = object : ContextWrapper(deviceApplication) { override fun getCacheDir() = cache }
        val input = Channel<IOStreamData>(Channel.UNLIMITED)
        val handshake = CompletableDeferred<Unit>()
        val streamClosed = CompletableDeferred<Unit>()
        val manager = FileManager(context, "device-upload", { outgoing ->
            channelFlow {
                val reader = launch { outgoing.collect { if (!it.data.isEmpty) handshake.complete(Unit) } }
                try { for (frame in input) send(frame) }
                finally { reader.cancel(); streamClosed.complete(Unit) }
            }
        }, null)
        val running = launch(Dispatchers.IO) { manager.run() }
        try {
            withTimeout(10_000) { handshake.await() }
            val header = byteArrayOf(0x02) + ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
                .putLong(1024).array() + target.absolutePath.toByteArray(Charsets.UTF_8)
            input.send(frame(header))
            input.send(frame(byteArrayOf(1, 2, 3, 4)))
            awaitCondition("partial upload written to a real cache file") {
                cache.listFiles().orEmpty().any { it.name.startsWith("nezha_upload_") && it.length() == 4L }
            }
            val partial = cache.listFiles().orEmpty().single()
            val identity = Os.stat(partial.path)
            assertTrue("Partial upload must own a real descriptor (dev=${identity.st_dev}, inode=${identity.st_ino})",
                descriptorsFor(identity).isNotEmpty())
            withTimeout(10_000) { running.cancelAndJoin(); streamClosed.await() }
            assertFalse(partial.exists())
            assertTrue("Unlinking the cache alone must not hide a leaked descriptor", descriptorsFor(identity).isEmpty())
            assertTrue(cache.listFiles().orEmpty().isEmpty())
            assertArrayEquals(original, target.readBytes())
            assertTrue(running.isCompleted)
            assertTrue(running.children.none())
        } finally {
            input.close()
            withTimeout(10_000) { running.cancelAndJoin() }
            assertTrue(directory.deleteRecursively())
        }
    }

    private fun frame(bytes: ByteArray) = IOStreamData.newBuilder().setData(ByteString.copyFrom(bytes)).build()

    private fun descriptorsFor(identity: StructStat): List<File> = File("/proc/self/fd").listFiles().orEmpty().filter {
        // /data/data and /data/user/0 can name the same file. Kernel identity also remains valid
        // after unlink, so it proves descriptor closure without relying on a symlink's spelling.
        runCatching {
            val opened = Os.stat(it.path)
            opened.st_dev == identity.st_dev && opened.st_ino == identity.st_ino
        }.getOrDefault(false)
    }
}
