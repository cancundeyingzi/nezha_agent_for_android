package com.nezhahq.agent.mcp

import com.google.gson.JsonParser
import com.nezhahq.agent.core.mcp.FsListResult
import com.nezhahq.agent.core.mcp.FsReadResult
import com.nezhahq.agent.core.mcp.McpLimits
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.mcp.fs.*
import com.nezhahq.agent.mcp.helper.HelperIsolation
import com.nezhahq.agent.mcp.helper.HelperSession
import com.nezhahq.agent.mcp.transport.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import proto.Nezha.Task
import proto.Nezha.TaskResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

class McpFsBackendTest {
    @Test fun permissionParseAndPreparationLayersDoNotLaunchAndKeepFamilyEnvelopeSemantics() = runBlocking {
        val launcher = Launcher { error("Must never launch") }
        val backend = McpFsBackend(launcher)
        val reserved = mutableListOf<Long>()
        suspend fun run(type: Long, data: String, enabled: Boolean = true): TaskResult {
            val result = backend.execute(task(type, data), RemoteCapabilities(fileManagerEnabled = enabled)) {
                reserved += it
                AutoCloseable { }
            }
            return result.use { it.result }
        }
        for (type in listOf(16L, 17L)) {
            val denied = run(type, "{invalid private data", false)
            assertTrue(denied.successful)
            assertTrue(json(denied).get("error").asString.contains("disabled"))
            if (type == 16L) assertNotNull(json(denied).get("entries")) else assertNotNull(json(denied).get("content"))
            assertFalse(run(type, "{oops").successful)
            assertFalse(run(type, "{\"path\":1}").successful)
            assertTrue(run(type, "{\"path\":\"relative\"}").successful)
        }
        assertTrue(run(17, "{\"path\":\"/file\",\"offset\":-1}").successful)
        assertTrue(run(17, "{\"path\":\"/file\",\"length\":-1}").successful)
        assertEquals("unknown encoding", json(run(17,
            "{\"path\":\"/file\",\"encoding\":\"private encoding\"}")).get("error").asString)
        assertFalse(run(17, "{\"path\":\"/file\",\"offset\":9223372036854775808}").successful)
        listOf(11L, 15L, 18L, Long.MIN_VALUE).forEach { assertFalse(run(it, "{}").successful) }
        assertTrue(reserved.all { it == 1024L })
        assertEquals(0, launcher.starts)
    }

    @Test fun fileResultsReserveExactLengthBeforePayloadReadAndRetainLeaseUntilSendOrDrop() = runBlocking {
        for (type in listOf(16L, 17L)) {
            val expected = if (type == 16L) McpResultEncoder().encode(Long.MIN_VALUE, type, FsListResult(total = 1))
                else McpResultEncoder().encode(Long.MIN_VALUE, type, FsReadResult("hello", size = 5))
            lateinit var input: CountedInput
            val launcher = Launcher { spec ->
                assertTrue(spec.lifetimeMillis in 1..(McpLimits.DEFAULT_FILE_TIMEOUT_SECONDS * 1000).toInt())
                input = CountedInput(frames {
                    frame(HelperProtocol.Kind.HELLO, HelperProtocol.encodeHello(hello(spec)))
                    frame(HelperProtocol.Kind.RESULT, expected.toByteArray())
                })
                Lease(input)
            }
            val releases = AtomicInteger()
            var reservations = 0
            val data = "{\"path\":\"/换\\n行\",\"offset\":9007199254740993}"
            val owned = McpFsBackend(launcher).execute(task(type, data), enabled()) {
                reservations++
                assertEquals(expected.serializedSize.toLong(), it)
                assertEquals(9 + HelperProtocol.HELLO_BYTES + 9, input.position)
                AutoCloseable { releases.incrementAndGet() }
            }
            assertEquals(expected, owned.result)
            assertEquals(0, releases.get())
            assertEquals(1, reservations)
            assertEquals(1, launcher.lease!!.closes.get())
            val reader = HelperProtocol.Reader(ByteArrayInputStream(launcher.lease!!.sent.toByteArray()))
            val request = HelperProtocol.decodeRequest(reader.payload(reader.header(setOf(HelperProtocol.Kind.REQUEST))))
            assertEquals(Long.MIN_VALUE, request.id)
            assertEquals(type, request.type)
            assertEquals(data, request.data)
            owned.close()
            owned.close()
            assertEquals(1, releases.get())
        }
    }

