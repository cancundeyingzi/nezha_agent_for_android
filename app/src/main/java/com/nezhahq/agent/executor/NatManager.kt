package com.nezhahq.agent.executor

import com.nezhahq.agent.util.Logger
import com.google.protobuf.ByteString
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import proto.Nezha
import proto.NezhaServiceGrpcKt.NezhaServiceCoroutineStub
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * NAT 内网穿透管理器（符合 Nezha V1 协议）。
 *
 * ## 安全与性能优化
 * - 结构化生命周期：取消时先关闭 Socket，再等待阻塞读取协程结束。
 * - 有界输出：限制本地读取领先于 gRPC 发送的帧数。
 * - 连接保护：本地目标连接支持协程取消并设置绝对超时。
 * - IPv6 容错：解析目标 Host 时增加对 IPv6 安全括号[] 的去除兼容。
 */
class NatManager(
    private val stub: NezhaServiceCoroutineStub,
    private val streamId: String,
    private val host: String
) {
    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val INPUT_BUFFER_CAPACITY = 8
        const val OUTPUT_BUFFER_CAPACITY = 8
        val STREAM_MAGIC = byteArrayOf(0xFF.toByte(), 0x05, 0xFF.toByte(), 0x05)
    }

    @Volatile private var socket: Socket? = null
    @Volatile private var socketInput: InputStream? = null
    @Volatile private var socketOutput: OutputStream? = null
    private val inputChannel = Channel<ByteArray>(INPUT_BUFFER_CAPACITY)
    private val outputChannel = Channel<Nezha.IOStreamData>(OUTPUT_BUFFER_CAPACITY)
    private val closed = AtomicBoolean(false)

    suspend fun run() {
        try {
            resourceSessionScope(::close) session@{
                // 1. 发送 StreamID 握手帧
                val header = STREAM_MAGIC + streamId.toByteArray(Charsets.UTF_8)
                val headerMsg = Nezha.IOStreamData.newBuilder()
                    .setData(ByteString.copyFrom(header))
                    .build()
                outputChannel.send(headerMsg)
                Logger.i("NatManager: 已发送 StreamID 握手帧 (StreamID=$streamId)")

                // 2. 建立到目标 Host 的本地 TCP Socket 连接
                connectToTarget()
                Logger.i("NatManager: 已成功连接到本地目标 $host (StreamID=$streamId)")

                // 3. 启动 Socket 读取协程
                launch(Dispatchers.IO) {
                    try {
                        readLocalLoop()
                    } finally {
                        this@session.cancel()
                    }
                }

                // 4. 单写者顺序写入 Socket；有界队列避免远端输入无限堆积。
                launch(Dispatchers.IO) {
                    try {
                        writeLocalLoop()
                    } finally {
                        this@session.cancel()
                    }
                }

                // 5. 启动心跳保活协程
                launch {
                    keepAliveLoop()
                }

                // 6. 建立 IOStream 双向流持续接收数据（阻塞当前协程）
                stub.iOStream(outputFlow()).collect { ioData ->
                    val bytes = ioData.data.toByteArray()
                    if (bytes.isNotEmpty()) {
                        inputChannel.send(bytes)
                    }
                }

                // Dashboard端主动结束了gRPC流
                this@session.cancel()
            }
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
        } catch (e: Exception) {
            Logger.i("NatManager: NAT 会话出现异常或自行结束 (StreamID=$streamId): ${e.message}")
        }
    }

    private suspend fun connectToTarget() {
        val (targetHost, targetPort) = parseHostPort(host)
        val candidate = Socket()
        socket = candidate

        try {
            withContext(Dispatchers.IO) {
                connectCancellable(
                    candidate,
                    InetSocketAddress(targetHost, targetPort)
                )
                currentCoroutineContext().ensureActive()
                socketInput = candidate.getInputStream()
                socketOutput = candidate.getOutputStream()
            }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private suspend fun connectCancellable(
        candidate: Socket,
        address: InetSocketAddress
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        continuation.invokeOnCancellation {
            try {
                candidate.close()
            } catch (_: Exception) {
            }
        }

        try {
            candidate.connect(address, CONNECT_TIMEOUT_MS)
            if (continuation.isActive) {
                continuation.resume(Unit)
            } else {
                candidate.close()
            }
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    }

    private fun outputFlow(): Flow<Nezha.IOStreamData> = flow {
        for (data in outputChannel) { emit(data) }
    }

    fun close() {
        if (closed.getAndSet(true)) return
        Logger.i("NatManager: 正在关闭 NAT 会话 (StreamID=$streamId)")
        inputChannel.close()
        try { socket?.close() } catch (_: Exception) {}
        try { socketOutput?.close() } catch (_: Exception) {}
        try { socketInput?.close() } catch (_: Exception) {}
        outputChannel.close()
        socket = null
        socketInput = null
        socketOutput = null
    }

    private suspend fun readLocalLoop() = withContext(Dispatchers.IO) {
        val buffer = ByteArray(10240)
        try {
            while (!closed.get()) {
                val bytesRead = socketInput?.read(buffer) ?: -1
                if (bytesRead == -1) break
                if (bytesRead > 0) {
                    sendToStream(ByteString.copyFrom(buffer, 0, bytesRead))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!closed.get()) {
                Logger.e("NatManager: 读取本地 Socket 数据异常 (StreamID=$streamId)", e)
            }
        }
    }

    private suspend fun keepAliveLoop() {
        try {
            while (!closed.get()) {
                delay(30_000)
                if (closed.get()) break
                sendToStream(ByteString.EMPTY)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
    }

    private suspend fun sendToStream(byteString: ByteString) {
        if (closed.get()) return
        try {
            outputChannel.send(
                Nezha.IOStreamData.newBuilder()
                    .setData(byteString)
                    .build()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
    }

    private suspend fun writeLocalLoop() {
        try {
            for (data in inputChannel) {
                val output = socketOutput ?: break
                output.write(data)
                output.flush()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!closed.get()) {
                Logger.e("NatManager: 写入本地 Socket 失败 (StreamID=$streamId)", e)
            }
        }
    }

    /**
     * 解析面板下发的目标 `host:port`。
     *
     * 规则与 TCP Ping 共用 [HostPort]，避免两处对同一格式给出不同答案；NAT 目标必须
     * 显式带端口（转发到哪个端口没有合理的猜测），所以不传 defaultPort。
     */
    private fun parseHostPort(hostPort: String): Pair<String, Int> =
        when (val result = HostPort.parse(hostPort)) {
            is HostPort.Result.Parsed -> Pair(result.host, result.port)
            is HostPort.Result.Invalid -> throw IllegalArgumentException(result.reason)
        }
}
