package com.nezhahq.agent.executor

import android.os.Build
import androidx.annotation.RequiresApi
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.core.task.RemoteCapabilityPolicy
import com.nezhahq.agent.core.task.TaskTypes
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.readLimitedUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import proto.Nezha.Task
import proto.Nezha.TaskResult
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal sealed interface ProcessWaitResult {
    data class Exited(val exitCode: Int) : ProcessWaitResult

    data object TimedOut : ProcessWaitResult
}

/**
 * Process operations that are safe on every supported Android API level.
 *
 * The SDK value and blocking primitives are injected so JVM tests do not need an Android runtime.
 * Waiting deliberately uses only [Process.exitValue], because Android did not expose the timed
 * `Process.waitFor` overload until API 26.
 */
internal class ProcessCompatibility(
    private val sdkInt: Int,
    private val destroyForcibly: (Process) -> Unit,
    private val pollIntervalMillis: Long = 10L,
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val destroy: (Process) -> Unit = { it.destroy() }
) {
    init {
        require(pollIntervalMillis > 0) { "pollIntervalMillis must be positive" }
    }

    @Throws(InterruptedException::class)
    fun waitFor(process: Process, timeoutMillis: Long): ProcessWaitResult {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        val deadlineNanos = nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        return waitForUntil(process, deadlineNanos)
    }

    @Throws(InterruptedException::class)
    fun waitForUntil(process: Process, deadlineNanos: Long): ProcessWaitResult {
        while (true) {
            if (hasReachedDeadline(nanoTime(), deadlineNanos)) {
                return ProcessWaitResult.TimedOut
            }

            val exitCode = try {
                process.exitValue()
            } catch (_: IllegalThreadStateException) {
                null
            }
            if (exitCode != null) {
                return if (hasReachedDeadline(nanoTime(), deadlineNanos)) {
                    ProcessWaitResult.TimedOut
                } else {
                    ProcessWaitResult.Exited(exitCode)
                }
            }

            val remainingNanos = deadlineNanos - nanoTime()
            if (remainingNanos <= 0) return ProcessWaitResult.TimedOut
            val remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L)
            try {
                sleep(minOf(pollIntervalMillis, remainingMillis))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
        }
    }

    fun terminate(process: Process) {
        try {
            if (sdkInt >= FORCE_DESTROY_MIN_SDK) {
                destroyForcibly(process)
            } else {
                destroy(process)
            }
        } catch (_: Exception) {
            // Stream cleanup below must still run if a vendor Process implementation rejects destroy.
        } finally {
            closeStreams(process)
        }
    }

    fun closeStreams(process: Process) {
        closeQuietly { process.outputStream } // child stdin
        closeQuietly { process.inputStream } // child stdout
        closeQuietly { process.errorStream } // child stderr
    }

    private inline fun closeQuietly(stream: () -> Closeable) {
        try {
            stream().close()
        } catch (_: Exception) {
            // Each stream is attempted independently.
        }
    }

    private companion object {
        const val FORCE_DESTROY_MIN_SDK = 26
    }
}

/** Keeps the API 26 symbol out of classes loaded on Android 6 and 7. */
@RequiresApi(Build.VERSION_CODES.O)
private object Api26ProcessDestroyer : (Process) -> Unit {
    override fun invoke(process: Process) {
        process.destroyForcibly()
    }
}

internal sealed interface ProcessExecutionResult {
    val output: String

    data class Exited(val exitCode: Int, override val output: String) : ProcessExecutionResult

    data class TimedOut(override val output: String) : ProcessExecutionResult
}

private sealed interface ProcessExecutionState {
    data object Running : ProcessExecutionState
    data class Exited(val exitCode: Int) : ProcessExecutionState
    data object TimedOut : ProcessExecutionState
    data object Cancelled : ProcessExecutionState
    data object Failed : ProcessExecutionState
}