    @Test fun execFramesAreRejectedForFsAndFallbackReservesSmallResultBeforeEncoding() = runBlocking {
        val launcher = Launcher { spec -> Lease(ByteArrayInputStream(frames {
            frame(HelperProtocol.Kind.HELLO, HelperProtocol.encodeHello(hello(spec)))
            frame(HelperProtocol.Kind.STDOUT, "private output".toByteArray())
        })) }
        val reservations = mutableListOf<Long>()
        val releases = AtomicInteger()
        val owned = McpFsBackend(launcher).execute(task(16, "{\"path\":\"/file\"}"), enabled()) {
            reservations += it
            AutoCloseable { releases.incrementAndGet() }
        }
        assertTrue(owned.result.successful)
        assertNotNull(json(owned.result).get("error"))
        assertFalse(owned.result.data.contains("private"))
        assertFalse(json(owned.result).has("total"))
        assertEquals(listOf(1024L), reservations)
        assertEquals(0, releases.get())
        owned.close()
        assertEquals(1, releases.get())
        assertEquals(1, launcher.lease!!.closes.get())
    }

    @Test fun resultBudgetRefusalIsNotRetriedAndStillReleasesHelper() = runBlocking {
        val expected = McpResultEncoder().encode(Long.MIN_VALUE, 17, FsReadResult())
        val launcher = Launcher { spec -> Lease(ByteArrayInputStream(frames {
            frame(HelperProtocol.Kind.HELLO, HelperProtocol.encodeHello(hello(spec)))
            frame(HelperProtocol.Kind.RESULT, expected.toByteArray())
        })) }
        val refused = IOException("test budget refusal")
        var reservations = 0
        try {
            McpFsBackend(launcher).execute(task(17, "{\"path\":\"/file\"}"), enabled()) {
                reservations++
                throw refused
            }
            fail("Expected reservation refusal")
        } catch (failure: IOException) { assertSame(refused, failure) }
        assertEquals(1, reservations)
        assertEquals(1, launcher.lease!!.closes.get())
    }

    @Test fun expiredFileDeadlineReturnsStructuredErrorWithoutLaunchingOrLargerFallback() = runBlocking {
        val launcher = Launcher { error("Must never launch") }
        val reserved = mutableListOf<Long>()
        val owned = McpFsBackend(launcher).execute(task(17, "{\"path\":\"/file\"}"), enabled(),
            HelperDeadline.afterMillis(0)) { reserved += it; AutoCloseable { } }
        owned.use {
            assertTrue(it.result.successful)
            assertEquals(FsError.TIMED_OUT.safeMessage, json(it.result).get("error").asString)
        }
        assertEquals(listOf(1024L), reserved)
        assertEquals(0, launcher.starts)
    }

