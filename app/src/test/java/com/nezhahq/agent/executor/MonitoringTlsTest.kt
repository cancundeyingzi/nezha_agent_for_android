package com.nezhahq.agent.executor

import com.nezhahq.agent.SilentLoggerRule
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.core.task.TaskTypes
import java.io.IOException
import java.net.InetAddress
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import proto.Nezha.Task
import kotlin.concurrent.thread

class MonitoringTlsTest {
    @get:Rule
    val silentLogger = SilentLoggerRule()

    @Test
    fun aSelfSignedTaskFailsBeforeAnyHttpRequestAndDoesNotRetryInsecurely() = runBlocking {
        LocalTlsServer().use { server ->
            val secret = "tls-probe-secret"
            val task = Task.newBuilder()
                .setId(101)
                .setType(TaskTypes.HTTP_GET)
                .setData("https://localhost:${server.port}/?token=$secret")
                .build()

            val result = TaskExecutor.executeTask(task, RemoteCapabilities())

            assertFalse(result.successful)
            assertTrue("certificate failure needs an actionable result", result.data.isNotBlank())
            assertEquals("unverified retry sent an HTTP request", 0, server.httpRequests.get())
            assertTrue(silentLogger.messages.any { "TaskID=101" in it })
            assertTrue(silentLogger.messages.none { secret in it || "token=" in it })
        }
    }

    @Test
    fun aTrustedCertificateStillRequiresAMatchingHostname() {
        LocalTlsServer().use { server ->
            val client = createMonitoringClient().newBuilder()
                .sslSocketFactory(server.trustedClientContext.socketFactory, server.trustManager)
                .build()
            val request = Request.Builder().url("https://127.0.0.1:${server.port}/").build()

            assertThrows(SSLPeerUnverifiedException::class.java) {
                client.newCall(request).execute().use { }
            }
            assertEquals(0, server.httpRequests.get())
        }
    }

    @Test
    fun theSameTrustedCertificateSucceedsForItsActualHostname() {
        LocalTlsServer().use { server ->
            val client = createMonitoringClient().newBuilder()
                .sslSocketFactory(server.trustedClientContext.socketFactory, server.trustManager)
                .build()
            val request = Request.Builder().url("https://localhost:${server.port}/").build()

            client.newCall(request).execute().use { assertTrue(it.isSuccessful) }
            assertEquals(1, server.httpRequests.get())
        }
    }

    private class LocalTlsServer : AutoCloseable {
        val httpRequests = AtomicInteger()
        private val closed = AtomicBoolean()
        private val acceptedSocket = AtomicReference<SSLSocket?>()
        private val keys = KeyStore.getInstance("PKCS12").apply {
            // Test-only localhost fixture, valid 2020-2119; password is public fixture metadata.
            // keytool -genkeypair -alias monitor-localhost -keyalg RSA -keysize 2048
            //   -dname "CN=localhost, OU=Test fixture, O=Nezha Android Tests" -ext SAN=dns:localhost
            //   -startdate "2020/01/01 00:00:00" -validity 36500 -storetype PKCS12
            //   -keystore monitor-localhost.p12 -storepass changeit -keypass changeit
            MonitoringTlsTest::class.java.getResourceAsStream("/monitor-localhost.p12").use {
                requireNotNull(it) { "Missing localhost TLS fixture" }
                load(it, "changeit".toCharArray())
            }
        }
        private val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keys, "changeit".toCharArray())
        }
        private val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("localhost", keys.getCertificate("monitor-localhost"))
            })
        }
        val trustManager = trustManagers.trustManagers.filterIsInstance<X509TrustManager>().single()
        val trustedClientContext: SSLContext = SSLContext.getInstance("TLS").apply {
            init(null, trustManagers.trustManagers, null)
        }
        private val server = SSLContext.getInstance("TLS").apply {
            init(keyManagers.keyManagers, null, null)
        }.serverSocketFactory.createServerSocket(0, 5, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        private val listener = thread(name = "monitor-tls-test", isDaemon = true) {
            while (!closed.get()) {
                val socket = try {
                    server.accept() as SSLSocket
                } catch (_: IOException) {
                    break
                }
                acceptedSocket.set(socket)
                try {
                    socket.use {
                        // Also closes an accepted socket published just after close() read null.
                        if (closed.get()) return@use
                        it.soTimeout = SOCKET_TIMEOUT_MILLIS
                        it.startHandshake()
                        if (readRequestHeaders(it)) {
                            httpRequests.incrementAndGet()
                            it.outputStream.apply {
                                write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray(Charsets.US_ASCII))
                                flush()
                            }
                        }
                    }
                } catch (_: IOException) {
                    // Certificate/hostname rejection closes the socket before an HTTP request.
                } finally {
                    acceptedSocket.compareAndSet(socket, null)
                }
            }
        }
        val port: Int get() = server.localPort

        private fun readRequestHeaders(socket: SSLSocket): Boolean {
            val input = socket.inputStream.buffered()
            val delimiter = byteArrayOf(13, 10, 13, 10)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SOCKET_TIMEOUT_MILLIS.toLong())
            var matched = 0
            repeat(MAX_HEADER_BYTES) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw IOException("Test HTTP header deadline exceeded")
                socket.soTimeout = TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1).toInt()
                val next = input.read()
                if (next == -1) return false
                matched = when (next) {
                    delimiter[matched].toInt() -> matched + 1
                    13 -> 1
                    else -> 0
                }
                if (matched == delimiter.size) return true
            }
            throw IOException("Test HTTP header exceeds limit")
        }

        override fun close() {
            closed.set(true)
            try {
                server.close()
            } finally {
                try {
                    acceptedSocket.getAndSet(null)?.close()
                } finally {
                    listener.join(CLOSE_TIMEOUT_MILLIS)
                    check(!listener.isAlive) { "Test TLS listener did not terminate" }
                }
            }
        }

        private companion object {
            const val SOCKET_TIMEOUT_MILLIS = 3_000
            const val MAX_HEADER_BYTES = 8_192
            const val CLOSE_TIMEOUT_MILLIS = 5_000L
        }
    }
}
