package com.nezhahq.agent.executor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.nezhahq.agent.service.KeepAliveAccessibilityService
import com.nezhahq.agent.util.ByteUnits.GIB
import com.nezhahq.agent.util.ByteUnits.KIB
import com.nezhahq.agent.util.ByteUnits.MIB
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import com.nezhahq.agent.util.shellEscape
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 虚拟指令处理器（Virtual Command Handler）。
 *
 * ## 设计思路
 * 对从 Dashboard 终端传入的 `@agent <subcommand>` 指令进行路由分发，
 * 将结果以格式化文本返回到终端输出流。
 *
 * ## 安全设计
 * - 短信读取前先检查 `READ_SMS` 运行时权限，未授权则返回友好提示
 * - 短信查询结果仅在内存中处理，**绝不写入磁盘或 Logcat**
 * - 截图指令只写入用户指定路径，默认 `/sdcard`，并优先使用已授权的无障碍服务
 *
 * ## 扩展性
 * 新增指令只需在 [execute] 的 `when` 分支中添加即可，
 * 后续可扩展 `@agent battery`、`@agent clipboard` 等指令。
 *
 * @param context Android 上下文，用于 ContentResolver 和权限检查
 */
class AgentCommandHandler(private val context: Context) {
    private val appListQuery = AppListQuery(context)

