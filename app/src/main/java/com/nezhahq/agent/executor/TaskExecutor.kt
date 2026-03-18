package com.nezhahq.agent.executor

import com.nezhahq.agent.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import proto.Nezha.Task
import proto.Nezha.TaskResult
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.json.JSONObject
import java.io.InputStream

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
 * - HTTPGet 使用信任所有证书的 OkHttpClient，用于监控自签名 HTTPS 站点
 * - Command 任务默认禁用，需用户在设置中手动启用
 * - Command 执行设有 2 小时硬超时，防止死循环脚本阻塞协程
 */
object TaskExecutor {

    /** 命令执行超时时间：2 小时（毫秒），对齐官方 Go Agent 的 time.Hour * 2 */
    private const val COMMAND_TIMEOUT_MS = 2L * 60 * 60 * 1000

    // ──────────────────────────────────────────────────────────────────────────
    // OkHttpClient：信任所有证书（监控场景需要能连接自签名 HTTPS 站点）
    // ──────────────────────────────────────────────────────────────────────────

    /** 信任所有证书的 TrustManager，用于监控自签名 HTTPS 站点 */
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    /**
     * 预配置的 OkHttpClient 实例。
     *
     * - 信任所有 SSL 证书（对齐官方探针行为，监控场景需要）
     * - 禁用主机名验证（允许 IP 直连和自签名证书）
     * - 10 秒连接/读取超时
     */
    private val client: OkHttpClient = run {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 任务执行入口
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 执行面板下发的任务并返回结果。
     *
     * @param task              面板下发的任务描述（含类型、ID、数据）
     * @param isCommandEnabled  是否允许执行远程命令（用户设置项）
     * @return TaskResult       包含延时、成功状态和数据的执行结果
     */
    suspend fun executeTask(task: Task, isCommandEnabled: Boolean): TaskResult = withContext(Dispatchers.IO) {
        val resultBuilder = TaskResult.newBuilder()
            .setId(task.id)
            .setType(task.type)

        try {
            when (task.type) {
                // ── TaskType 1：HTTPGet 健康检查 + SSL 证书解析 ──────────────
                1L -> {
                    val params = parseParams(task.data)
                    val url = params.host
                    val start = System.currentTimeMillis()
                    val request = Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()
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
                    response.close()

                    resultBuilder.setDelay(delay)
                        .setSuccessful(response.isSuccessful)
                        .setData(certData)
                }

                // ── TaskType 2：ICMP Ping ────────────────────────────────────
                2L -> {
                    val params = parseParams(task.data)
                    val start = System.currentTimeMillis()
                    val process = ProcessBuilder("ping", "-c", "1", "-w", "5", params.host).start()
                    val exitVal = process.waitFor()
                    val delay = (System.currentTimeMillis() - start).toFloat()

                    resultBuilder.setDelay(delay).setSuccessful(exitVal == 0)
                }

                // ── TaskType 3：TCP Ping ─────────────────────────────────────
                3L -> {
                    val params = parseParams(task.data)
                    val start = System.currentTimeMillis()
                    val socket = Socket()
                    try {
                        socket.connect(InetSocketAddress(params.host, params.port), 5000)
                        val delay = (System.currentTimeMillis() - start).toFloat()
                        resultBuilder.setDelay(delay).setSuccessful(true)
                    } catch (e: Exception) {
                        resultBuilder.setDelay(0f).setSuccessful(false)
                    } finally {
                        socket.close()
                    }
                }

                // ── TaskType 4：远程命令执行（带超时保护）─────────────────────
                4L -> {
                    if (!isCommandEnabled) {
                        resultBuilder.setData("Command execution disabled on Android Agent.\n").setSuccessful(false)
                        return@withContext resultBuilder.build()
                    }
                    executeCommand(task.data, resultBuilder)
                }

                else -> {
                    resultBuilder.setSuccessful(false).setData("Unsupported task type on Android")
                }
            }
        } catch (e: Exception) {
            resultBuilder.setSuccessful(false).setData(e.message ?: "Unknown error")
        }

        return@withContext resultBuilder.build()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 命令执行（带超时 + 进程销毁）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 执行 Shell 命令，带 2 小时超时保护。
     *
     * ## 对齐官方 Go Agent 的安全机制
     * - 官方使用 `processgroup.NewProcessExitGroup()` + `time.NewTimer(time.Hour * 2)`
     * - Android 端使用 `withTimeout` 协程超时 + `Process.destroyForcibly()` 替代
     * - 合并 stderr 到 stdout（`redirectErrorStream(true)`），与 Go Agent 行为一致
     *
     * ## 超时处理
     * 超时后通过 `destroyForcibly()` 强制终止子进程。
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

        try {
            coroutineScope {
                var isTimeout = false
                // 看门狗协程：达到超时时间后主动强制终止进程
                // 这是为了解决 withTimeout 无法打断 Java 阻塞 IO (readText) 的问题
                val watchdog = launch {
                    delay(COMMAND_TIMEOUT_MS)
                    isTimeout = true
                    try { process.destroyForcibly() } catch (_: Exception) {}
                }

                // 在 IO 线程中读取全部输出（最多读取 1 MB 避免 OOM）。
                // 若发生超时，看门狗会杀掉进程，由此关闭管道，这里的流读取会立即停止阻塞并返回已读数据。
                val output = readLimitedString(process.inputStream, 1024 * 1024)
                val exitVal = process.waitFor()
                watchdog.cancel()

                val delay = (System.currentTimeMillis() - startTime).toFloat()
                if (isTimeout) {
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000
                    Logger.i("TaskExecutor: 命令执行超时（${elapsed}s），已强制终止: ${command.take(100)}")
                    resultBuilder
                        .setData("Command execution timed out after ${elapsed}s.\n$output")
                        .setDelay(delay)
                        .setSuccessful(false)
                } else {
                    resultBuilder
                        .setData(output)
                        .setDelay(delay)
                        .setSuccessful(exitVal == 0)
                }
            }
        } catch (e: Exception) {
            val delay = (System.currentTimeMillis() - startTime).toFloat()
            resultBuilder.setData(e.message ?: "Unknown error").setDelay(delay).setSuccessful(false)
        } finally {
            // 确保进程资源释放
            try { process.destroy() } catch (_: Exception) {}
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 基础工具方法
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 安全地读取 InputStream，限制最大读取字节数，防止被恶意或失控脚本打爆内存（OOM）。
     *
     * @param inputStream 要读取的输入流
     * @param maxBytes    最大允许读取的字节数
     * @return 读取到的字符串内容
     */
    private fun readLimitedString(inputStream: InputStream, maxBytes: Int): String {
        val buffer = ByteArray(4096)
        val sb = java.lang.StringBuilder()
        var totalRead = 0
        try {
            while (totalRead < maxBytes) {
                // read 会在此阻塞直到有数据、EOF 或由于 destroyForcibly() 抛出异常
                val bytesRead = inputStream.read(buffer, 0, minOf(buffer.size, maxBytes - totalRead))
                if (bytesRead == -1) break
                totalRead += bytesRead
                sb.append(String(buffer, 0, bytesRead, Charsets.UTF_8))
            }
            if (totalRead >= maxBytes) {
                sb.append("\n...[Output truncated due to size limit (1MB)]...\n")
            }
        } catch (e: Exception) {
            // 当 watchdog 杀掉进程时，这里可能抛出 IOException
        }
        return sb.toString()
    }

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

    /** 任务参数数据类，包含目标主机和端口 */
    private data class TaskParams(val host: String, val port: Int = 0)

    /**
     * 解析面板下发的任务数据。
     *
     * 支持两种格式：
     * 1. JSON 格式：`{"host": "example.com", "port": 80}`
     * 2. 纯字符串格式：`"example.com"`（兼容旧版面板）
     *
     * @param data 面板下发的原始数据字符串
     * @return 解析后的 TaskParams
     */
    private fun parseParams(data: String): TaskParams {
        return try {
            val json = JSONObject(data)
            TaskParams(
                host = json.optString("host", data),
                port = json.optInt("port", 80)
            )
        } catch (e: Exception) {
            TaskParams(host = data)
        }
    }
}
