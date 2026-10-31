package com.nezhahq.agent.executor

import android.content.Context
import android.os.Environment
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import com.nezhahq.agent.util.ShellSession
import com.google.protobuf.ByteString
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import proto.Nezha
import proto.NezhaServiceGrpcKt.NezhaServiceCoroutineStub
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

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
        const val MAX_COMMAND_LENGTH = 8 * 1024
        const val SHELL_INPUT_BUFFER_CAPACITY = 8
        const val OUTPUT_BUFFER_CAPACITY = 16
        /** IOStream StreamID 魔术头（协议规定） */
        val STREAM_MAGIC = byteArrayOf(0xFF.toByte(), 0x05, 0xFF.toByte(), 0x05)
    }

    @Volatile private var process: Process? = null
    @Volatile private var privilegedProcess: RootShell.ManagedProcess? = null
    @Volatile private var shellInput: OutputStream? = null
    private val shellInputChannel = Channel<ByteArray>(SHELL_INPUT_BUFFER_CAPACITY)
    private val outputChannel = Channel<Nezha.IOStreamData>(OUTPUT_BUFFER_CAPACITY)
    private val commandHandler = AgentCommandHandler(context)
    private val closed = AtomicBoolean(false)
    private val lineDiscipline = LineDiscipline(PROMPT, MAX_COMMAND_LENGTH)
    private val awaitingPrompt = AtomicBoolean(false)

    /**
     * 启动终端会话（完整的 IOStream 生命周期管理）。
     *
     * 此方法会阻塞直到终端会话结束（用户关闭终端或连接断开）。
     * 应在独立协程中调用。
     */
    suspend fun run() {
        try {
            resourceSessionScope(::close) session@{
                // 1. 由 RootShell 集中授权高权限进程；不可用时回退到普通 sh
                //    显式切入 IO 调度器，避免 Thread.sleep / ProcessBuilder.start 阻塞非 IO 线程
                val shellType = withContext(Dispatchers.IO) {
                    startAndAttachShell()
                }
                val shellProcess = checkNotNull(process) { "Shell process was not attached." }
                Logger.i("TerminalManager: Shell 子进程已启动 (type=$shellType, StreamID=$streamId)")

                // 2. 启动 stdout 读取协程
                launch(Dispatchers.IO) {
                    try {
                        readLoop(shellProcess.inputStream)
                    } finally {
                        this@session.cancel()
                    }
                }

                // 3. 单写者顺序写入 Shell stdin；有界队列避免输入无限积压。
                launch(Dispatchers.IO) {
                    try {
                        writeShellLoop()
                    } finally {
                        this@session.cancel()
                    }
                }

                // 4. 发送 StreamID 魔术头（协议握手）
                val header = STREAM_MAGIC + streamId.toByteArray(Charsets.UTF_8)
                val headerMsg = Nezha.IOStreamData.newBuilder()
                    .setData(ByteString.copyFrom(header))
                    .build()
                outputChannel.send(headerMsg)

                // 5. 发送欢迎横幅（显示当前 Shell 权限类型）
                val typeLabel = when (shellType) {
                    "su"      -> "Root"
                    "shizuku" -> "Shizuku (ADB)"
                    else      -> "普通"
                }
                sendOutput("\r\n========== Nezha Agent Terminal ==========\r\n")
                sendOutput("  模式: $typeLabel | 输入 @agent help 查看虚拟指令\r\n")
                sendOutput("==========================================\r\n\r\n")
                sendOutput(PROMPT)

                // 6. 启动心跳协程
                launch { keepAliveLoop() }

                // 7. 建立 IOStream 双向流并处理输入
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

                this@session.cancel()
            }
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
        } catch (e: Exception) {
            Logger.i("TerminalManager: 终端会话结束 (StreamID=$streamId): ${e.message}")
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
        val managed = privilegedProcess
        if (managed != null) {
            managed.close()
        } else {
            process?.let(ShellSession::destroyProcess)
        }
        shellInputChannel.close()
        outputChannel.close()
        process = null
        privilegedProcess = null
        shellInput = null
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 内置行纪律 (Line Discipline)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 逐字节喂给 [LineDiscipline]，并立刻执行它给出的副作用。
     *
     * 刻意保持"一个字节 → 执行该字节的全部副作用 → 下一个字节"的顺序，而不是先把整段
     * 输入解析完再统一执行：[LineDisciplineEffect.AwaitPrompt] 会改变 [awaitingPrompt]，
     * 而同一段输入里紧随其后的 Ctrl+C 要读到改动后的值（例如 `ls\r` + `0x03`）。
     */
    private suspend fun handleInput(data: ByteArray) {
        if (closed.get()) return
        for (byte in data) {
            for (effect in lineDiscipline.onByte(byte)) {
                applyEffect(effect)
            }
        }
    }

    private suspend fun applyEffect(effect: LineDisciplineEffect) {
        when (effect) {
            is LineDisciplineEffect.Echo -> sendOutput(effect.text)
            is LineDisciplineEffect.SendToShell -> writeToShell(effect.text.toByteArray())
            is LineDisciplineEffect.RunAgentCommand -> handleAgentCommand(effect.subcommand)
            LineDisciplineEffect.AwaitPrompt -> awaitingPrompt.set(true)
            LineDisciplineEffect.SendInterrupt -> writeToShell(byteArrayOf(0x03))
            // awaitingPrompt 在这里才读取，与改造前"先回显 ^C 再判断"的时序一致
            LineDisciplineEffect.Interrupt ->
                applyEffect(lineDiscipline.resolveInterrupt(awaitingPrompt.get()))
        }
    }

    private suspend fun handleAgentCommand(subcommand: String) {
        try {
            sendOutput(commandHandler.execute(subcommand))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            Logger.e("TerminalManager: 虚拟指令执行异常", e)
            sendOutput("❌ 指令执行异常: ${e.message}\r\n")
        }
    }

    private suspend fun writeToShell(data: ByteArray) {
        if (closed.get()) return
        try {
            shellInputChannel.send(data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!closed.get()) Logger.e("TerminalManager: 排队写入 Shell stdin 失败", e)
        }
    }

    private suspend fun writeShellLoop() {
        try {
            for (data in shellInputChannel) {
                val input = shellInput ?: break
                input.write(data)
                input.flush()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!closed.get()) Logger.e("TerminalManager: 写入 Shell stdin 失败", e)
        }
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
                    // 对 Shell 输出做 LF→CRLF 翻译（模拟 PTY 的 ONLCR 行为）
                    // Shell 进程输出的 \n 在没有 PTY 的情况下不会自动变成 \r\n，
                    // 导致 xterm.js 只执行"光标下移"而不回到行首，出现阶梯状排列
                    sendOutput(translateLfToCrlf(buffer, bytesRead))
                    if (awaitingPrompt.get()) {
                        while (inputStream.available() > 0) {
                            val more = inputStream.read(buffer, 0,
                                minOf(buffer.size, inputStream.available()))
                            if (more <= 0) break
                            sendOutput(translateLfToCrlf(buffer, more))
                        }
                        delay(100)
                        if (inputStream.available() == 0) {
                            awaitingPrompt.set(false)
                            sendOutput(PROMPT)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!closed.get()) Logger.e("TerminalManager: 读取 Shell 输出异常", e)
        } finally {
            if (!closed.get()) {
                try {
                    sendOutput("\r\n[Shell session ended]\r\n")
                } finally {
                    close()
                }
            }
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
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Shell 进程启动策略（集中授权 + 普通回退）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 启动 Shell 子进程，根据设备提权能力选择最优方式。
     *
     * ## 两级回退策略
     * 1. 请求 [RootShell] 创建经过集中授权的 Root/Shizuku Shell
     * 2. 高权限模式关闭或不可用时，使用应用沙箱内的普通 `/system/bin/sh`
     *
     * ## 工作目录选择
     * - Root/Shizuku 模式：`/sdcard`（用户有最强的使用直觉）
     * - 普通模式：应用数据目录（`context.filesDir`，确保有读写权限）
     *
     * @return Pair<Process, String>，其中 String 为 Shell 类型标识
     *         ("su" / "shizuku" / "sh")
     */
    private fun startShellProcess(): Pair<Process, String> {
        val privileged = RootShell.startInteractiveShell(
            Environment.getExternalStorageDirectory()
        )
        if (privileged != null) {
            privilegedProcess = privileged
            Logger.i("TerminalManager: 高权限 Shell (${privileged.type}) 启动成功")
            return Pair(privileged.process, privileged.type)
        }

        Logger.i("TerminalManager: 使用普通 sh（权限受限于应用沙箱）")
        val pb = ProcessBuilder("/system/bin/sh")
        pb.redirectErrorStream(true)
        pb.directory(context.filesDir)
        val p = pb.start()
        return Pair(p, "sh")
    }

    private fun startAndAttachShell(): String {
        val (startedProcess, shellType) = startShellProcess()
        process = startedProcess
        shellInput = startedProcess.outputStream
        return shellType
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
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 行纪律：纯逻辑部分
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 行纪律对外部产生的副作用。
 *
 * 把"决定做什么"与"怎么做"分开，是为了让状态机能在没有 gRPC 流、没有 Shell 子进程、
 * 也没有 Android 运行时的情况下被直接断言。
 */
internal sealed interface LineDisciplineEffect {
    /** 回显到终端。 */
    data class Echo(val text: String) : LineDisciplineEffect

    /** 把一行命令写入 Shell stdin（[text] 已含结尾换行）。 */
    data class SendToShell(val text: String) : LineDisciplineEffect

    /** 执行 `@agent` 虚拟指令；[subcommand] 是已经剥掉前缀的部分。 */
    data class RunAgentCommand(val subcommand: String) : LineDisciplineEffect

    /** 标记"已经把命令交给 Shell，正在等它的提示符"。 */
    data object AwaitPrompt : LineDisciplineEffect

    /** 向 Shell 转发 Ctrl+C（0x03）。 */
    data object SendInterrupt : LineDisciplineEffect

    /**
     * Ctrl+C，去向未定。
     *
     * 结果取决于运行时的 awaitingPrompt，而它由读取 Shell 输出的协程并发改写，
     * 所以必须在执行副作用时才读取，交给 [LineDiscipline.resolveInterrupt] 解析。
     */
    data object Interrupt : LineDisciplineEffect
}

/**
 * 终端行纪律状态机（纯逻辑，无 IO、无 Android 依赖）。
 *
 * Android 无法分配 PTY（需 JNI），所以回显、退格、Ctrl+C/U/D、ESC 序列过滤这些本该由
 * 内核 termios 完成的事都得自己做。这里只负责"输入字节 → 该产生哪些副作用"，
 * 副作用由 [TerminalManager] 执行。
 *
 * @param prompt            命令提示符，需要重画提示符的分支会原样回显它
 * @param maxCommandLength  单行命令的字符上限；超出后整行作废（见 [onEnter]）
 */
internal class LineDiscipline(
    private val prompt: String,
    private val maxCommandLength: Int
) {
    private companion object {
        const val AGENT_CMD_PREFIX = "@agent "
        const val AGENT_CMD_EXACT = "@agent"
    }

    /** ESC 序列的解析阶段：ESC 之后可能跟 `[`（CSI），CSI 以 0x40..0x7E 收尾。 */
    private enum class InputState { NORMAL, ESC, CSI }

    private val lineBuffer = StringBuilder()
    private var inputState = InputState.NORMAL

    /**
     * 本行是否已经超长。
     *
     * 超长后剩余字符被静默丢弃，直到 Enter 才整行拒绝——不能一超长就报错，
     * 否则用户粘贴一大段文本时会收到一串重复错误。
     */
    private var lineOverflowed = false

    /** 当前行缓冲内容，供测试与诊断观察。 */
    val currentLine: String get() = lineBuffer.toString()

    fun onByte(byte: Byte): List<LineDisciplineEffect> {
        val b = byte.toInt() and 0xFF
        return when (inputState) {
            // ESC / CSI 序列（方向键、功能键等）整段吞掉：没有 PTY，光标移动无从实现，
            // 放行只会把控制字符混进命令行
            InputState.ESC -> {
                inputState = if (b == 0x5B) InputState.CSI else InputState.NORMAL
                emptyList()
            }

            InputState.CSI -> {
                if (b in 0x40..0x7E) inputState = InputState.NORMAL
                emptyList()
            }

            InputState.NORMAL -> onNormalByte(b)
        }
    }

    /** Ctrl+C 的去向：Shell 正在跑命令就转发过去，否则本地重画提示符。 */
    fun resolveInterrupt(awaitingPrompt: Boolean): LineDisciplineEffect =
        if (awaitingPrompt) {
            LineDisciplineEffect.SendInterrupt
        } else {
            LineDisciplineEffect.Echo(prompt)
        }

    private fun onNormalByte(b: Int): List<LineDisciplineEffect> = when (b) {
        0x1B -> {
            inputState = InputState.ESC
            emptyList()
        }

        0x0D -> onEnter()

        0x0A -> emptyList() // 忽略 LF（CR+LF 场景）

        0x7F, 0x08 -> if (lineBuffer.isEmpty()) {
            // 空缓冲时不回退，否则退格会擦掉提示符本身
            emptyList()
        } else {
            lineBuffer.deleteCharAt(lineBuffer.length - 1)
            listOf(LineDisciplineEffect.Echo("\b \b"))
        }

        0x03 -> { // Ctrl+C
            lineBuffer.clear()
            lineOverflowed = false
            listOf(LineDisciplineEffect.Echo("^C\r\n"), LineDisciplineEffect.Interrupt)
        }

        0x15 -> { // Ctrl+U：清空整行
            val effects: List<LineDisciplineEffect> = if (lineBuffer.isEmpty()) {
                emptyList()
            } else {
                // repeat 必须在 clear 之前求值
                listOf(LineDisciplineEffect.Echo("\b \b".repeat(lineBuffer.length)))
                    .also { lineBuffer.clear() }
            }
            lineOverflowed = false
            effects
        }

        0x04 -> if (lineBuffer.isEmpty()) { // Ctrl+D
            listOf(
                LineDisciplineEffect.Echo("\r\n[使用 exit 命令退出]\r\n"),
                LineDisciplineEffect.Echo(prompt)
            )
        } else {
            emptyList()
        }

        in 0x20..0x7E -> if (lineBuffer.length >= maxCommandLength) {
            lineOverflowed = true
            emptyList()
        } else {
            lineBuffer.append(b.toChar())
            // 0x20..0x7E 的 UTF-8 编码就是它本身，回显字符与回显原字节等价
            listOf(LineDisciplineEffect.Echo(b.toChar().toString()))
        }

        else -> emptyList()
    }

    private fun onEnter(): List<LineDisciplineEffect> {
        if (lineOverflowed) {
            lineBuffer.clear()
            lineOverflowed = false
            return listOf(
                LineDisciplineEffect.Echo("\r\n"),
                LineDisciplineEffect.Echo(
                    "[Command rejected: maximum length is $maxCommandLength characters]\r\n"
                ),
                LineDisciplineEffect.Echo(prompt)
            )
        }

        val command = lineBuffer.toString().trim()
        lineBuffer.clear()
        return when {
            command.startsWith(AGENT_CMD_PREFIX) || command == AGENT_CMD_EXACT -> listOf(
                LineDisciplineEffect.Echo("\r\n"),
                LineDisciplineEffect.RunAgentCommand(agentSubcommand(command)),
                LineDisciplineEffect.Echo(prompt)
            )

            // 提示符由读取 Shell 输出的协程在命令结束后补上，这里不画
            command.isNotEmpty() -> listOf(
                LineDisciplineEffect.Echo("\r\n"),
                LineDisciplineEffect.AwaitPrompt,
                LineDisciplineEffect.SendToShell(command + "\n")
            )

            else -> listOf(
                LineDisciplineEffect.Echo("\r\n"),
                LineDisciplineEffect.Echo(prompt)
            )
        }
    }

    private fun agentSubcommand(command: String): String =
        if (command == AGENT_CMD_EXACT) "" else command.removePrefix(AGENT_CMD_PREFIX).trim()
}

/**
 * 模拟 PTY 的 ONLCR（Output NL-CR）行为：
 * 将 Shell 输出中孤立的 \n (0x0A) 翻译为 \r\n (0x0D 0x0A)。
 *
 * ## 为什么需要这个？
 * 真正的伪终端（PTY）内核驱动会自动做这个翻译（termios OPOST+ONLCR 标志），
 * 但我们使用的是 ProcessBuilder 的原始管道（非 PTY），Shell 输出的 \n 不会
 * 自动变成 \r\n。xterm.js 等终端模拟器收到孤立的 \n 后只执行"光标下移"，
 * 不回到行首，导致命令输出呈"阶梯状"。
 *
 * ## 翻译规则
 * - `\n` 前面没有 `\r` → 插入 `\r` 变成 `\r\n`
 * - `\r\n` 已经存在 → 保持不变，不做重复翻译
 *
 * @param data   原始字节缓冲区
 * @param length 有效字节数
 * @return 翻译后的字节数组
 */
internal fun translateLfToCrlf(data: ByteArray, length: Int): ByteArray {
    // 快速路径：先扫描是否有需要翻译的孤立 \n，没有则直接返回原数据的拷贝
    var needsTranslation = false
    for (i in 0 until length) {
        if (data[i] == 0x0A.toByte()) {
            if (i == 0 || data[i - 1] != 0x0D.toByte()) {
                needsTranslation = true
                break
            }
        }
    }
    if (!needsTranslation) return data.copyOf(length)

    // 需要翻译：最坏情况每个字节都是 \n，输出长度翻倍
    val out = ByteArray(length * 2)
    var pos = 0
    for (i in 0 until length) {
        val b = data[i]
        if (b == 0x0A.toByte() && (i == 0 || data[i - 1] != 0x0D.toByte())) {
            // 孤立的 \n → 插入 \r\n
            out[pos++] = 0x0D.toByte()
        }
        out[pos++] = b
    }
    return out.copyOf(pos)
}