    /**
     * 执行虚拟指令并返回格式化的终端输出文本。
     *
     * @param subcommand `@agent` 后的子指令（已 trim），例如 "sms"、"help"
     * @return 终端输出文本，包含换行符
     */
    suspend fun execute(subcommand: String): String {
        val normalized = subcommand.trim()
        val commandToken = normalized.substringBefore(' ', normalized)
        val command = commandToken.lowercase(Locale.ROOT)
        val args = normalized.drop(commandToken.length).trim()

        return when (command) {
            "" , "help" -> executeHelp()
            "sms" -> executeSms()
            "apps", "app", "应用" -> executeApps(args)
            "screenshot", "screencap", "screen", "截图", "截屏" -> executeScreenshot(args)
            else -> "❌ 未知指令: @agent $normalized\r\n输入 @agent help 查看可用指令列表。\r\n"
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 指令实现
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 显示帮助信息。
     */
    private fun executeHelp(): String {
        return buildString {
            append("\r\n")
            append("╔═════════════════════════════════════╗\r\n")
            append("║     @agent 虚拟指令系统 v1.0        ║\r\n")
            append("╠═════════════════════════════════════╣\r\n")
            append("║  @agent help   显示此帮助信息       ║\r\n")
            append("║  @agent sms    查看最近 5 条短信    ║\r\n")
            append("║  @agent apps installed 用户应用列表 ║\r\n")
            append("║  @agent apps processes 应用进程列表 ║\r\n")
            append("║  @agent screenshot [路径] 保存截图  ║\r\n")
            append("╚═════════════════════════════════════╝\r\n")
            append("\r\n")
            append("截图默认保存到 /sdcard，例如 /sdcard/nezha_screenshot_20260626_120000.png\r\n")
            append("提示: 所有其他输入将作为标准 Shell 命令执行。\r\n")
            append("\r\n")
        }
    }

    /**
     * 查询用户应用列表或用户应用进程。
     */
    private suspend fun executeApps(args: String): String {
        val trimmed = args.trim()
        val action = trimmed.substringBefore(' ', trimmed).lowercase(Locale.ROOT)

        return when (action) {
            "", "help" -> executeAppsHelp()
            "installed", "install", "list", "ls", "已安装", "安装", "列表" -> {
                formatInstalledApps(appListQuery.getInstalledUserApps())
            }
            "processes", "process", "ps", "running", "run", "cached", "cache",
            "进程", "运行", "缓存" -> executeAppProcesses()
            else -> buildString {
                append("❌ 未知应用指令: @agent apps $trimmed\r\n")
                append(executeAppsHelp())
            }
        }
    }

    private fun executeAppsHelp(): String {
        return buildString {
            append("\r\n")
            append("📦 用户应用查询\r\n")
            append("═══════════════════════════════════════\r\n")
            append("@agent apps installed    已安装用户应用\r\n")
            append("@agent apps processes    运行中/缓存中用户应用\r\n")
            append("\r\n")
            append("别名: app / 应用；processes 也可写作 running / 进程 / 运行。\r\n")
            append("\r\n")
        }
    }

    private fun formatInstalledApps(apps: List<AppListQuery.InstalledApp>): String {
        if (apps.isEmpty()) {
            return "\r\n📦 未找到已安装用户应用。\r\n\r\n"
        }

        return buildString {
            append("\r\n")
            append("📦 已安装用户应用\r\n")
            append("═══════════════════════════════════════\r\n")
            apps.forEachIndexed { index, app ->
                append("[${index + 1}] ${app.label}\r\n")
                append("    包名: ${app.packageName}\r\n")
                append("    版本: ${app.versionName}\r\n")
                append("    UID : ${app.uid}\r\n")
                append("    应用: ${formatBytes(app.appBytes)}\r\n")
                append("    数据: ${formatBytes(app.dataBytes)}\r\n")
                append("───────────────────────────────────────\r\n")
            }
            append("共 ${apps.size} 个用户应用\r\n")
            append("\r\n")
        }
    }

    private suspend fun executeAppProcesses(): String {
        if (!RootShell.isAuthorized()) {
            Logger.i("AgentCommandHandler: 跳过用户应用进程查询，Root/Shizuku 模式未开启")
            return buildString {
                append("\r\n")
                append("⚠️ 应用进程查询需要 Root/Shizuku 模式\r\n")
                append("请在设置中开启 Root / Shizuku 模式并确认授权后重试。\r\n")
                append("\r\n")
            }
        }

        return when (val result = appListQuery.getUserAppProcesses()) {
            is AppListQuery.ProcessQueryResult.Success -> formatAppProcesses(result.processes)
            is AppListQuery.ProcessQueryResult.Failure -> buildString {
                append("\r\n")
                append("❌ 应用进程查询失败\r\n")
                append("原因: ${result.reason}\r\n")
                append("请确认 Root/Shizuku Shell 可用后重试。\r\n")
                append("\r\n")
            }
        }
    }

    private fun formatAppProcesses(processes: List<AppListQuery.UserAppProcess>): String {
        if (processes.isEmpty()) {
            return "\r\n📦 当前未找到运行中或缓存中的用户应用。\r\n\r\n"
        }

        val runningCount = processes.count { it.state == AppListQuery.ProcessState.RUNNING }
        val cachedCount = processes.size - runningCount

        return buildString {
            append("\r\n")
            append("📦 用户应用进程\r\n")
            append("═══════════════════════════════════════\r\n")
            processes.forEachIndexed { index, process ->
                val app = process.app
                append("[${index + 1}] [${process.state.label}] ${app.label}\r\n")
                append("    包名: ${app.packageName}\r\n")
                append("    版本: ${app.versionName}\r\n")
                append("    UID : ${app.uid}\r\n")
                append("    进程内存(PSS): ${formatGigabytes(process.memoryBytes)}\r\n")
                append("───────────────────────────────────────\r\n")
            }
            append("运行中 $runningCount 个，缓存中 $cachedCount 个，共 ${processes.size} 个用户应用\r\n")
            append("\r\n")
        }
    }

    /**
     * 读取最近 5 条短信。
     *
     * ## 安全流程
     * 1. 检查 READ_SMS 权限 → 未授权则返回提示
     * 2. 通过 ContentResolver 查询 content://sms/inbox
     * 3. 结果仅在 StringBuilder 中组装，不落盘
     * 4. 限制最多 5 条，防止大量数据占用终端
     */
    private fun executeSms(): String {
        // ── 权限检查 ──
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return buildString {
                append("\r\n")
                append("⚠️ 短信权限未授予\r\n")
                append("请在设备的「设置 → 应用 → 哪吒探针 → 权限」中\r\n")
                append("手动开启「短信」权限后重试。\r\n")
                append("\r\n")
            }
        }

        // ── 查询短信 ──
        return try {
            val smsUri: Uri = Telephony.Sms.Inbox.CONTENT_URI
            val projection = arrayOf(
                Telephony.Sms.ADDRESS,   // 发件人号码
                Telephony.Sms.BODY,      // 短信内容
                Telephony.Sms.DATE       // 接收时间
            )
            val sortOrder = "${Telephony.Sms.DATE} DESC"

            val cursor: Cursor? = context.contentResolver.query(
                smsUri, projection, null, null, sortOrder
            )

            if (cursor == null) {
                return "❌ 无法查询短信数据库\r\n"
            }

            cursor.use { c ->
                if (c.count == 0) {
                    return "📭 收件箱为空，没有找到短信。\r\n"
                }

                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                val addressIdx = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE)

                buildString {
                    append("\r\n")
                    append("📱 最近 5 条短信 (收件箱)\r\n")
                    append("═══════════════════════════════════════\r\n")

                    var count = 0
                    while (c.moveToNext() && count < 5) {
                        count++
                        val address = c.getString(addressIdx) ?: "未知号码"
                        val body = c.getString(bodyIdx) ?: ""
                        val dateMs = c.getLong(dateIdx)
                        val dateStr = dateFormat.format(Date(dateMs))

                        // 截断超长短信内容，防止刷屏
                        val truncatedBody = if (body.length > 100) {
                            body.substring(0, 100) + "..."
                        } else {
                            body
                        }

                        append("[$count] $address\r\n")
                        append("    时间: $dateStr\r\n")
                        append("    内容: $truncatedBody\r\n")
                        append("───────────────────────────────────────\r\n")
                    }
                    append("共显示 $count 条短信\r\n")
                    append("\r\n")
                }
            }
        } catch (e: Exception) {
            Logger.e("AgentCommandHandler: 短信查询失败", e)
            "❌ 短信查询失败: ${e.message}\r\n"
        }
    }

