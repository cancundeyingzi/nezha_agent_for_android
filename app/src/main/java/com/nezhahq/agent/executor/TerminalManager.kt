package com.nezhahq.agent.executor

import android.content.Context
import android.os.Environment
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import com.google.protobuf.ByteString
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import proto.Nezha
import proto.NezhaServiceGrpcKt.NezhaServiceCoroutineStub
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * IOStream 终端会话管理器（符合 Nezha V1 协议）。
 *
 * ## 协议规范（从官方 Go Agent 逆向分析）
 * 1. Dashboard 通过 Task (type=8) 触发终端，Task.data 包含 `{"StreamID":"xxx"}`
 * 2. Agent 收到 Task 后调用 `stub.iOStream()` 建立双向流
 * 3. 第一条消息必须是 **魔术头** `0xff, 0x05, 0xff, 0x05` + StreamID 字节
 * 4. 后续接收的 IOStreamData.data[0] 为消息类型：
 *    - `0x00`：终端输入（data[1:] 是实际键盘数据）
 *    - `0x01`：窗口大小调整（data[1:] 是 JSON `{"Cols":80,"Rows":24}`）
 * 5. 每 30 秒发送空 IOStreamData 作为心跳
 *
 * ## 行纪律（Line Discipline）
 * Android 无法分配 PTY（需 JNI），因此内置软件行纪律：
 * 回显、退格、Ctrl+C/U、ESC 序列过滤、自动命令提示符。
 */