/** Coordinates output draining, the absolute deadline, cancellation, and process cleanup. */
internal class ProcessExecutionOrchestrator(
    private val processCompatibility: ProcessCompatibility,
    private val nanoTime: () -> Long = System::nanoTime,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) }
) {
    suspend fun executeUntil(
        process: Process,
        deadlineNanos: Long,
        maxOutputBytes: Int
    ): ProcessExecutionResult = coroutineScope {
        val state = AtomicReference<ProcessExecutionState>(ProcessExecutionState.Running)

        fun claimAndTerminate(terminalState: ProcessExecutionState) {
            if (state.compareAndSet(ProcessExecutionState.Running, terminalState)) {
                processCompatibility.terminate(process)
            }
        }

        val watchdog = launch(start = CoroutineStart.UNDISPATCHED) {
            while (state.get() === ProcessExecutionState.Running) {
                val remainingNanos = deadlineNanos - nanoTime()
                if (remainingNanos <= 0) {
                    claimAndTerminate(ProcessExecutionState.TimedOut)
                    return@launch
                }
                delayMillis(TimeUnit.NANOSECONDS.toMillis(remainingNanos).coerceAtLeast(1L))
            }
        }

        var output = ""
        try {
            val waitResult = runCancellableBlocking(
                onCancellation = { claimAndTerminate(ProcessExecutionState.Cancelled) }
            ) { isActive ->
                output = readLimitedUtf8(process.inputStream, maxOutputBytes)
                if (!isActive()) {
                    return@runCancellableBlocking ProcessWaitResult.TimedOut
                }
                processCompatibility.waitForUntil(process, deadlineNanos)
            }

            when (waitResult) {
                is ProcessWaitResult.Exited -> state.compareAndSet(
                    ProcessExecutionState.Running,
                    ProcessExecutionState.Exited(waitResult.exitCode)
                )

                ProcessWaitResult.TimedOut -> claimAndTerminate(ProcessExecutionState.TimedOut)
            }
            watchdog.cancelAndJoin()

            when (val terminalState = state.get()) {
                is ProcessExecutionState.Exited -> ProcessExecutionResult.Exited(
                    exitCode = terminalState.exitCode,
                    output = output
                )

                ProcessExecutionState.TimedOut -> ProcessExecutionResult.TimedOut(output)
                ProcessExecutionState.Cancelled -> throw CancellationException("Process execution cancelled")
                ProcessExecutionState.Failed -> error("Process execution failed without an exception")
                ProcessExecutionState.Running -> error("Process execution did not reach a terminal state")
            }
        } catch (cancelled: CancellationException) {
            claimAndTerminate(ProcessExecutionState.Cancelled)
            throw cancelled
        } catch (failure: Throwable) {
            claimAndTerminate(ProcessExecutionState.Failed)
            throw failure
        } finally {
            watchdog.cancel()
            if (state.get() is ProcessExecutionState.Exited) {
                processCompatibility.closeStreams(process)
            }
        }
    }
}

private suspend fun <T> runCancellableBlocking(
    onCancellation: () -> Unit,
    block: (isActive: () -> Boolean) -> T
): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { onCancellation() }
    if (!continuation.isActive) return@suspendCancellableCoroutine

    try {
        val result = block { continuation.isActive }
        if (continuation.isActive) continuation.resume(result)
    } catch (failure: Throwable) {
        if (continuation.isActive) continuation.resumeWithException(failure)
    }
}

private fun hasReachedDeadline(nowNanos: Long, deadlineNanos: Long): Boolean =
    nowNanos - deadlineNanos >= 0

/**
 * 任务执行器：处理面板下发的各类监控任务。
 *
 * ## 支持的任务类型
 * - **TaskType 1 (HTTPGet)**：HTTP/HTTPS 健康检查，支持 SSL 证书到期解析
 * - **TaskType 2 (ICMPPing)**：ICMP Ping 探测
 * - **TaskType 3 (TCPPing)**：TCP 端口连通性探测
 * - **TaskType 4 (Command)**：远程命令执行（带 2 小时超时保护）
 *
 * ## 安全说明
 * - HTTPGet 严格校验证书链和主机名，校验失败直接报告失败
 * - Command 与交互终端共用远程 Shell 授权，默认禁用
 * - Command 执行设有 2 小时硬超时，防止死循环脚本阻塞协程
 */
object TaskExecutor {