    /**
     * 保存当前屏幕截图，默认落盘到 /sdcard。
     *
     * 优先使用 Android 11+ 无障碍截图 API；当无障碍不可用时，如果用户已开启
     * Root/Shizuku 高权限模式，则回退到系统 screencap 命令。
     */
    private suspend fun executeScreenshot(pathArg: String): String {
        val targetPath = normalizeScreenshotPath(pathArg) ?: return buildString {
            append("\r\n")
            append("❌ 截图路径无效\r\n")
            append("相对路径不能越出默认目录 ${defaultScreenshotDir()}；如需写到别处请给出绝对路径。\r\n")
            append("\r\n")
        }

        when (val result = KeepAliveAccessibilityService.saveScreenshot(targetPath)) {
            is KeepAliveAccessibilityService.ScreenshotSaveResult.Success -> {
                Logger.i("AgentCommandHandler: 无障碍截图已保存: ${result.path}")
                return formatScreenshotSuccess(result.path, result.bytes, "无障碍服务")
            }
            is KeepAliveAccessibilityService.ScreenshotSaveResult.Failure -> {
                Logger.i("AgentCommandHandler: 无障碍截图不可用，准备尝试高权限兜底: ${result.reason}")
                val rootMode = RootShell.isAuthorized()
                if (!rootMode) {
                    return buildString {
                        append("\r\n")
                        append("❌ 截图保存失败\r\n")
                        append("目标路径: $targetPath\r\n")
                        append("原因: ${result.reason}\r\n")
                        append("\r\n")
                        append("请确认无障碍服务已启用、所有文件访问可写，或开启 Root/Shizuku 高权限模式后重试。\r\n")
                        append("\r\n")
                    }
                }

                return when (val shellResult = captureScreenshotViaShell(targetPath)) {
                    is ShellScreenshotResult.Success -> {
                        Logger.i("AgentCommandHandler: screencap 截图已保存: ${shellResult.path}")
                        formatScreenshotSuccess(shellResult.path, shellResult.bytes, "Root/Shizuku screencap")
                    }
                    is ShellScreenshotResult.Failure -> buildString {
                        append("\r\n")
                        append("❌ 截图保存失败\r\n")
                        append("目标路径: $targetPath\r\n")
                        append("无障碍: ${result.reason}\r\n")
                        append("高权限: ${shellResult.reason}\r\n")
                        append("\r\n")
                    }
                }
            }
        }
    }

