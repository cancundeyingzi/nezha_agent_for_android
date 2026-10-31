package com.nezhahq.agent.mcp

import com.google.gson.JsonParser
import com.nezhahq.agent.core.mcp.*
import com.nezhahq.agent.mcp.fs.*
import com.nezhahq.agent.mcp.helper.HelperExecutionContext
import com.nezhahq.agent.mcp.transport.HelperDeadline
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

class HelperFsRunnerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun lazyEnumerationRetains5000ButCountsEveryVisibleEntryWithoutFurtherMetadata() {
        for (showHidden in listOf(false, true)) {
            val directory = Directory(sequence {
                repeat(6005) {
                    if (it < 100) yield(".hidden-$it")
                    yield("file-$it")
                }
            })
            val result = HelperFsRunner(access(directory)).list(FsListRequest("/directory", showHidden), context())
            assertNull(result.error)
            assertEquals(if (showHidden) 6105L else 6005L, result.total)
            assertEquals(5000, result.entries.size)
            assertTrue(result.truncated)
            assertEquals(6105, directory.visited)
            assertEquals(5000, directory.metadataReads)
            assertEquals(0, directory.linkReads)
            assertEquals(1, directory.closes)
            assertEquals(if (showHidden) ".hidden-0" else "file-0", result.entries.first().name)
        }
    }

    @Test fun hiddenOnlyAndEmptyDirectoriesAreCompletedWithNoFalseTruncation() {
        for (names in listOf(emptySequence(), sequenceOf(".one", ".二"))) {
            val directory = Directory(names)
            val result = HelperFsRunner(access(directory)).list(FsListRequest("/directory"), context())
            assertNull(result.error)
            assertEquals(0L, result.total)
            assertTrue(result.entries.isEmpty())
            assertFalse(result.truncated)
            assertEquals(0, directory.metadataReads)
            assertEquals(1, directory.closes)
        }
    }

    @Test fun specialNamesAndSymlinkMetadataStayWholeAndAreNeverFollowed() {
        val name = " 空 格\n中文 "
        val target = "../目 标\n中文"
        val directory = Directory(sequenceOf(name, "directory", "link", "fifo"), metadata = {
            when (it) {
                name -> FsMetadata(FsEntryType.FILE, 9_007_199_254_740_993, 0b110100100, -2)
                "directory" -> FsMetadata(FsEntryType.DIR, 4096, 0b111101101, 17)
                "link" -> FsMetadata(FsEntryType.SYMLINK, target.toByteArray().size.toLong(), 0b111111111, 18)
                else -> FsMetadata(FsEntryType.SPECIAL, 0, 0, 19)
            }
        }, target = { assertEquals("link", it); target })
        val result = HelperFsRunner(access(directory)).list(FsListRequest("/directory"), context())
        assertEquals(4L, result.total)
        assertEquals(listOf(name, "directory", "link", "fifo"), result.entries.map { it.name })
        assertEquals(FsEntry(name, FsEntryType.FILE, 9_007_199_254_740_993, "0644", -2), result.entries[0])
        assertEquals(FsEntryType.DIR, result.entries[1].type)
        assertEquals(FsEntryType.SYMLINK, result.entries[2].type)
        assertTrue(result.entries[2].isSymlink)
        assertEquals(target, result.entries[2].linkTarget)
        assertEquals(FsEntryType.SPECIAL, result.entries[3].type)
        assertEquals(1, directory.linkReads)
        val json = JsonParser.parseString(McpResultEncoder().encode(1, 16, result).data).asJsonObject
        assertEquals(name, json.getAsJsonArray("entries")[0].asJsonObject.get("name").asString)
        assertEquals(target, json.getAsJsonArray("entries")[2].asJsonObject.get("link_target").asString)
    }

    @Test fun entryWireMeterUsesFinalEncoderEscapesAndLimitsBeforeRetainingHugeTargets() {
        val entry = FsEntry("\u0000\n中文😀\\\"", FsEntryType.SYMLINK, 99, "0777", Long.MIN_VALUE,
            true, "\t\u2028\u2029 中文😀")
        val json = McpResultEncoder().encode(-1, 16, FsListResult(listOf(entry))).data
            .removePrefix("{\"entries\":[").removeSuffix("]}")
        val bytes = json.toByteArray(Charsets.UTF_8).size
        assertEquals(bytes, McpResultEncoder.fsEntryEncodedBytes(entry, bytes))
        assertNull(McpResultEncoder.fsEntryEncodedBytes(entry, bytes - 1))

        val directory = Directory((0 until 5000).asSequence().map { "link-$it" },
            metadata = { FsMetadata(FsEntryType.SYMLINK, 4096, 511, 0) }, target = { "x".repeat(4096) })
        val result = HelperFsRunner(access(directory)).list(FsListRequest("/directory"), context())
        assertEquals(FsError.RESULT_TOO_LARGE.safeMessage, result.error)
        assertTrue(directory.visited in 501..800)
        assertEquals(directory.visited, directory.metadataReads)
        assertEquals(directory.visited, directory.linkReads)
        assertTrue(result.entries.isEmpty())
        assertEquals(0L, result.total)
        assertFalse(JsonParser.parseString(McpResultEncoder().encode(1, 16, result).data).asJsonObject.has("total"))
        assertEquals(1, directory.closes)
    }

    @Test fun enumerationDeadlineAfter5000StillErrorsInsteadOfReportingAnInventedTotal() {
        val now = AtomicLong()
        val directory = Directory((0 until 7000).asSequence().map {
            if (it == 5010) now.set(31_000_000_000)
            "entry-$it"
        })
        val result = HelperFsRunner(access(directory)).list(FsListRequest("/directory"),
            context(HelperDeadline.afterMillis(30_000, now::get)))
        assertEquals(FsError.TIMED_OUT.safeMessage, result.error)
        assertTrue(result.entries.isEmpty())
        assertEquals(0L, result.total)
        assertEquals(5000, directory.metadataReads)
        assertEquals(1, directory.closes)
    }

    @Test fun directoryIteratorMetadataReadlinkAndCloseFailuresAreErrorsAndReleaseOwner() {
        val failures = listOf<Directory>(
            Directory(sequence { throw IOException("private iterator path") }),
            Directory(sequenceOf("file"), metadata = { throw FsFailure(FsError.PERMISSION_DENIED) }),
            Directory(sequenceOf("link"), metadata = { FsMetadata(FsEntryType.SYMLINK, 2, 511, 0) },
                target = { throw IOException("private target") }),
            Directory(sequenceOf("file"), closeFailure = IOException("private close path"))
        )
        failures.forEach { directory ->
            val result = HelperFsRunner(access(directory)).list(FsListRequest("/directory"), context())
            assertNotNull(result.error)
            assertFalse(result.error!!.contains("private"))
            assertTrue(result.entries.isEmpty())
            assertEquals(0L, result.total)
            assertEquals(1, directory.closes)
        }
    }

    @Test fun regularSegmentBase64SizeAndHashComeFromRawReturnedBytes() {
        val handle = BytesHandle("xxhellotail".toByteArray(), chunk = 2)
        val result = HelperFsRunner(access(read = handle)).read(
            FsReadRequest("/file", offset = 2, length = 5, encoding = "base64"), context())
        assertNull(result.error)
        assertEquals("aGVsbG8=", result.content)
        assertEquals(5L, result.size)
        assertEquals(HELLO_SHA256, result.sha256)
        assertTrue(result.truncated)
        assertEquals(listOf(2L, 4L, 6L), handle.positions)
        assertEquals(1, handle.stats)
        assertEquals(1, handle.closes)
    }

    @Test fun unicodeCutReplacesMalformedUtf8LikeExecAndStillHashesOriginalBytes() {
        val bytes = "你好😀".toByteArray()
        val result = HelperFsRunner(access(read = BytesHandle(bytes))).read(FsReadRequest("/file", length = 4), context())
        assertEquals("你\uFFFD", result.content)
        assertEquals(4L, result.size)
        assertEquals(hexDigest(bytes.copyOf(4)), result.sha256)
        assertTrue(result.truncated)
        val capture = com.nezhahq.agent.mcp.exec.ExecOutputCapture(4)
        capture.accept(com.nezhahq.agent.mcp.exec.ExecOutput.Bytes(false, bytes.copyOf(4)))
        assertEquals(capture.snapshot().stdout, result.content)
    }

    @Test fun defaultZeroAndHugeLengthClampToOneMiBWithoutReadingWholeFile() {
        val bytes = ByteArray(McpLimits.MAX_FS_READ_BYTES.toInt() + 37) { (it % 251).toByte() }
        for (length in listOf(0L, McpLimits.MAX_FS_READ_BYTES, Long.MAX_VALUE)) {
            val handle = BytesHandle(bytes)
            val result = HelperFsRunner(access(read = handle)).read(FsReadRequest("/file", length = length), context())
            assertNull(result.error)
            assertEquals(McpLimits.MAX_FS_READ_BYTES, result.size)
            assertEquals(McpLimits.MAX_FS_READ_BYTES.toInt(), handle.totalRead)
            assertTrue(result.truncated)
            assertEquals(hexDigest(bytes.copyOf(McpLimits.MAX_FS_READ_BYTES.toInt())), result.sha256)
            assertEquals(1, handle.closes)
        }
    }

    @Test fun emptyAndEofBeyondOffsetsIncludingLongMaximumAreLegalWithoutOverflow() {
        for ((bytes, offset) in listOf(ByteArray(0) to 0L, "hello".toByteArray() to 5L,
            "hello".toByteArray() to 999L, "hello".toByteArray() to Long.MAX_VALUE)) {
            val handle = BytesHandle(bytes)
            val result = HelperFsRunner(access(read = handle)).read(FsReadRequest("/file", offset = offset), context())
            assertNull(result.error)
            assertEquals("", result.content)
            assertEquals(0L, result.size)
            assertEquals(EMPTY_SHA256, result.sha256)
            assertFalse(result.truncated)
            assertEquals(1, handle.closes)
        }
    }

    @Test fun offsetOverDoublePrecisionAndFixedFstatSizeArePreserved() {
        val offset = 9_007_199_254_740_993L
        val handle = object : FsReadHandle {
            var stats = 0
            var closes = 0
            override fun stat(): FsMetadata {
                stats++
                return FsMetadata(FsEntryType.FILE, if (stats == 1) offset + 7 else offset, 420, 0)
            }
            override fun pread(buffer: ByteArray, offset: Int, length: Int, fileOffset: Long): Int {
                assertEquals(9_007_199_254_740_993L, fileOffset)
                buffer[offset] = 'o'.code.toByte()
                buffer[offset + 1] = 'k'.code.toByte()
                return 2
            }
            override fun close() { closes++ }
        }
        val result = HelperFsRunner(access(read = handle)).read(FsReadRequest("/file", offset, 2), context())
        assertEquals("ok", result.content)
        assertEquals(2L, result.size)
        assertTrue(result.truncated)
        assertEquals(1, handle.stats)
        assertEquals(1, handle.closes)
    }

    @Test fun fifoDirectorySocketAndSymlinkFdTypesAreRejectedBeforeAnyRead() {
        for (type in listOf(FsEntryType.SPECIAL, FsEntryType.DIR, FsEntryType.SYMLINK)) {
            val handle = BytesHandle("must not read".toByteArray(), type = type)
            val result = HelperFsRunner(access(read = handle)).read(FsReadRequest("/file"), context())
            assertEquals(FsError.NOT_REGULAR.safeMessage, result.error)
            assertTrue(handle.positions.isEmpty())
            assertEquals(1, handle.closes)
        }
    }

    @Test fun invalidBusinessArgumentsAndExpiredDeadlineDoNotOpenAnything() {
        var readOpens = 0
        var directoryOpens = 0
        val forbidden = object : FsAccess {
            override fun openRead(path: String): FsReadHandle {
                readOpens++
                throw AssertionError("Must not open a file before validation/deadline checks")
            }
            override fun openDirectory(path: String): FsDirectoryHandle {
                directoryOpens++
                throw AssertionError("Must not open a directory before validation/deadline checks")
            }
        }
        val runner = HelperFsRunner(forbidden)
        listOf(
            FsReadRequest("relative") to "path must be absolute",
            FsReadRequest("/bad\u0000path") to "path contains a NUL character",
            FsReadRequest("/file", -1) to "offset must not be negative",
            FsReadRequest("/file", length = -1) to "length must not be negative",
            FsReadRequest("/file", encoding = "secret") to "unknown encoding"
        ).forEach { (request, expectedError) ->
            assertEquals(expectedError, runner.read(request, context()).error)
        }
        assertEquals("path must be absolute", runner.list(FsListRequest("relative"), context()).error)
        assertEquals(FsError.TIMED_OUT.safeMessage,
            runner.list(FsListRequest("/file"), context(HelperDeadline.afterMillis(0))).error)
        assertEquals(FsError.TIMED_OUT.safeMessage,
            runner.read(FsReadRequest("/file"), context(HelperDeadline.afterMillis(0))).error)
        assertEquals(0, readOpens)
        assertEquals(0, directoryOpens)
    }

    @Test fun readStatPreadCloseAndElapsedNativeReadFailuresReleaseTheOpenedHandle() {
        for (stage in listOf("stat", "pread", "close", "deadline")) {
            val now = AtomicLong()
            var closes = 0
            val handle = object : FsReadHandle {
                override fun stat(): FsMetadata {
                    if (stage == "stat") throw IOException("private stat path")
                    return FsMetadata(FsEntryType.FILE, 5, 420, 0)
                }
                override fun pread(buffer: ByteArray, offset: Int, length: Int, fileOffset: Long): Int {
                    if (stage == "deadline") { now.set(31_000_000_000); return 0 }
                    if (stage == "pread") throw IOException("private read path")
                    return 0
                }
                override fun close() { closes++; if (stage == "close") throw IOException("private close path") }
            }
            val result = HelperFsRunner(access(read = handle)).read(FsReadRequest("/file"),
                context(HelperDeadline.afterMillis(30_000, now::get)))
            assertEquals(if (stage == "deadline") FsError.TIMED_OUT.safeMessage else FsError.IO.safeMessage, result.error)
            assertEquals(1, closes)
            assertEquals("", result.content)
            assertNull(result.sha256)
        }
    }

    @Test fun openedReadHandlePinsOriginalFileWhenItsNameIsReplaced() {
        val original = temporary.newFile("original").toPath()
        Files.write(original, "hello".toByteArray())
        val moved = original.resolveSibling("moved")
        var opens = 0
        var closes = 0
        val files = object : FsAccess {
            override fun openRead(path: String): FsReadHandle {
                opens++
                val channel = FileChannel.open(original, StandardOpenOption.READ)
                Files.move(original, moved)
                Files.write(original, "replacement".toByteArray())
                return object : FsReadHandle {
                    override fun stat() = FsMetadata(FsEntryType.FILE, channel.size(), 420, 0)
                    override fun pread(buffer: ByteArray, offset: Int, length: Int, fileOffset: Long): Int =
                        channel.read(ByteBuffer.wrap(buffer, offset, length), fileOffset).coerceAtLeast(0)
                    override fun close() { closes++; channel.close() }
                }
            }
            override fun openDirectory(path: String): FsDirectoryHandle = error("Must not open")
        }
        val result = HelperFsRunner(files).read(FsReadRequest("/same-name", length = 5), context())
        assertEquals("hello", result.content)
        assertEquals(HELLO_SHA256, result.sha256)
        assertEquals("replacement", String(Files.readAllBytes(original)))
        assertEquals(1, opens)
        assertEquals(1, closes)
    }

    @Test fun cleanedPathReachesAccessWithoutTrimmingNamesOrFollowingLinks() {
        val files = object : FsAccess {
            override fun openRead(path: String): FsReadHandle {
                assertEquals("/ 空格\n中文 /link", path)
                throw FsFailure(FsError.SYMLINK)
            }
            override fun openDirectory(path: String): FsDirectoryHandle {
                assertEquals("/ 空格\n中文 /link", path)
                throw FsFailure(FsError.SYMLINK)
            }
        }
        val runner = HelperFsRunner(files)
        assertEquals(FsError.SYMLINK.safeMessage,
            runner.read(FsReadRequest("// 空格\n中文 /x/../link/./"), context()).error)
        assertEquals(FsError.SYMLINK.safeMessage,
            runner.list(FsListRequest("// 空格\n中文 /x/../link/./"), context()).error)
    }

    @Test fun base64PaddingAndBinaryBytesMatchJdkReferenceWithoutNewlines() {
        for (length in 0..260) {
            val bytes = ByteArray(length) { it.toByte() }
            val encoded = FsContent.base64(bytes, bytes.size)
            assertEquals(java.util.Base64.getEncoder().encodeToString(bytes), encoded)
            assertFalse(encoded.contains('\n'))
        }
    }

    @Test fun api23To25GuardReturnsBeforeAnyUnmockedAndroidOrNioCallAndAbiFlagsAreExact() {
        for (api in 23..25) {
            try { AndroidFsAccess { api }.openDirectory("/directory"); fail("Expected capability error") }
            catch (failure: FsFailure) { assertEquals(FsError.ENUMERATION_UNAVAILABLE, failure.reason) }
        }
        assertTrue(FsPlatformRules.boundedDirectoryEnumerationAvailable(26))
        assertEquals(16_384, FsPlatformRules.directoryFlagForNoFollow(32_768))
        assertEquals(65_536, FsPlatformRules.directoryFlagForNoFollow(131_072))
        assertNull(FsPlatformRules.directoryFlagForNoFollow(0))
        assertNull(FsPlatformRules.directoryFlagForNoFollow(65_536))
        assertEquals(524_288, FsPlatformRules.closeOnExecFlagForNoFollow(32_768))
        assertEquals(524_288, FsPlatformRules.closeOnExecFlagForNoFollow(131_072))
        assertNull(FsPlatformRules.closeOnExecFlagForNoFollow(0))
        assertNull(FsPlatformRules.closeOnExecFlagForNoFollow(65_536))
        assertEquals("0644", FsPlatformRules.permissionMode(0x81a4))
        assertEquals("0777", FsPlatformRules.permissionMode(0xffff))
        assertEquals("0", FsPlatformRules.permissionMode(0))
    }

    @Test fun directoryDescriptorClosesBeforeAnchorOwnershipTransfersOnSuccess() {
        val events = mutableListOf<String>()
        val descriptor = Any()
        val anchor = AutoCloseable { events += "anchor-close" }
        val returned = pinDirectoryDescriptor(descriptor, pin = {
            assertSame(descriptor, it)
            events += "pin"
            anchor
        }, close = {
            assertSame(descriptor, it)
            events += "fd-close"
        })
        assertSame(anchor, returned)
        assertEquals(listOf("pin", "fd-close"), events)
        returned.close()
        assertEquals(listOf("pin", "fd-close", "anchor-close"), events)
    }

    @Test fun directoryPinFailureStillClosesOriginalDescriptorExactlyOnce() {
        val failure = FsFailure(FsError.ANCHOR_UNAVAILABLE)
        var closes = 0
        try {
            pinDirectoryDescriptor<Any, AutoCloseable>(Any(), pin = { throw failure }, close = { closes++ })
            fail("Expected pin failure")
        } catch (actual: FsFailure) { assertSame(failure, actual) }
        assertEquals(1, closes)
    }

    @Test fun originalDescriptorCloseFailureReleasesAnchorEvenWhenAnchorCloseAlsoFails() {
        for (anchorCloseFails in listOf(false, true)) {
            val failure = FsFailure(FsError.IO)
            val anchorFailure = IOException("private anchor close failure")
            val events = mutableListOf<String>()
            val anchor = AutoCloseable {
                events += "anchor-close"
                if (anchorCloseFails) throw anchorFailure
            }
            try {
                pinDirectoryDescriptor(Any(), pin = { events += "pin"; anchor }, close = {
                    events += "fd-close"
                    throw failure
                })
                fail("Expected source fd close failure")
            } catch (actual: FsFailure) { assertSame(failure, actual) }
            assertEquals(listOf("pin", "fd-close", "anchor-close"), events)
            assertEquals(if (anchorCloseFails) listOf(anchorFailure) else emptyList<Throwable>(), failure.suppressed.toList())
        }
    }

    @Test fun pinAndOriginalCloseFailureKeepPinErrorWithCloseSuppressed() {
        for (reuseFailure in listOf(false, true)) {
            val failure = FsFailure(FsError.ANCHOR_UNAVAILABLE)
            val closeFailure = if (reuseFailure) failure else IOException("private original close failure")
            var closes = 0
            try {
                pinDirectoryDescriptor<Any, AutoCloseable>(Any(), pin = { throw failure }, close = {
                    closes++
                    throw closeFailure
                })
                fail("Expected pin failure")
            } catch (actual: FsFailure) { assertSame(failure, actual) }
            assertEquals(1, closes)
            assertEquals(if (reuseFailure) emptyList<Throwable>() else listOf(closeFailure), failure.suppressed.toList())
        }
    }

    @Test fun resourcePairAlwaysAttemptsBothClosesAndKeepsFirstFailure() {
        for (firstFails in listOf(false, true)) for (secondFails in listOf(false, true)) {
            val firstFailure = IOException("private stream close failure")
            val secondFailure = IOException("private anchor close failure")
            val events = mutableListOf<String>()
            val actual = try {
                withFsCleanup(block = {
                    events += "stream-close"
                    if (firstFails) throw firstFailure
                }, cleanup = {
                    events += "anchor-close"
                    if (secondFails) throw secondFailure
                })
                null
            } catch (failure: IOException) { failure }
            val expected = if (firstFails) firstFailure else if (secondFails) secondFailure else null
            assertSame(expected, actual)
            assertEquals(listOf("stream-close", "anchor-close"), events)
            assertEquals(if (firstFails && secondFails) listOf(secondFailure) else emptyList<Throwable>(),
                actual?.suppressed?.toList().orEmpty())
        }
    }

    private fun context(deadline: HelperDeadline = HelperDeadline.afterMillis(30_000)) = HelperExecutionContext(deadline, 0)
    private fun access(directory: FsDirectoryHandle? = null, read: FsReadHandle? = null) = object : FsAccess {
        override fun openRead(path: String) = requireNotNull(read)
        override fun openDirectory(path: String) = requireNotNull(directory)
    }

    private class Directory(
        private val names: Sequence<String>,
        private val metadata: (String) -> FsMetadata = { FsMetadata(FsEntryType.FILE, 5, 420, 0) },
        private val target: (String) -> String = { error("Must not follow/read a regular child") },
        private val closeFailure: IOException? = null
    ) : FsDirectoryHandle {
        var visited = 0
        var metadataReads = 0
        var linkReads = 0
        var closes = 0
        override fun iterator(): Iterator<String> = names.map { visited++; it }.iterator()
        override fun lstat(name: String): FsMetadata { metadataReads++; return metadata(name) }
        override fun readlink(name: String): String { linkReads++; return target(name) }
        override fun close() { closes++; closeFailure?.let { throw it } }
    }

    private class BytesHandle(
        private val bytes: ByteArray,
        private val chunk: Int = Int.MAX_VALUE,
        private val type: FsEntryType = FsEntryType.FILE
    ) : FsReadHandle {
        var stats = 0
        var closes = 0
        var totalRead = 0
        val positions = mutableListOf<Long>()
        override fun stat(): FsMetadata { stats++; return FsMetadata(type, bytes.size.toLong(), 420, 0) }
        override fun pread(buffer: ByteArray, offset: Int, length: Int, fileOffset: Long): Int {
            positions += fileOffset
            if (fileOffset >= bytes.size) return 0
            val count = minOf(chunk, length, bytes.size - fileOffset.toInt())
            bytes.copyInto(buffer, offset, fileOffset.toInt(), fileOffset.toInt() + count)
            totalRead += count
            return count
        }
        override fun close() { closes++ }
    }

    private fun hexDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        private const val HELLO_SHA256 = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
