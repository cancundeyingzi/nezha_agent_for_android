package com.nezhahq.agent.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where [Logger] writes once it has buffered a line for the UI.
 *
 * Logging is ambient — 25 files call [Logger] — so it stays a global façade rather than a
 * constructor parameter threaded everywhere. Only the platform write is swappable, which is what a
 * JVM test needs when `android.util.Log` is not stubbed.
 */
interface PlatformLogSink {
    fun info(message: String)
    fun error(message: String, throwable: Throwable?)
}

private object AndroidLogSink : PlatformLogSink {
    private const val TAG = "NezhaAgent"

    override fun info(message: String) {
        android.util.Log.i(TAG, message)
    }

    override fun error(message: String, throwable: Throwable?) {
        android.util.Log.e(TAG, message, throwable)
    }
}

/**
 * 全局日志管理器（单例），为 UI 层提供日志流，同时写入 Android Log。
 *
 * ## 去重/节流机制
 * 同一消息在 [DEDUP_WINDOW_MS] 毫秒内重复出现时，不再追加新条目，
 * 而是在原条目后面更新 "×N" 重复计数。此机制可有效防止高频采集循环中
 * 重复错误日志填满缓冲区（如每 2 秒一次的 Root 权限失败），
 * 保护真正重要的运行信息不被冲掉。
 *
 * ## 线程安全
 * 所有状态更新通过 [synchronized] 保护，可在任意线程安全调用。
 *
 * ## 缓冲区策略
 * 最多保留 [MAX_LOG_SIZE] 条日志。超出时移除最早的条目（FIFO）。
 */
object Logger {

    /** 日志缓冲区最大容量。 */
    private const val MAX_LOG_SIZE = 200

    /**
     * 去重窗口时间（毫秒）。
     * 同一条消息在此时间窗口内重复出现时，仅更新计数而不追加新条目。
     * 设置为 30 秒：覆盖 SystemStateCollector 的 2 秒采集周期 × 15 次，
     * 足以抑制绝大多数高频重复日志。
     */
    private const val DEDUP_WINDOW_MS = 30_000L

    /** 初始化提示；[_logs] 与缓冲区的首元素同为此文本。 */
    private const val INITIAL_MESSAGE = "System Logger Initialized."

    // ── 去重/缓冲/序号逻辑下沉到 LogBuffer（纯逻辑 + 可注入时钟，便于 JVM 单测）──
    // Logger 只保留三件事：格式化时间戳、写平台日志、用 synchronized 串行化并发写。
    private val buffer = LogBuffer(
        maxSize = MAX_LOG_SIZE,
        dedupWindowMs = DEDUP_WINDOW_MS,
        initialMessage = INITIAL_MESSAGE
    )

    // ── StateFlow：供 Compose UI 层以 List<LogEntry> 形式观察 ──
    // 由 List<String> 升级为 List<LogEntry>：每条日志带一个单调递增、永不复用的 id 作为稳定身份。
    // 这是 ConfigScreen 日志窗能正确增量刷新的前提——缓冲区满后 removeAt(0) 只是让下标整体前移，
    // 各条 id 不变，Compose 便不会把“下标错位”误判成“整列都换了”而全量重绘。
    private val _logs = MutableStateFlow(buffer.snapshot())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    // ── 时间格式化 ──

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** Swapped by tests that must not reach `android.util.Log`. */
    @Volatile
    internal var platformSink: PlatformLogSink = AndroidLogSink

    // ══════════════════════════════════════════════════════════════════════════
    // 公开 API
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 记录 INFO 级别日志。
     *
     * @param message 日志消息文本
     */
    fun i(message: String) {
        // SimpleDateFormat 非线程安全，需在 synchronized 中格式化时间
        val formattedLog = synchronized(this) {
            val time = dateFormat.format(Date())
            "[$time] $message"
        }
        addLogWithDedup(formattedLog, message)
        platformSink.info(message)
    }

    /**
     * 记录 ERROR 级别日志。
     *
     * @param message 日志消息文本
     * @param throwable 可选的异常对象（其 message 会附加到日志末尾）
     */
    fun e(message: String, throwable: Throwable? = null) {
        val err = throwable?.let { " - ${it.message ?: it.javaClass.simpleName}" } ?: ""
        // SimpleDateFormat 非线程安全，需在 synchronized 中格式化时间
        val formattedLog = synchronized(this) {
            val time = dateFormat.format(Date())
            "[$time] ERROR: $message$err"
        }
        // 去重仅基于主消息，忽略异常详情（同一个方法产生的异常消息通常相同）
        addLogWithDedup(formattedLog, message)
        platformSink.error(message, throwable)
    }