    private suspend fun captureScreenshotViaShell(targetPath: String): ShellScreenshotResult =
        withContext(Dispatchers.IO) {
            val parent = File(targetPath).parent?.takeIf { it.isNotBlank() } ?: defaultScreenshotDir()
            val escapedParent = shellEscape(parent)
            val escapedTarget = shellEscape(targetPath)
            // chmod 600 与 FileManager 的上传路径保持一致，理由相同：这段脚本以 root 运行，
            // 666 会让一张可能含验证码/聊天内容的截图对设备上任意应用可读可写。
            // 补充：默认目录 /sdcard 是 FUSE 挂载，chmod 在那里本就被内核忽略；这条命令只在面板
            // 显式指定 /data/local/tmp 这类真实文件系统路径时才生效——而那恰恰是有风险的场景。
            val command = """
                mkdir -p $escapedParent 2>&1
                screencap -p $escapedTarget 2>&1
                status=${'$'}?
                if [ "${'$'}status" -eq 0 ] && [ -s $escapedTarget ]; then
                    chmod 600 $escapedTarget 2>/dev/null
                    bytes=${'$'}(wc -c < $escapedTarget 2>/dev/null | tr -d ' ')
                    echo "$SHELL_SUCCESS_MARKER${'$'}bytes"
                else
                    echo "$SHELL_FAILURE_MARKER${'$'}status"
                fi
            """.trimIndent()

            // Isolated: a screencap of a large display can outlast the state-report timeout, and
            // the metrics loop must keep reporting while it runs.
            val output = RootShell.executeIsolated(command, timeoutMs = 30_000)
            if (output.isBlank()) {
                return@withContext ShellScreenshotResult.Failure("Root/Shizuku shell 不可用或无输出")
            }

            val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            val successLine = lines.lastOrNull { it.startsWith(SHELL_SUCCESS_MARKER) }
            if (successLine != null) {
                val bytes = successLine.removePrefix(SHELL_SUCCESS_MARKER).toLongOrNull() ?: 0L
                if (bytes > 0L) {
                    return@withContext ShellScreenshotResult.Success(targetPath, bytes)
                }
                return@withContext ShellScreenshotResult.Failure("screencap 已返回成功，但文件为空")
            }

            val failureLine = lines.lastOrNull { it.startsWith(SHELL_FAILURE_MARKER) }
            val exitCode = failureLine?.removePrefix(SHELL_FAILURE_MARKER)?.ifBlank { "未知" } ?: "未知"
            val detail = lines
                .filterNot {
                    it.startsWith(SHELL_SUCCESS_MARKER) || it.startsWith(SHELL_FAILURE_MARKER)
                }
                .joinToString("\n")
                .ifBlank { "无详细错误输出" }

            ShellScreenshotResult.Failure("screencap 退出码 $exitCode: $detail")
        }

    private fun normalizeScreenshotPath(pathArg: String): String? = resolveScreenshotPath(
        requestedPath = pathArg.stripWrappingQuotes(),
        defaultDirectory = defaultScreenshotDir(),
        generatedFileName = "nezha_screenshot_${screenshotDateFormat.format(Date())}.png"
    )

    private fun defaultScreenshotDir(): String {
        return Environment.getExternalStorageDirectory().absolutePath
    }

    private fun formatScreenshotSuccess(path: String, bytes: Long, method: String): String {
        return buildString {
            append("\r\n")
            append("✅ 截图已保存\r\n")
            append("路径: $path\r\n")
            append("大小: $bytes bytes\r\n")
            append("方式: $method\r\n")
            append("\r\n")
        }
    }

    private fun String.stripWrappingQuotes(): String {
        val value = trim()
        if (value.length < 2) return value
        val first = value.first()
        val last = value.last()
        return if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            value.substring(1, value.length - 1).trim()
        } else {
            value
        }
    }


    private fun formatBytes(bytes: Long?): String {
        bytes ?: return "未知"
        val safeBytes = bytes.coerceAtLeast(0L)
        val unit = 1024.0
        return when {
            safeBytes >= GIB -> String.format(Locale.US, "%.2f GB", safeBytes / unit / unit / unit)
            safeBytes >= MIB -> String.format(Locale.US, "%.2f MB", safeBytes / unit / unit)
            safeBytes >= KIB -> String.format(Locale.US, "%.2f KB", safeBytes / unit)
            else -> "$safeBytes B"
        }
    }

    private fun formatGigabytes(bytes: Long?): String {
        bytes ?: return "未知"
        return String.format(Locale.US, "%.3f GB", bytes.coerceAtLeast(0L).toDouble() / GIB)
    }

    private sealed class ShellScreenshotResult {
        data class Success(val path: String, val bytes: Long) : ShellScreenshotResult()
        data class Failure(val reason: String) : ShellScreenshotResult()
    }

    private companion object {
        const val SHELL_SUCCESS_MARKER = "__NEZHA_SCREENSHOT_OK__:"
        const val SHELL_FAILURE_MARKER = "__NEZHA_SCREENSHOT_FAIL__:"

        val screenshotDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