class TerminalManager(
    private val context: Context,
    private val stub: NezhaServiceCoroutineStub,
    private val streamId: String
) {
    private companion object {
        const val PROMPT = "nezha:/ $ "
        const val AGENT_CMD_PREFIX = "@agent "
        const val AGENT_CMD_EXACT = "@agent"
        /** IOStream StreamID 魔术头（协议规定） */
        val STREAM_MAGIC = byteArrayOf(0xFF.toByte(), 0x05, 0xFF.toByte(), 0x05)
    }

    private enum class InputState { NORMAL, ESC, CSI }

    private var process: Process? = null
    private var shellInput: OutputStream? = null
    private val outputChannel = Channel<Nezha.IOStreamData>(Channel.BUFFERED)
    private val commandHandler = AgentCommandHandler(context)
    private val closed = AtomicBoolean(false)
    private val lineBuffer = StringBuilder()
    private var inputState = InputState.NORMAL
    private val awaitingPrompt = AtomicBoolean(false)

    /**
     * 启动终端会话（完整的 IOStream 生命周期管理）。
     *
     * 此方法会阻塞直到终端会话结束（用户关闭终端或连接断开）。
     * 应在独立协程中调用。
     */
    suspend fun run() {
        try {
            coroutineScope {
                // 1. 选择 Shell 类型并启动子进程
                //    优先使用 su（Root），其次 Shizuku，最后普通 sh
                //    显式切入 IO 调度器，避免 Thread.sleep / ProcessBuilder.start 阻塞非 IO 线程
                val (shellProcess, shellType) = withContext(Dispatchers.IO) {
                    startShellProcess()
                }
                process = shellProcess
                shellInput = process!!.outputStream
                Logger.i("TerminalManager: Shell 子进程已启动 (type=$shellType, StreamID=$streamId)")

                // 2. 启动 stdout 读取协程
                launch(Dispatchers.IO) {
                    try {
                        readLoop(process!!.inputStream)
                    } finally {
                        this@coroutineScope.cancel()
                    }
                }

                // 3. 发送 StreamID 魔术头（协议握手）
                val header = STREAM_MAGIC + streamId.toByteArray(Charsets.UTF_8)
                val headerMsg = Nezha.IOStreamData.newBuilder()
                    .setData(ByteString.copyFrom(header))
                    .build()
                outputChannel.send(headerMsg)

                // 4. 发送欢迎横幅（显示当前 Shell 权限类型）
                val typeLabel = when (shellType) {
                    "su"      -> "Root"
                    "shizuku" -> "Shizuku (ADB)"
                    else      -> "普通"
                }
                sendOutput("\r\n========== Nezha Agent Terminal ==========\r\n")
                sendOutput("  模式: $typeLabel | 输入 @agent help 查看虚拟指令\r\n")
                sendOutput("==========================================\r\n\r\n")
                sendOutput(PROMPT)

                // 5. 启动心跳协程
                launch { keepAliveLoop() }

                // 6. 建立 IOStream 双向流并处理输入
                stub.iOStream(outputFlow()).collect { ioData ->
                    val bytes = ioData.data.toByteArray()
                    if (bytes.isEmpty()) return@collect // 心跳空包，忽略

                    when (bytes[0].toInt() and 0xFF) {
                        0x00 -> { // 终端输入数据
                            if (bytes.size > 1) {
                                handleInput(bytes.copyOfRange(1, bytes.size))
                            }
                        }
                        0x01 -> { // 窗口大小调整（当前无 PTY，忽略）
                            // 未来如果实现 PTY 可在此调整窗口大小
                        }
                        else -> {
                            // 未知类型，忽略
                        }
                    }
                }
                
                this@coroutineScope.cancel()
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Logger.i("TerminalManager: 终端会话结束 (StreamID=$streamId): ${e.message}")
            }
        } finally {
            close()
        }
    }

    /** 获取终端输出 Flow，用于 gRPC IOStream 发送。 */
    fun outputFlow(): Flow<Nezha.IOStreamData> = flow {
        for (data in outputChannel) { emit(data) }
    }

    /** 关闭终端会话。 */
    fun close() {
        if (closed.getAndSet(true)) return
        Logger.i("TerminalManager: 正在关闭终端会话 (StreamID=$streamId)")
        try { shellInput?.close() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        outputChannel.close()
        process = null; shellInput = null
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 内置行纪律 (Line Discipline)
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun handleInput(data: ByteArray) {
        if (closed.get()) return
        for (byte in data) {
            val b = byte.toInt() and 0xFF
            when (inputState) {
                InputState.ESC -> {
                    inputState = if (b == 0x5B) InputState.CSI else InputState.NORMAL
                }
                InputState.CSI -> {
                    if (b in 0x40..0x7E) inputState = InputState.NORMAL
                }
                InputState.NORMAL -> when (b) {
                    0x1B -> inputState = InputState.ESC
                    0x0D -> { // Enter
                        sendOutput("\r\n")
                        val cmd = lineBuffer.toString().trim()
                        lineBuffer.clear()
                        when {
                            cmd.startsWith(AGENT_CMD_PREFIX) || cmd == AGENT_CMD_EXACT -> {
                                handleAgentCommand(cmd)
                                sendOutput(PROMPT)
                            }
                            cmd.isNotEmpty() -> {
                                awaitingPrompt.set(true)
                                writeToShell((cmd + "\n").toByteArray())
                            }
                            else -> sendOutput(PROMPT)
                        }
                    }
                    0x0A -> { /* 忽略 LF（CR+LF 场景） */ }
                    0x7F, 0x08 -> { // Backspace
                        if (lineBuffer.isNotEmpty()) {
                            lineBuffer.deleteCharAt(lineBuffer.length - 1)
                            sendOutput("\b \b")
                        }
                    }
                    0x03 -> { // Ctrl+C
                        lineBuffer.clear()
                        sendOutput("^C\r\n")
                        if (awaitingPrompt.get()) {
                            writeToShell(byteArrayOf(0x03))
                        } else {
                            sendOutput(PROMPT)
                        }
                    }
                    0x15 -> { // Ctrl+U
                        if (lineBuffer.isNotEmpty()) {
                            sendOutput("\b \b".repeat(lineBuffer.length))
                            lineBuffer.clear()
                        }
                    }
                    0x04 -> { // Ctrl+D
                        if (lineBuffer.isEmpty()) {
                            sendOutput("\r\n[使用 exit 命令退出]\r\n")
                            sendOutput(PROMPT)
                        }
                    }
                    in 0x20..0x7E -> { // 可打印 ASCII
                        lineBuffer.append(b.toChar())
                        sendOutput(byteArrayOf(byte))
                    }
                }
            }
        }
    }

    private suspend fun handleAgentCommand(line: String) {
        val cmd = if (line == AGENT_CMD_EXACT) "" else line.removePrefix(AGENT_CMD_PREFIX).trim()
        try {
            sendOutput(commandHandler.execute(cmd))
        } catch (e: Exception) {
            Logger.e("TerminalManager: 虚拟指令执行异常", e)
            sendOutput("❌ 指令执行异常: ${e.message}\r\n")
        }
    }

    private fun writeToShell(data: ByteArray) {
        try { shellInput?.let { it.write(data); it.flush() } }
        catch (e: Exception) { Logger.e("TerminalManager: 写入 Shell stdin 失败", e) }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Shell 输出读取 + 心跳
    // ══════════════════════════════════════════════════════════════════════════

    private suspend fun readLoop(inputStream: InputStream) = withContext(Dispatchers.IO) {
        val buffer = ByteArray(4096)
        try {
            while (!closed.get()) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break
                if (bytesRead > 0) {
                    sendOutput(buffer.copyOf(bytesRead))
                    if (awaitingPrompt.get()) {
                        while (inputStream.available() > 0) {
                            val more = inputStream.read(buffer, 0,
                                minOf(buffer.size, inputStream.available()))
                            if (more <= 0) break
                            sendOutput(buffer.copyOf(more))
                        }
                        delay(100)
                        if (inputStream.available() == 0) {
                            awaitingPrompt.set(false)
                            sendOutput(PROMPT)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (!closed.get()) Logger.e("TerminalManager: 读取 Shell 输出异常", e)
        } finally {
            if (!closed.get()) { sendOutput("\r\n[Shell session ended]\r\n"); close() }
        }
    }

    /** 协议心跳：每 30 秒发送空数据包保持连接。 */
    private suspend fun keepAliveLoop() {
        try {
            while (!closed.get()) {
                delay(30_000)
                if (closed.get()) break
                outputChannel.send(
                    Nezha.IOStreamData.newBuilder()
                        .setData(ByteString.EMPTY)
                        .build()
                )
            }
        } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Shell 进程启动策略（三级回退）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 启动 Shell 子进程，根据设备提权能力选择最优方式。
     *
     * ## 三级回退策略
     * 1. **Root (su)**：通过 `su` 启动 Root Shell，拥有完整系统访问权限
     * 2. **Shizuku (ADB)**：通过 Shizuku 服务启动 ADB 级别 Shell (UID 2000)
     * 3. **普通 (sh)**：使用 `/system/bin/sh`，权限受限于应用沙箱
     *
     * ## 工作目录选择
     * - Root/Shizuku 模式：`/sdcard`（用户有最强的使用直觉）
     * - 普通模式：应用数据目录（`context.filesDir`，确保有读写权限）
     *
     * @return Pair<Process, String>，其中 String 为 Shell 类型标识
     *         ("su" / "shizuku" / "sh")
     */
    private fun startShellProcess(): Pair<Process, String> {
        // ── 选择工作目录 ──────────────────────────────────────────────
        // 确定一个有权限访问的默认工作目录
        val defaultDir = context.filesDir  // 应用沙箱目录，始终有权限
        val sdcardDir = Environment.getExternalStorageDirectory()  // /sdcard

        // ── 策略 1：尝试 su（Root Shell）────────────────────────────
        try {
            val pb = ProcessBuilder("su")
            pb.redirectErrorStream(true)
            pb.directory(sdcardDir)  // Root 权限下 /sdcard 更方便用户操作
            val p = pb.start()
            // 短暂等待让 su 有时间初始化或退出
            Thread.sleep(200)
            try {
                p.exitValue()
                // 能拿到 exitValue 说明 su 已退出（权限被拒绝或不存在）
                Logger.i("TerminalManager: su 进程秒退，尝试 Shizuku...")
            } catch (e: IllegalThreadStateException) {
                // 抛出异常说明进程仍在运行 → su 启动成功！
                Logger.i("TerminalManager: Root Shell (su) 启动成功")
                return Pair(p, "su")
            }
        } catch (e: Exception) {
            Logger.i("TerminalManager: su 不可用 (${e.message})，尝试 Shizuku...")
        }

        // ── 策略 2：尝试 Shizuku (ADB Shell) ──────────────────────
        try {
            if (isShizukuAvailableForTerminal()) {
                @Suppress("DEPRECATION")
                val method = rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                method.isAccessible = true
                // Shizuku.newProcess 第三个参数是工作目录路径
                val p = method.invoke(
                    null,
                    arrayOf("sh"),
                    null,
                    sdcardDir.absolutePath
                ) as Process
                Logger.i("TerminalManager: Shizuku Shell 启动成功")
                return Pair(p, "shizuku")
            }
        } catch (e: Exception) {
            Logger.e("TerminalManager: Shizuku Shell 启动失败", e)
        }

        // ── 策略 3：回退到普通 sh ──────────────────────────────────
        Logger.i("TerminalManager: 使用普通 sh（权限受限于应用沙箱）")
        val pb = ProcessBuilder("/system/bin/sh")
        pb.redirectErrorStream(true)
        pb.directory(defaultDir)  // 普通模式使用应用数据目录，确保有读写权限
        val p = pb.start()
        return Pair(p, "sh")
    }

    /**
     * 检测 Shizuku 是否可用并已授权（独立于 RootShell 的检测逻辑）。
     * 终端进程不复用 RootShell 的持久会话，而是启动独立的 Shell 进程，
     * 因此需要独立检测 Shizuku 的可用性。
     */
    private fun isShizukuAvailableForTerminal(): Boolean {
        return try {
            rikka.shizuku.Shizuku.pingBinder()
                    && !rikka.shizuku.Shizuku.isPreV11()
                    && rikka.shizuku.Shizuku.checkSelfPermission() ==
                       android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun sendOutput(text: String) = sendOutput(text.toByteArray(Charsets.UTF_8))

    private suspend fun sendOutput(bytes: ByteArray) {
        if (closed.get()) return
        try {
            outputChannel.send(
                Nezha.IOStreamData.newBuilder()
                    .setData(ByteString.copyFrom(bytes))
                    .build()
            )
        } catch (_: Exception) {}
    }
}
