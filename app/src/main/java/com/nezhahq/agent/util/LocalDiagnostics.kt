package com.nezhahq.agent.util

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

data class LocalDiagnosticState(
    val enabled: Boolean = false,
    val cleaning: Boolean = true,
    val error: String? = null
)

internal data class DiagnosticLimits(
    val maxLineBytes: Int = 2_048,
    val maxFileBytes: Long = 65_536,
    val maxTotalBytes: Long = 262_144,
    val maxFiles: Int = 4,
    val maxPending: Int = 64,
    val retentionMillis: Long = 24 * 60 * 60 * 1_000L
) {
    init {
        require(maxLineBytes > 1 && maxFileBytes >= maxLineBytes && maxTotalBytes >= maxFileBytes)
        require(maxFiles > 0 && maxPending > 0 && retentionMillis > 0)
    }
}

/**
 * Process-only opt-in. The directory provider is evaluated by the IO worker, including Android's
 * noBackupFilesDir getter. A bounded data queue cannot delay/drop disable or cleanup: control state
 * is separate, and a single worker always cleans the previous generation before writing the next.
 */
class LocalDiagnostics internal constructor(
    directory: () -> File,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val limits: DiagnosticLimits = DiagnosticLimits()
) {
    private data class Record(val generation: Long, val time: Long, val message: String, val failure: Throwable?)
    private val lock = Any()
    private val pending = ArrayDeque<Record>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val store by lazy { DiagnosticFileStore(directory(), limits) }
    private var generation = 0L
    private var cleanupPending = true
    private var version = 0L
    private val completed = MutableStateFlow(-1L)
    private val _state = MutableStateFlow(LocalDiagnosticState())
    val state: StateFlow<LocalDiagnosticState> = _state.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                if (state.value.enabled) {
                    withTimeoutOrNull(limits.retentionMillis.coerceAtMost(60_000L)) { wake.receive() }
                } else {
                    wake.receive()
                }
                drain()
            }
        }
        wake.trySend(Unit) // Remove files from previous processes even though this process is off.
    }

    fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            if (_state.value.enabled == enabled && _state.value.error == null) return
            generation++
            version++
            pending.clear()
            cleanupPending = true
            _state.value = LocalDiagnosticState(enabled = enabled)
        }
        wake.trySend(Unit)
    }

    internal fun record(message: String, failure: Throwable?) {
        synchronized(lock) {
            if (!_state.value.enabled || pending.size >= limits.maxPending) return
            pending.addLast(Record(generation, clock(), SafeLogText.message(message), failure))
            version++
        }
        wake.trySend(Unit)
    }

    private fun drain() {
        try {
            while (true) {
                val cleanupGeneration = synchronized(lock) {
                    if (cleanupPending) {
                        cleanupPending = false
                        generation
                    } else null
                }
                if (cleanupGeneration != null) {
                    store.clear()
                    synchronized(lock) {
                        if (generation == cleanupGeneration) _state.value = _state.value.copy(cleaning = false)
                    }
                    continue
                }
                val record = synchronized(lock) { if (pending.isEmpty()) null else pending.removeFirst() }
                    ?: break
                val current = synchronized(lock) { _state.value.enabled && record.generation == generation }
                if (current) {
                    // No other worker can clean while append runs. A concurrent disable is the next
                    // control action, so an already-started old write is removed before re-enable.
                    val detail = record.failure?.let(SafeErrorText::diagnostic).orEmpty()
                    store.append(record.time, "${record.time} ${record.message} | $detail", clock())
                }
            }
            store.expire(clock())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            synchronized(lock) {
                generation++
                pending.clear()
                cleanupPending = false
                _state.value = LocalDiagnosticState(enabled = false, cleaning = false,
                    error = "本地诊断写入或清理失败，已停止记录；请检查可用存储空间，或重启后重试清理。")
            }
            // Best effort after a failed write; errors stay local and are never logged recursively.
            runCatching { store.clear() }
        } finally {
            synchronized(lock) {
                if (!cleanupPending && pending.isEmpty()) completed.value = version
            }
        }
    }

    /** Waits for prior requests and an expiry pass; useful for deterministic lifecycle tests. */
    internal suspend fun awaitIdle() {
        val target = synchronized(lock) { ++version }
        wake.trySend(Unit)
        withTimeout(5_000) { completed.first { it >= target } }
    }

    internal suspend fun shutdown() {
        setEnabled(false)
        try { awaitIdle() } finally { scope.cancel() }
    }
}

/** Worker-confined file operations; file creation time is in the name, never inferred from mtime. */
internal class DiagnosticFileStore(private val directory: File, private val limits: DiagnosticLimits) {
    private val ownedName = Regex("diagnostic-([0-9]+)-([0-9]+)\\.log")
    private var sequence = 0L
    private var current: File? = null

    fun append(recordTime: Long, text: String, now: Long) {
        expire(now)
        if (now < recordTime || now - recordTime >= limits.retentionMillis) return
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Diagnostic directory unavailable")
        val bytes = boundedUtf8(text, limits.maxLineBytes - 1) + byteArrayOf('\n'.code.toByte())
        val old = current
        if (old == null || !old.exists() || old.length() + bytes.size > limits.maxFileBytes) {
            current = File(directory, "diagnostic-$recordTime-${sequence++}.log")
        }
        current!!.appendBytes(bytes)
        var total = 0L
        files().sortedWith(compareByDescending<File> { createdAt(it) }
            .thenByDescending { ownedName.matchEntire(it.name)!!.groupValues[2].toLong() })
            .forEachIndexed { index, file ->
                total += file.length()
                if (index >= limits.maxFiles || total > limits.maxTotalBytes) delete(file)
            }
    }

    fun expire(now: Long) {
        files().filter { now < createdAt(it) || now - createdAt(it) >= limits.retentionMillis }
            .forEach(::delete)
    }

    fun clear() {
        files().forEach(::delete)
        current = null
    }

    private fun files(): List<File> {
        if (!directory.exists()) return emptyList()
        return directory.listFiles()?.filter { ownedName.matches(it.name) }
            ?: throw IOException("Diagnostic directory unreadable")
    }

    private fun createdAt(file: File): Long = ownedName.matchEntire(file.name)!!.groupValues[1].toLong()

    private fun delete(file: File) {
        if (!file.delete() && file.exists()) throw IOException("Diagnostic file cleanup failed")
        if (current == file) current = null
    }

    private fun boundedUtf8(text: String, maxBytes: Int): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return bytes
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
        return bytes.copyOf(end)
    }
}