    /** 命令执行超时时间：2 小时（毫秒），对齐官方 Go Agent 的 time.Hour * 2 */
    private const val COMMAND_TIMEOUT_MS = 2L * 60 * 60 * 1000
    private const val MAX_COMMAND_OUTPUT_BYTES = 1024 * 1024
    private const val MAX_LOGGED_UNSUPPORTED_TYPES = 64
    private val loggedUnsupportedTypes = LinkedHashSet<Long>()
    private val processCompatibility by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ProcessCompatibility(
                sdkInt = Build.VERSION.SDK_INT,
                destroyForcibly = Api26ProcessDestroyer
            )
        } else {
            ProcessCompatibility(
                sdkInt = Build.VERSION.SDK_INT,
                // The compatibility layer never selects this strategy below API 26.
                destroyForcibly = { process -> process.destroy() }
            )
        }
    }
    private val processOrchestrator by lazy {
        ProcessExecutionOrchestrator(processCompatibility)
    }

    // Defaults retain platform certificate trust and hostname verification. There is no insecure retry.
    private val monitoringClient by lazy { createMonitoringClient() }

    // ──────────────────────────────────────────────────────────────────────────
    // 任务执行入口
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 执行面板下发的任务并返回结果。
     *
     * @param task              面板下发的任务描述（含类型、ID、数据）
     * @param capabilities locally granted remote task capabilities
     * @return TaskResult       包含延时、成功状态和数据的执行结果
     */
    suspend fun executeTask(
        task: Task,
        capabilities: RemoteCapabilities
    ): TaskResult = withContext(Dispatchers.IO) {
        val resultBuilder = TaskResult.newBuilder()
            .setId(task.id)
            .setType(task.type)

        RemoteCapabilityPolicy.denialReason(task.type, capabilities)?.let { reason ->
            return@withContext resultBuilder
                .setSuccessful(false)
                .setData(reason)
                .build()
        }

        try {
            when (task.type) {
                // ── TaskType 1：HTTPGet 健康检查 + SSL 证书解析 ──────────────
                TaskTypes.HTTP_GET -> {
                    val url = parseHost(task.data)
                    val start = System.currentTimeMillis()
                    val request = Request.Builder().url(url).build()
                    // 使用 .use {} 确保 Response 在异常时也能正确释放，
                    // 防止 OkHttp 连接池泄漏（原 response.close() 在异常路径下不会执行）
                    monitoringClient.newCall(request).execute().use { response ->
                        val delay = (System.currentTimeMillis() - start).toFloat()

                        // 提取 SSL 证书信息（仅 HTTPS 连接有 handshake）
                        // 官方 Go Agent 格式：result.Data = c.Issuer.CommonName + "|" + c.NotAfter.String()
                        var certData = ""
                        response.handshake?.peerCertificates?.firstOrNull()?.let { cert ->
                            if (cert is X509Certificate) {
                                val issuerCN = extractCN(cert.issuerX500Principal.name) ?: cert.issuerX500Principal.name
                                // 使用与 Go time.Time.String() 一致的格式输出
                                certData = "$issuerCN|${cert.notAfter}"
                            }
                        }

                        resultBuilder.setDelay(delay)
                            .setSuccessful(response.isSuccessful)
                            .setData(certData)
                    }
                }

                // ── TaskType 2：ICMP Ping ────────────────────────────────────
                TaskTypes.ICMP_PING -> {
                    val host = parseHost(task.data)
                    val start = System.currentTimeMillis()
                    val process = ProcessBuilder("ping", "-c", "1", "-w", "5", host).start()
                    var exitedNormally = false
                    try {
                        // exitValue 轮询兼容 API 23；不调用 API 26 才提供的带超时 waitFor。
                        val waitResult = processCompatibility.waitFor(process, timeoutMillis = 10_000)
                        exitedNormally = waitResult is ProcessWaitResult.Exited
                        val delay = (System.currentTimeMillis() - start).toFloat()
                        resultBuilder.setDelay(delay).setSuccessful(
                            waitResult is ProcessWaitResult.Exited && waitResult.exitCode == 0
                        )
                    } finally {
                        if (exitedNormally) {
                            processCompatibility.closeStreams(process)
                        } else {
                            processCompatibility.terminate(process)
                        }
                    }
                }

                // ── TaskType 3：TCP Ping ─────────────────────────────────────
                TaskTypes.TCP_PING -> {
                    val params = when (val parsed = parseTcpTarget(task.data)) {
                        is HostPort.Result.Parsed -> parsed
                        is HostPort.Result.Invalid -> throw IllegalArgumentException(parsed.reason)
                    }
                    val start = System.currentTimeMillis()
                    val socket = Socket()
                    try {
                        socket.connect(InetSocketAddress(params.host, params.port), 5000)
                        val delay = (System.currentTimeMillis() - start).toFloat()
                        resultBuilder.setDelay(delay).setSuccessful(true)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        logTaskFailure(task.id, e)
                        resultBuilder.setDelay(0f).setSuccessful(false).setData(e.message ?: "TCP probe failed")
                    } finally {
                        socket.close()
                    }
                }

                // ── TaskType 4：远程命令执行（带超时保护）─────────────────────
                TaskTypes.COMMAND -> {
                    executeCommand(task.data, resultBuilder)
                }

                TaskTypes.KEEPALIVE -> {
                    Logger.i("TaskRouter: 收到 Keepalive 任务 (TaskID=${task.id})")
                    resultBuilder.setSuccessful(true).setData("")
                }

                else -> {
                    val type = task.type
                    val message = if (TaskTypes.isKnownUnsupportedOnAndroid(type)) {
                        TaskTypes.unsupportedMessage(task.type)
                    } else {
                        "Unknown task type ${task.type} on Android Agent."
                    }
                    if (shouldLogUnsupportedType(type)) {
                        Logger.i("TaskRouter: $message (TaskID=${task.id})")
                    }
                    resultBuilder.setSuccessful(false).setData(message)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logTaskFailure(task.id, e)
            resultBuilder.setSuccessful(false).setData(e.message ?: "Unknown error")
        }

        return@withContext resultBuilder.build()
    }

    private fun shouldLogUnsupportedType(type: Long): Boolean {
        synchronized(loggedUnsupportedTypes) {
            if (type in loggedUnsupportedTypes) return false
            if (loggedUnsupportedTypes.size >= MAX_LOGGED_UNSUPPORTED_TYPES) {
                val iterator = loggedUnsupportedTypes.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
            loggedUnsupportedTypes.add(type)
            return true
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 命令执行（带超时 + 进程销毁）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 执行 Shell 命令，带 2 小时超时保护。
     *
     * ## 对齐官方 Go Agent 的安全机制
     * - 官方使用 `processgroup.NewProcessExitGroup()` + `time.NewTimer(time.Hour * 2)`
     * - Android 端使用 2 小时看门狗，并按系统版本选择兼容的进程终止方式
     * - 合并 stderr 到 stdout（`redirectErrorStream(true)`），与 Go Agent 行为一致
     *
     * ## 超时处理
     * API 26+ 超时后强制终止进程；API 23-25 只能调用 `destroy()`。
     * 注意：Android 普通权限下无法使用进程组 kill，仅能销毁直接子进程。
     * 若命令 fork 了孙进程，孙进程可能成为孤儿进程（这是 Android 沙箱的固有限制）。
     *
     * @param command        要执行的 Shell 命令字符串
     * @param resultBuilder  TaskResult 构建器，用于设置执行结果
     */
    private suspend fun executeCommand(command: String, resultBuilder: TaskResult.Builder) {
        val startTime = System.currentTimeMillis()
        // 合并 stderr 到 stdout，避免 stderr 缓冲区满导致的死锁
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COMMAND_TIMEOUT_MS)

        try {
            when (
                val execution = processOrchestrator.executeUntil(
                    process = process,
                    deadlineNanos = deadlineNanos,
                    maxOutputBytes = MAX_COMMAND_OUTPUT_BYTES
                )
            ) {
                is ProcessExecutionResult.Exited -> resultBuilder
                    .setData(execution.output)
                    .setDelay((System.currentTimeMillis() - startTime).toFloat())
                    .setSuccessful(execution.exitCode == 0)

                is ProcessExecutionResult.TimedOut -> {
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000
                    // The task id, not the command: dashboard-supplied commands routinely carry
                    // credentials, and the in-app log view and logcat are both readable.
                    Logger.i(
                        "TaskExecutor: 命令执行超时（${elapsed}s），已终止 (TaskID=${resultBuilder.id})"
                    )
                    resultBuilder
                        .setData("Command execution timed out after ${elapsed}s.\n${execution.output}")
                        .setDelay((System.currentTimeMillis() - startTime).toFloat())
                        .setSuccessful(false)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            logTaskFailure(resultBuilder.id, e)
            val delay = (System.currentTimeMillis() - startTime).toFloat()
            resultBuilder.setData(e.message ?: "Unknown error").setDelay(delay).setSuccessful(false)
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 基础工具方法
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 从 X.500 Distinguished Name 中提取 CN（Common Name）字段。
     *
     * X.500 DN 格式示例：`CN=DigiCert Global Root G2, OU=www.digicert.com, O=DigiCert Inc, C=US`
     * 提取后返回 `"DigiCert Global Root G2"`。
     *
     * @param dn X.500 格式的 Distinguished Name 字符串
     * @return CN 字段值，未找到返回 null
     */
    private fun extractCN(dn: String): String? {
        // 简单解析 X.500 DN 中的 CN= 字段
        // 格式：CN=xxx, OU=yyy, O=zzz 或 CN=xxx,OU=yyy
        return dn.split(",")
            .map { it.trim() }
            .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
            ?.substringAfter("=")
            ?.trim()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 参数解析
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * TCP Ping 省略端口时使用的端口。
     *
     * 取 80 是为了与下面 JSON 分支历史上的 `optInt("port", 80)` 保持一致：同一个目标
     * 写成 `{"host":"x"}` 还是写成 `x`，不应该连到不同端口。80 也是这类探测最常见的目标。
     */
    private const val DEFAULT_TCP_PING_PORT = 80

    /**
     * 解析 HTTPGet / ICMPPing 的目标主机。
     *
     * 支持两种格式：
     * 1. JSON 格式：`{"host": "example.com"}`
     * 2. 纯字符串格式：`"example.com"`（兼容旧版面板）
     *
     * 这里刻意**不**拆 `host:port`：HTTPGet 拿到的是完整 URL，`https://example.com` 里的
     * 冒号不是端口分隔符，拆了会把 host 变成 `https`。需要端口的 TCP Ping 走 [parseTcpTarget]。
     */
    private fun parseHost(data: String): String = try {
        JSONObject(data).optString("host", data)
    } catch (_: Exception) {
        data
    }

    /**
     * 解析 TCP Ping 的目标。
     *
     * 支持两种格式：
     * 1. JSON 格式：`{"host": "example.com", "port": 80}`
     * 2. 纯字符串格式：`example.com:80` / `[::1]:8080` / `example.com`（兼容旧版面板）
     *
     * 纯字符串分支此前是 `TaskParams(host = data)`：host 变成整串 `example.com:80`、port 留 0，
     * 连接必然失败——文档声称支持的格式其实一次都没通过。现在与 NAT 共用 [HostPort] 的规则。
     */
    private fun parseTcpTarget(data: String): HostPort.Result {
        val input = data.trim()
        if (!input.startsWith("{")) {
            return HostPort.parse(input, defaultPort = DEFAULT_TCP_PING_PORT)
        }
        val json = try {
            JSONObject(input)
        } catch (_: Exception) {
            return HostPort.Result.Invalid("无效的 TCP 目标 JSON")
        }
        val host = json.optString("host", "").trim()
        val port = if (json.has("port")) {
            json.optString("port").toIntOrNull()
                ?: return HostPort.Result.Invalid("无效的端口号")
        } else {
            DEFAULT_TCP_PING_PORT
        }
        return HostPort.of(host, port)
    }
}

/** Uses the platform trust store and OkHttp's default hostname verifier. */
internal fun createMonitoringClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()

/** Do not pass the exception to Logger: its message and causes can include commands or secrets. */
internal fun logTaskFailure(taskId: Long, failure: Throwable) {
    Logger.e("TaskExecutor: 任务失败 (TaskID=$taskId, Error=${failure.javaClass.simpleName})")
}
