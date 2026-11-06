package com.nezhahq.agent.executor

import com.nezhahq.agent.SilentLoggerRule
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.core.task.TaskTypes
import com.nezhahq.agent.util.awaitResult
import com.nezhahq.agent.util.awaitResultWithin
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import proto.Nezha.Task

class HttpProbeTest {
    @get:Rule val logger = SilentLoggerRule()

    @Test fun actualHttpTaskReportsSuccessAndHttpFailure() = runBlocking {
        for (code in listOf(204, 503)) LocalServer { socket ->
            socket.getOutputStream().write("HTTP/1.1 $code Test\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        }.use { server ->
            val result = withTimeout(5_000) { TaskExecutor.executeTask(task(server.url), RemoteCapabilities()) }
            assertEquals(code == 204, result.successful)
            assertEquals(1L, result.id)
            assertTrue(server.received.isCompleted)
        }
    }

    @Test fun cancellingAnActualHttpTaskClosesThePeerAndJoinsWellBeforeItsNetworkTimeout() = runBlocking {
        val peerClosed = CompletableDeferred<Unit>()
        LocalServer { socket ->
            try {
                assertEquals(-1, socket.getInputStream().read())
            } catch (_: SocketException) {
                // Connection reset also proves that the client aborted the underlying socket.
            }
            peerClosed.complete(Unit)
        }.use { server ->
            val running = launch { TaskExecutor.executeTask(task(server.url), RemoteCapabilities()); fail("cancelled task returned") }
            try {
                withTimeout(5_000) { server.received.await() }
                withTimeout(2_000) { running.cancelAndJoin(); peerClosed.await() }
            } finally { withTimeout(5_000) { running.cancelAndJoin() } }
            server.assertNoFailure()
        }
    }

    @Test fun totalDeadlineAlsoCancelsACallStillQueuedInsideOkHttp() = runBlocking {
        LocalServer { socket ->
            try { socket.getInputStream().read() } catch (_: SocketException) {}
        }.use { server ->
            val client = createMonitoringClient().newBuilder().dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 1
                maxRequestsPerHost = 1
            }).build()
            val first = client.newCall(Request.Builder().url(server.url).build())
            val blocker = launch { first.awaitResult { it.code } }
            try {
                withTimeout(5_000) { server.received.await() }
                val queued = client.newCall(Request.Builder().url(server.url).build())
                val failure = runCatching { withTimeout(2_000) { queued.awaitResultWithin(100) { it.code } } }.exceptionOrNull()
                assertTrue("Expected the coroutine deadline, got $failure", failure is SocketTimeoutException)
                assertTrue(queued.isCanceled())
            } finally {
                withTimeout(5_000) { blocker.cancelAndJoin() }
                client.dispatcher.cancelAll()
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test fun tricklingHeadersCannotExtendTheTotalDeadline() = runBlocking {
        val writes = AtomicInteger()
        LocalServer { socket ->
            val output = socket.getOutputStream()
            output.write("HTTP/1.1 200 OK\r\nX-Trickle: ".toByteArray())
            try {
                while (!socket.isClosed) {
                    output.write('a'.code); output.flush(); writes.incrementAndGet()
                    Thread.sleep(20)
                }
            } catch (_: IOException) { /* Client deadline closed the socket. */ }
        }.use { server ->
            val client = createMonitoringClient(callTimeoutMillis = 300)
            try {
                val call = client.newCall(Request.Builder().url(server.url).build())
                val failure = runCatching { withTimeout(3_000) { call.awaitResult { it.code } } }.exceptionOrNull()
                assertTrue("Expected OkHttp deadline, got $failure", failure is IOException)
                assertTrue(call.isCanceled())
                assertTrue("the read timeout alone must have stayed satisfied", writes.get() >= 2)
                assertEquals(15_000, createMonitoringClient().callTimeoutMillis)
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    private fun task(url: String) = Task.newBuilder().setId(1).setType(TaskTypes.HTTP_GET).setData(url).build()

    private class LocalServer(private val respond: (Socket) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5_000 }
        val url = "http://127.0.0.1:${server.localPort}/probe"
        val received = CompletableDeferred<Unit>()
        @Volatile private var peer: Socket? = null
        @Volatile private var failure: Throwable? = null
        private val worker = thread(isDaemon = true, name = "probe-test-server") {
            try {
                server.accept().use { socket ->
                    peer = socket
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    received.complete(Unit)
                    respond(socket)
                }
            } catch (error: Throwable) {
                failure = error
                received.completeExceptionally(error)
            }
        }
        fun assertNoFailure() { failure?.let { throw AssertionError("loopback server failed", it) } }
        override fun close() {
            peer?.close()
            server.close()
            worker.join(5_000)
            assertFalse("server thread leaked", worker.isAlive)
        }
    }
}
