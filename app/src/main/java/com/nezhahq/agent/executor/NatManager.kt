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
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NAT 内网穿透管理器（符合 Nezha V1 协议）。
 *
 * ## 安全与性能优化
 * - 纯协程设计：使用 coroutineScope 完全解藕，避免协程生命周期泄漏。
 * - 高效缓冲：直接使用 ByteString 转化 ByteBuffer 减少冗余的 ByteArray 深拷贝分配开销。
 * - IPv6 容错：解析目标 Host 时增加对 IPv6 安全括号[] 的去除兼容。
 */
class NatManager(
    private val stub: NezhaServiceCoroutineStub,
    private val streamId: String,
    private val host: String
) {
    private companion object {
        val STREAM_MAGIC = byteArrayOf(0xFF.toByte(), 0x05, 0xFF.toByte(), 0x05)
    }

    private var socket: Socket? = null
    private var socketInput: InputStream? = null
    private var socketOutput: OutputStream? = null
    private val outputChannel = Channel<Nezha.IOStreamData>(Channel.BUFFERED)
    private val closed = AtomicBoolean(false)

    suspend fun run() {
        try {
            coroutineScope {
                // 1. 发送 StreamID 握手帧
                val header = STREAM_MAGIC + streamId.toByteArray(Charsets.UTF_8)
                val headerMsg = Nezha.IOStreamData.newBuilder()
                    .setData(ByteString.copyFrom(header))
                    .build()
                outputChannel.send(headerMsg)
                Logger.i("NatManager: 已发送 StreamID 握手帧 (StreamID=$streamId)")

                // 2. 建立到目标 Host 的本地 TCP Socket 连接
                val (targetHost, targetPort) = parseHostPort(host)
                withContext(Dispatchers.IO) {
                    socket = Socket(targetHost, targetPort)
                    socketInput = socket!!.getInputStream()
                    socketOutput = socket!!.getOutputStream()
                }
                Logger.i("NatManager: 已成功连接到本地目标 $host (StreamID=$streamId)")

                // 3. 启动 Socket 读取协程
                launch(Dispatchers.IO) {
                    try {
                        readLocalLoop()
                    } finally {
                        // 一旦结束读操作，立即取消整个NAT Session的关联协程
                        this@coroutineScope.cancel()
                    }
                }

                // 4. 启动心跳保活协程
                launch {
                    keepAliveLoop()
                }

                // 5. 建立 IOStream 双向流持续接收数据（阻塞当前协程）
                stub.iOStream(outputFlow()).collect { ioData ->
                    val bytes = ioData.data.toByteArray()
                    if (bytes.isNotEmpty()) {
                        writeToSocket(bytes)
                    }
                }
                
                // Dashboard端主动结束了gRPC流
                this@coroutineScope.cancel()
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Logger.i("NatManager: NAT 会话出现异常或自行结束 (StreamID=$streamId): ${e.message}")
            }
        } finally {
            close()
        }
    }

    private fun outputFlow(): Flow<Nezha.IOStreamData> = flow {
        for (data in outputChannel) { emit(data) }
    }

    fun close() {
        if (closed.getAndSet(true)) return
        Logger.i("NatManager: 正在关闭 NAT 会话 (StreamID=$streamId)")
        try { socketOutput?.close() } catch (_: Exception) {}
        try { socketInput?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        outputChannel.close()
        socket = null; socketInput = null; socketOutput = null
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
        } catch (_: Exception) {}
    }

    private fun writeToSocket(data: ByteArray) {
        try {
            val output = socketOutput ?: return
            output.write(data)
            output.flush()
        } catch (e: Exception) {
            Logger.e("NatManager: 写入本地 Socket 失败 (StreamID=$streamId)", e)
            close()
        }
    }

    private fun parseHostPort(hostPort: String): Pair<String, Int> {
        val lastColon = hostPort.lastIndexOf(':')
        require(lastColon > 0 && lastColon < hostPort.length - 1) {
            "无效的 Host 格式: $hostPort（期望格式: host:port）"
        }
        var h = hostPort.substring(0, lastColon)
        // 兼容 IPv6 的标准方括号格式，如: [::1]:8080
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length - 1)
        }
        val p = hostPort.substring(lastColon + 1).toIntOrNull()
            ?: throw IllegalArgumentException("无效的端口号: ${hostPort.substring(lastColon + 1)}")
        return Pair(h, p)
    }
}
