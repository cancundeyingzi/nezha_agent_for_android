package com.nezhahq.agent.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.task.TaskTypes
import com.nezhahq.agent.grpc.GrpcConnection
import com.nezhahq.agent.grpc.GrpcConnectionState
import com.nezhahq.agent.grpc.GrpcTransportMode
import com.nezhahq.agent.service.AgentRuntime
import com.nezhahq.agent.service.RuntimeKeepAliveFactory
import com.nezhahq.agent.service.RuntimeNetworkRegistrar
import com.nezhahq.agent.service.RuntimeNetworkRegistration
import com.nezhahq.agent.service.keepalive.KeepAliveController
import com.nezhahq.agent.service.keepalive.KeepAliveResource
import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.Status
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import proto.Nezha
import proto.NezhaServiceGrpcKt.NezhaServiceCoroutineStub

@RunWith(AndroidJUnit4::class)
class RuntimeHttpCancellationRegressionTest {
    @get:Rule val evidence = DeviceEvidenceRule()

    @Test fun runtimeGoesOnlineWithoutGeoIpAndStopCancelsTheRealInFlightHttpProbe() = runBlocking {
        val requestArrived = CompletableDeferred<Unit>()
        val peerClosed = CompletableDeferred<Unit>()
        val online = CompletableDeferred<Unit>()
        val geoStarted = CompletableDeferred<Unit>()
        val geoStopped = CompletableDeferred<Unit>()
        var peer: Socket? = null
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 15_000 }
        val connection = ScriptedConnection("http://127.0.0.1:${server.localPort}/waiting")
        val noKeepAlive = object : KeepAliveResource {
            override suspend fun setEnabled(enabled: Boolean) = Unit
            override suspend fun close() = Unit
        }
        val runtime = AgentRuntime(deviceApplication,
            AgentConfig("127.0.0.1", 12345, "synthetic", "http-stop-regression", false, false),
            statusSink = { state, _ -> if (state == GrpcConnectionState.PLAINTEXT_CONNECTED) online.complete(Unit) },
            grpcConnection = connection,
            networkRegistrar = RuntimeNetworkRegistrar { RuntimeNetworkRegistration {} },
            keepAliveFactory = RuntimeKeepAliveFactory { _, _ ->
                KeepAliveController(noKeepAlive, noKeepAlive, noKeepAlive, noKeepAlive)
            }, fetchGeoIp = {
                geoStarted.complete(Unit)
                try { awaitCancellation() } finally { geoStopped.complete(Unit) }
            })
        val worker = thread(isDaemon = true, name = "device-http-peer") {
            try {
                server.accept().use { socket ->
                    peer = socket
                    socket.soTimeout = 15_000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    requestArrived.complete(Unit)
                    try { check(socket.getInputStream().read() == -1) }
                    catch (_: SocketException) { /* Reset is also cancellation. */ }
                    peerClosed.complete(Unit)
                }
            } catch (failure: Throwable) {
                requestArrived.completeExceptionally(failure)
                peerClosed.completeExceptionally(failure)
            }
        }
        try {
            runtime.start()
            withTimeout(15_000) { online.await(); geoStarted.await(); requestArrived.await() }
            assertFalse(geoStopped.isCompleted)
            assertEquals(1, connection.connects)
            // The real request is waiting for response headers. The normal request deadline is 15 s.
            withTimeout(3_000) { runtime.stop(); peerClosed.await(); geoStopped.await() }
            assertTrue(connection.closed)
            assertEquals(0, connection.disconnects)
        } finally {
            peer?.close()
            server.close()
            withTimeout(10_000) { runtime.stop() }
            worker.join(5_000)
            assertFalse(worker.isAlive)
        }
    }

    /** Real production runtime and HTTP socket; only the Dashboard RPC transport is scripted. */
    private class ScriptedConnection(url: String) : GrpcConnection {
        var connects = 0
        var disconnects = 0
        var closed = false
        override val transportMode = GrpcTransportMode.PLAINTEXT
        override val stub = NezhaServiceCoroutineStub(object : Channel() {
            override fun authority() = "synthetic-dashboard"
            override fun <ReqT : Any?, RespT : Any?> newCall(
                methodDescriptor: MethodDescriptor<ReqT, RespT>, callOptions: CallOptions
            ): ClientCall<ReqT, RespT> = object : ClientCall<ReqT, RespT>() {
                private lateinit var listener: Listener<RespT>
                private val method = methodDescriptor.fullMethodName.substringAfterLast('/')
                private val done = AtomicBoolean()
                private val taskSent = AtomicBoolean()
                private var message: ReqT? = null
                override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                    listener = responseListener
                    listener.onReady()
                }
                override fun isReady() = !done.get()
                override fun request(numMessages: Int) {
                    if (method == "RequestTask" && taskSent.compareAndSet(false, true)) {
                        deliver(Nezha.Task.newBuilder().setId(1).setType(TaskTypes.HTTP_GET).setData(url).build())
                    }
                }
                override fun sendMessage(value: ReqT) {
                    message = value
                    if (method == "ReportSystemState") deliver(Nezha.Receipt.getDefaultInstance())
                }
                override fun halfClose() {
                    when (method) {
                        "ReportSystemInfo2" -> { deliver(Nezha.Uint64Receipt.getDefaultInstance()); finish(Status.OK) }
                        "ReportGeoIP" -> { deliver(checkNotNull(message)); finish(Status.OK) }
                    }
                }
                override fun cancel(message: String?, cause: Throwable?) = finish(Status.CANCELLED)
                @Suppress("UNCHECKED_CAST")
                private fun deliver(value: Any) { if (!done.get()) listener.onMessage(value as RespT) }
                private fun finish(status: Status) {
                    if (done.compareAndSet(false, true)) listener.onClose(status, Metadata())
                }
            }
        })
        override fun connect() { connects++ }
        override fun disconnect(preserveConnectionState: Boolean) { disconnects++ }
        override fun close() { closed = true }
    }
}