    @Test fun helperRegistersBothFileTasksAndEmitsOnlyHelloThenResultBeforeCleanup() {
        for (type in listOf(16L, 17L)) {
            var opened = 0
            var closed = 0
            val files = object : FsAccess {
                override fun openRead(path: String): FsReadHandle {
                    opened++
                    assertEquals("/中文\n/file", path)
                    return object : FsReadHandle {
                        override fun stat() = FsMetadata(com.nezhahq.agent.core.mcp.FsEntryType.FILE, 0, 420, 0)
                        override fun pread(buffer: ByteArray, offset: Int, length: Int, fileOffset: Long) = 0
                        override fun close() { closed++ }
                    }
                }
                override fun openDirectory(path: String): FsDirectoryHandle {
                    opened++
                    assertEquals("/中文\n/file", path)
                    return object : FsDirectoryHandle {
                        override fun iterator(): Iterator<String> = emptyList<String>().iterator()
                        override fun lstat(name: String): FsMetadata = error("Empty directory")
                        override fun readlink(name: String): String = error("Empty directory")
                        override fun close() { closed++ }
                    }
                }
            }
            val input = HeldInput(frames {
                frame(HelperProtocol.Kind.REQUEST, HelperProtocol.encodeRequest(HelperProtocol.Request(
                    Long.MIN_VALUE, type, 1000, 0, "{\"path\":\"//中文\\n/other/../file/./\"}")))
            })
            val output = ByteArrayOutputStream()
            val terminated = AtomicInteger()
            val isolation = object : HelperIsolation {
                override fun establish(nonce: ByteArray) = HelperProtocol.Hello(HelperProtocol.VERSION, nonce, 10213, 10213, 1234, 1234)
                override fun terminate() {
                    if (terminated.incrementAndGet() == 1) {
                        val reader = HelperProtocol.Reader(ByteArrayInputStream(output.toByteArray()))
                        reader.payload(reader.header(setOf(HelperProtocol.Kind.HELLO)))
                        val terminal = TaskResult.parseFrom(reader.payload(reader.header(setOf(HelperProtocol.Kind.RESULT))))
                        assertTrue(terminal.successful)
                        assertEquals(type, terminal.type)
                        assertEquals(Long.MIN_VALUE, terminal.id)
                        assertFalse(json(terminal).has("error"))
                        assertEquals(output.size(), 9 + HelperProtocol.HELLO_BYTES + 9 + terminal.serializedSize)
                        input.close()
                    }
                }
            }
            HelperSession(isolation, HelperSession.handlers(File("."), File("."), files))
                .run(input, output, ByteArray(32), 1000)
            assertEquals(1, opened)
            assertEquals(1, closed)
            assertTrue(terminated.get() >= 1)
        }
    }

    private fun task(type: Long, data: String) = Task.newBuilder().setId(Long.MIN_VALUE).setType(type).setData(data).build()
    private fun json(result: TaskResult) = JsonParser.parseString(result.data).asJsonObject
    private fun enabled() = RemoteCapabilities(fileManagerEnabled = true)
    private fun hello(spec: HelperLaunchSpec) = HelperProtocol.Hello(HelperProtocol.VERSION, spec.nonce, 10213, 10213, 1234, 1234)
    private fun frames(block: HelperProtocol.Writer.() -> Unit) = ByteArrayOutputStream().also {
        HelperProtocol.Writer(it).block()
    }.toByteArray()

    private class Launcher(private val create: (HelperLaunchSpec) -> Lease) : HelperLauncher {
        override val backend = HelperBackend.APPLICATION
        var lease: Lease? = null
        var starts = 0
        override suspend fun launch(spec: HelperLaunchSpec): HelperProcessLease {
            starts++
            return create(spec).also { lease = it }
        }
    }

    private class Lease(override val stdout: InputStream) : HelperProcessLease {
        override val backend = HelperBackend.APPLICATION
        override val expectedUid = 10213
        val sent = ByteArrayOutputStream()
        override val stdin get() = sent
        override val stderr = ByteArrayInputStream(ByteArray(0))
        val closes = AtomicInteger()
        override fun acceptIdentity(hello: HelperProtocol.Hello) = Unit
        override fun close() { if (closes.incrementAndGet() == 1) stdout.close() }
    }

    private class CountedInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        val position: Int get() = pos
    }

    private class HeldInput(prefix: ByteArray) : InputStream() {
        private val bytes = ByteArrayInputStream(prefix)
        private val gate = java.lang.Object()
        private var closed = false
        override fun read(): Int {
            val next = bytes.read()
            if (next >= 0) return next
            synchronized(gate) {
                while (!closed) gate.wait()
                return -1
            }
        }
        override fun close() { synchronized(gate) { closed = true; gate.notifyAll() } }
    }
}
