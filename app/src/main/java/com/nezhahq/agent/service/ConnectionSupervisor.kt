package com.nezhahq.agent.service

import com.nezhahq.agent.grpc.GrpcConnection
import com.nezhahq.agent.grpc.GrpcConnectionState
import com.nezhahq.agent.grpc.GrpcTransportMode
import com.nezhahq.agent.util.Logger
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import java.security.cert.CertificateException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import proto.NezhaServiceGrpcKt.NezhaServiceCoroutineStub

/** Each attempt owns both streams; only cancellation ends the runtime's reconnect loop. */
internal class ConnectionSupervisor(
    private val connection: GrpcConnection,
    private val statusSink: (GrpcConnectionState, String) -> Unit,
    private val reportInitial: suspend (NezhaServiceCoroutineStub) -> Unit,
    private val reportState: suspend (NezhaServiceCoroutineStub, () -> Unit) -> Unit,
    private val dispatchTasks: suspend (NezhaServiceCoroutineStub) -> Unit,
    private val reconnectBackoff: ReconnectBackoffPolicy = ReconnectBackoffPolicy()
) {
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            try {
                showConnectingStatus()
                val stub = connection.stub ?: error("gRPC channel is not initialized.")
                reportInitial(stub)
                val connectionMarked = AtomicBoolean(false)
                coroutineScope {
                    launch {
                        reportState(stub) {
                            // A channel opening is not proof of a working session. Only the first
                            // state receipt in this attempt resets its failure schedule.
                            if (connectionMarked.compareAndSet(false, true)) {
                                reconnectBackoff.reset()
                                showConnectedStatus()
                            }
                        }
                    }
                    launch { dispatchTasks(stub) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                try {
                    handleConnectionFailure(failure)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (secondary: Throwable) {
                    // Even a reporting/disconnect failure must not leave a foreground runtime
                    // silently idle. Keep it on the same bounded backoff schedule.
                    Logger.e("AgentRuntime: 连接失败处理自身异常，仍将继续重连", secondary)
                    delay(reconnectBackoff.nextDelayMillis())
                }
            }
        }
    }

    private fun showConnectingStatus() {
        if (connection.transportMode == GrpcTransportMode.PLAINTEXT) {
            statusSink(GrpcConnectionState.PLAINTEXT_CONNECTING, "明文模式，正在连接...")
        } else {
            statusSink(GrpcConnectionState.CONNECTING, "正在连接...")
        }
    }

    private fun showConnectedStatus() {
        if (connection.transportMode == GrpcTransportMode.PLAINTEXT) {
            statusSink(GrpcConnectionState.PLAINTEXT_CONNECTED, "已连接到面板（明文传输）")
        } else {
            statusSink(GrpcConnectionState.CONNECTED, "已连接到面板")
        }
    }

    private fun showReconnectStatus() {
        if (connection.transportMode == GrpcTransportMode.PLAINTEXT) {
            statusSink(GrpcConnectionState.PLAINTEXT_RECONNECTING, "明文模式，正在重连...")
        } else {
            statusSink(GrpcConnectionState.RECONNECTING, "连接断开，正在重连...")
        }
    }

    private suspend fun handleConnectionFailure(failure: Throwable) {
        if (isAuthenticationFailure(failure)) {
            statusSink(GrpcConnectionState.AUTH_FAILED, "认证失败，请检查密钥和 UUID")
            Logger.e("AgentRuntime: 认证失败，请检查密钥和 UUID 配置", failure)
        } else {
            if (connection.transportMode == GrpcTransportMode.TLS && isGenuineTlsFailure(failure)) {
                Logger.e("Grpc: TLS 连接失败，将继续按 TLS 重试，不自动切换明文", failure)
            }
            showReconnectStatus()
            Logger.e("AgentRuntime: 连接中断，关闭通道后重试", failure)
        }
        connection.disconnect(preserveConnectionState = true)
        delay(reconnectBackoff.nextDelayMillis())
        runCatching { connection.connect() }
            .onFailure { Logger.e("AgentRuntime: gRPC 通道重建失败，将继续重试", it) }
    }

    private fun isAuthenticationFailure(throwable: Throwable): Boolean =
        throwable.causeSequence().any { cause ->
            when (cause) {
                is StatusException -> cause.status.code == Status.Code.UNAUTHENTICATED
                is StatusRuntimeException -> cause.status.code == Status.Code.UNAUTHENTICATED
                else -> cause.message?.contains("UNAUTHENTICATED", ignoreCase = true) == true
            }
        }

    private fun isGenuineTlsFailure(throwable: Throwable): Boolean =
        throwable.causeSequence().any { cause ->
            cause is SSLException || cause is CertificateException ||
                cause.message?.let { message ->
                    val normalized = message.lowercase()
                    TLS_FAILURE_MARKERS.any { it in normalized }
                } == true
        }

    private fun Throwable.causeSequence(): Sequence<Throwable> =
        generateSequence(this) { it.cause }

    private companion object {
        val TLS_FAILURE_MARKERS = listOf(
            "ssl", "tls", "handshake", "certificate", "trust anchor", "peer unverified", "hostname",
            "not an ssl/tls record", "unsupported or unrecognized ssl message",
            "unable to find valid certification path"
        )
    }
}