    /**
     * 获取所有日志的完整拼接字符串（用于复制到剪贴板等一次性场景）。
     *
     * 注意：此方法会创建一个新的大字符串，不应在高频渲染路径中调用。
     */
    fun getLogString(): String {
        synchronized(this) {
            // 行为保持不变：仍是把每条日志的展示文本用换行拼成一个大字符串。
            return buffer.snapshot().joinToString("\n") { it.text }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 内部实现
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 带去重/节流逻辑的日志追加。
     *
     * 去重/淘汰/序号的具体规则见 [LogBuffer]。此处只负责：用 synchronized 串行化并发写
     * （[LogBuffer] 自身不加锁，沿用旧实现由 Logger 统一保护的约定），拿到新快照后发布到
     * [_logs] 触发 Compose 重组。
     *
     * @param formattedLog 带时间戳的完整日志字符串（展示用）
     * @param rawMessage 不含时间戳的原始消息（用于去重比对）
     */
    private fun addLogWithDedup(formattedLog: String, rawMessage: String) {
        val snapshot = synchronized(this) {
            buffer.add(formattedLog, rawMessage)
        }
        _logs.value = snapshot
    }
}

/**
 * 一条日志的稳定身份 + 展示文本。
 *
 * [id] 由 [LogBuffer] 单调递增分配且永不复用，作为 LazyColumn 的 key：它不随条目在列表中的
 * 下标变化而变化（缓冲区淘汰最旧条目会让所有下标前移，但 id 不动），从而避免整列重绘。
 * [text] 是带时间戳、可能带 “×N” 重复计数的展示字符串。
 */
data class LogEntry(val id: Long, val text: String)

/**
 * 日志缓冲区的纯逻辑：去重/节流、FIFO 淘汰、单调递增序号。
 *
 * 从 [Logger] 抽出的目的有二：
 * 1. 让这段容易出错的时序逻辑可以在 JVM 上直接单测（[clock] 可注入，无需真实等待去重窗口）；
 * 2. 让 [Logger] 只关心“格式化 + 平台写 + 加锁 + 发布”，职责单一。
 *
 * ## 线程安全
 * 本类**不**自带同步；并发访问由调用方（[Logger] 的 `synchronized(this)`）保证，
 * 与重构前的行为完全一致。
 *
 * ## 去重规则
 * 新消息若与上一条的 [rawMessage] 相同且在 [dedupWindowMs] 窗口内，则不追加新条目，
 * 而是**原地更新最后一条**（保留其 id）的展示文本，追加 “×N” 计数；否则追加一条带新 id 的条目。
 * 超过窗口仍在重复时按“新一轮”处理，追加新条目并重置计数，避免某条日志永远停在列表底部。
 */
internal class LogBuffer(
    private val maxSize: Int,
    private val dedupWindowMs: Long,
    initialMessage: String,
    private val clock: () -> Long = System::currentTimeMillis
) {
    // 首元素 id 固定为 0，后续 id 从 1 起单调递增。
    private val entries = mutableListOf(LogEntry(0L, initialMessage))
    private var nextId: Long = 1L

    private var lastRawMessage: String? = null
    private var lastRepeatCount: Int = 0
    private var lastLogTimeMs: Long = 0L

    /** 当前日志的不可变快照。 */
    fun snapshot(): List<LogEntry> = entries.toList()

    /**
     * 追加一条日志（或在去重窗口内折叠进最后一条），返回追加后的新快照。
     *
     * @param formattedLog 展示用文本（带时间戳）
     * @param rawMessage 去重比对用的原始消息
     */
    fun add(formattedLog: String, rawMessage: String): List<LogEntry> {
        val now = clock()
        val isDuplicate = rawMessage == lastRawMessage && (now - lastLogTimeMs) < dedupWindowMs

        if (isDuplicate && entries.isNotEmpty()) {
            // 重复：保留最后一条的 id（身份不变 → LazyColumn 原地更新而非增删），只刷新 ×N 文本。
            lastRepeatCount++
            lastLogTimeMs = now
            val lastIndex = entries.lastIndex
            entries[lastIndex] = entries[lastIndex].copy(text = "$formattedLog ×$lastRepeatCount")
        } else {
            // 新消息或窗口已过期：追加一条带全新 id 的条目。
            lastRawMessage = rawMessage
            lastRepeatCount = 1
            lastLogTimeMs = now
            entries.add(LogEntry(nextId++, formattedLog))
            if (entries.size > maxSize) {
                entries.removeAt(0)
            }
        }
        return snapshot()
    }
}
