package com.nezhahq.agent.collector

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import android.os.SystemClock
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import com.nezhahq.agent.util.shellEscape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

private const val DUMPSYS_THROTTLE_MS = 5_000L
private const val PRIVILEGED_UNAVAILABLE_RETRY_MS = 30_000L

/** GPU 使用率的采集方案，按探测优先级由高到低排列。 */
internal enum class GpuCollectionStrategy {
    DIRECT,
    SHELL_FS,
    DUMPSYS,
    UNAVAILABLE
}

internal data class GpuProbeResult(
    val strategy: GpuCollectionStrategy,
    val usages: List<Double>
)

/**
 * Keeps normal and privileged strategy decisions independent for one collector instance.
 */
internal class GpuModeStrategyCache(
    private val monotonicTimeMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val privilegedUnavailableRetryMs: Long = PRIVILEGED_UNAVAILABLE_RETRY_MS
) {
    /**
     * A `Mutex`, not `synchronized`: the dumpsys fallback now runs on its own process through a
     * suspending call, and that call happens inside this critical section.
     *
     * The mutex is not reentrant, so nothing invoked from inside [collect] may call back into a
     * locking method of this class — that would deadlock against the lock the caller already
     * holds. This is why [collect] lets `readCached` report a dead strategy by returning null
     * instead of exposing a `clear` method for it to call.
     */
    private val lock = Mutex()
    private var normalStrategy: GpuCollectionStrategy? = null
    private var privilegedStrategy: GpuCollectionStrategy? = null
    private var privilegedUnavailableAtMs: Long? = null

    /**
     * @param readCached reads through the already chosen strategy; returning null means that
     *   strategy stopped working, which drops it so the next call probes again.
     */
    suspend fun collect(
        isPrivileged: Boolean,
        readCached: suspend (GpuCollectionStrategy) -> List<Double>?,
        probe: suspend () -> GpuProbeResult
    ): List<Double> = lock.withLock {
        val strategy = strategyFor(isPrivileged)
        if (strategy == null || shouldRetryUnavailable(isPrivileged, strategy)) {
            return@withLock probe().also { result ->
                cacheProbeResult(isPrivileged, result.strategy)
            }.usages
        }

        readCached(strategy) ?: run {
            setStrategy(isPrivileged, null)
            if (isPrivileged) privilegedUnavailableAtMs = null
            emptyList()
        }
    }

    private fun strategyFor(isPrivileged: Boolean): GpuCollectionStrategy? {
        return if (isPrivileged) privilegedStrategy else normalStrategy
    }

    private fun setStrategy(
        isPrivileged: Boolean,
        strategy: GpuCollectionStrategy?
    ) {
        if (isPrivileged) {
            privilegedStrategy = strategy
        } else {
            normalStrategy = strategy
        }
    }

    private fun cacheProbeResult(
        isPrivileged: Boolean,
        strategy: GpuCollectionStrategy
    ) {
        setStrategy(isPrivileged, strategy)
        if (isPrivileged) {
            privilegedUnavailableAtMs = if (strategy == GpuCollectionStrategy.UNAVAILABLE) {
                monotonicTimeMs()
            } else {
                null
            }
        }
    }

    private fun shouldRetryUnavailable(
        isPrivileged: Boolean,
        strategy: GpuCollectionStrategy
    ): Boolean {
        if (!isPrivileged || strategy != GpuCollectionStrategy.UNAVAILABLE) return false
        val unavailableAt = privilegedUnavailableAtMs ?: return true
        val elapsed = monotonicTimeMs() - unavailableAt
        return elapsed < 0L || elapsed >= privilegedUnavailableRetryMs
    }
}

/**
 * Caches and throttles dumpsys samples, including the sample that selected dumpsys initially.
 */
internal class GpuDumpsysThrottle(
    private val monotonicTimeMs: () -> Long,
    private val throttleMs: Long = DUMPSYS_THROTTLE_MS
) {
    /** A `Mutex` rather than `synchronized` because the reader it guards is a suspending call. */
    private val lock = Mutex()
    private var lastSampleAtMs: Long? = null
    private var lastResult: Double? = null

    suspend fun recordInitial(result: Double) = lock.withLock {
        lastSampleAtMs = monotonicTimeMs()
        lastResult = result
    }

    suspend fun read(reader: suspend () -> Double?): Double? = lock.withLock {
        val now = monotonicTimeMs()
        val sampledAt = lastSampleAtMs
        if (sampledAt != null && now >= sampledAt && now - sampledAt < throttleMs) {
            return@withLock lastResult
        }

        reader().also { result ->
            lastSampleAtMs = now
            lastResult = result
        }
    }
}

/**
 * GPU 数据采集器（独立模块，单一职责）。
 *
 * ## 职责划分
 * - **静态信息**：GPU 型号名称（通过 EGL14 + GLES20 获取，无需权限）
 * - **动态状态**：GPU 使用率百分比（通过 sysfs / dumpsys 获取，需 Root/Shizuku）
 *
 * ## 五级回退策略
 * | 优先级 | 方案 | 说明 |
 * |-------|------|------|
 * | P0    | sysfs 直读 | 仅 Android 9 及以下（API ≤ 28）或已手动 chmod 过的节点 |
 * | P1    | RootShell sysfs（已知厂商路径） | 核心主力，支持 Qualcomm/Mali/MediaTek |
 * | P1.5  | RootShell 动态扫描 /sys | 未知厂商兜底探测 |
 * | P2    | dumpsys 解析 | 最终兜底，命令慢且跑在独立进程上 |
 * | P3    | 返回空列表 | 设备不兼容 GPU 监控 |
 *
 * ## 线程安全
 * 缓存字段使用 @Volatile 保证可见性；策略选择与 dumpsys 节流各自持有一把 Mutex
 * （而非 synchronized，因为临界区内含挂起调用），保证探测只跑一次。
 *
 * ## 性能设计
 * - GPU 名称：首次调用时采集并永久缓存（硬件不变）
 * - sysfs 路径：首次采集时探测并缓存，后续直接读取
 * - P2 dumpsys：**采样周期不变**（调用方仍是每 2 秒调用一次），只是内部按时间戳节流——
 *   5 秒内的重复调用直接复用上次结果、不再执行 dumpsys，所以面板上这一项的实际刷新
 *   间隔是 5 秒。采集器本身不会、也无法要求调用方降低调用频率。
 * - P2 的两条 dumpsys 命令走 [RootShell.executeIsolated]（独立进程），不占用全进程
 *   共用的持久 Shell 会话锁：它们是整条采集链上最慢的命令，压在共享会话上会连带拖慢
 *   同一拍里的其它指标命令和 FileManager 的文件操作，进而可能超出面板的状态回执预算。
 */
class GpuCollector internal constructor(
    monotonicTimeMs: () -> Long = { SystemClock.elapsedRealtime() },
    privilegedUnavailableRetryMs: Long = PRIVILEGED_UNAVAILABLE_RETRY_MS
) {

    /**
     * 已知 GPU 厂商路径数据库的一条记录。
     *
     * - vendorHint: GL_VENDOR 中的关键词（用于优先匹配对应厂商路径，减少无效探测）
     * - path: sysfs 文件绝对路径
     * - parser: 原始文本 → [0.0, 100.0] 的解析函数
     */
    private data class SysfsEntry(
        val vendorHint: String,
        val path: String,
        val parser: (String) -> Double?
    )

    // ══════════════════════════════════════════════════════════════════════════
    // 缓存字段（@Volatile 保证多线程可见性）
    // ══════════════════════════════════════════════════════════════════════════

    /** 缓存的 GPU 渲染器名称（GL_RENDERER），如 "Adreno (TM) 730" */
    @Volatile private var cachedGpuName: String? = null

    /** 缓存的 GPU 厂商名称（GL_VENDOR），如 "Qualcomm"，用于路径优先匹配 */
    @Volatile private var cachedGpuVendor: String? = null

    private class ModeState(monotonicTimeMs: () -> Long) {
        @Volatile var sysfsPath: String? = null
        @Volatile var parser: ((String) -> Double?)? = null
        @Volatile var directReadFailed = false
        @Volatile var probeCompleted = false
        @Volatile var unavailableWarned = false
        val dumpsysThrottle = GpuDumpsysThrottle(monotonicTimeMs)
    }

    private val normalState = ModeState(monotonicTimeMs)
    private val privilegedState = ModeState(monotonicTimeMs)
    private val strategyCache = GpuModeStrategyCache(
        monotonicTimeMs = monotonicTimeMs,
        privilegedUnavailableRetryMs = privilegedUnavailableRetryMs
    )

    // ══════════════════════════════════════════════════════════════════════════
    // 公开 API：GPU 型号名称
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 获取 GPU 型号名称列表。
     *
     * 通过 EGL14 创建离屏上下文，调用 GLES20.glGetString(GL_RENDERER)。
     * 首次调用时采集并永久缓存，后续直接返回缓存值。
     * Android 设备通常只有 1 个 GPU，返回列表长度为 1。
     *
     * @return GPU 名称列表，采集失败返回空列表
     */
    fun getGpuNames(): List<String> {
        // 快速路径：已缓存直接返回
        cachedGpuName?.let { return listOf(it) }

        // 首次采集（synchronized 防止并发重复创建 EGL 上下文）
        synchronized(this) {
            // 双重检查锁
            cachedGpuName?.let { return listOf(it) }

            val (renderer, vendor) = queryGpuInfoViaEgl() ?: return emptyList()
            cachedGpuName = renderer
            cachedGpuVendor = vendor
            return listOf(renderer)
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 公开 API：GPU 使用率
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 获取 GPU 使用率列表（百分比 0.0~100.0）。
     *
     * 采用五级回退策略，首次调用时自动探测最优方案并缓存。
     * 普通模式（非 Root/Shizuku）下仅尝试 P0 直读，大概率返回空列表。
     *
     * @param isRootMode 是否处于 Root/Shizuku 提权模式
     * @return GPU 使用率列表，不可用时返回空列表
     */
    suspend fun getGpuUsages(isRootMode: Boolean): List<Double> {
        val state = stateFor(isRootMode)
        return strategyCache.collect(
            isPrivileged = isRootMode,
            readCached = { strategy -> readUsing(strategy, state) },
            probe = { probeAndRead(isRootMode, state) }
        )
    }

    /** @return null 表示该策略已失效，交由 [GpuModeStrategyCache] 丢弃缓存并重新探测。 */
    private suspend fun readUsing(
        strategy: GpuCollectionStrategy,
        state: ModeState
    ): List<Double>? {
        return when (strategy) {
            GpuCollectionStrategy.DIRECT -> readDirect(state)
            GpuCollectionStrategy.SHELL_FS -> readShellFs(state)
            GpuCollectionStrategy.DUMPSYS -> readDumpsys(state)
            GpuCollectionStrategy.UNAVAILABLE -> emptyList()
        }
    }

    private fun stateFor(isPrivileged: Boolean): ModeState {
        return if (isPrivileged) privilegedState else normalState
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 私有：EGL GPU 信息查询
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 通过 EGL14 创建离屏上下文获取 GPU 信息。
     *
     * 创建 pbuffer surface（无需可见窗口），调用 GLES20.glGetString()
     * 获取 GPU 渲染器名称和厂商名称，采集完成后立即释放所有 EGL 资源。
     *
     * @return Pair(GL_RENDERER, GL_VENDOR)，失败返回 null
     */
    private fun queryGpuInfoViaEgl(): Pair<String, String>? {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE

        try {
            // 1. 获取 EGL 默认显示设备
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) {
                Logger.i("GpuCollector: EGL 默认显示设备不可用")
                return null
            }

            // 2. 初始化 EGL
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                Logger.i("GpuCollector: EGL 初始化失败")
                return null
            }

            // 3. 选择 EGL 配置（最小化需求：GLES2 + pbuffer 支持）
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val chosen = EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            // 三个条件都满足才算拿到配置；收进局部变量后后续两处直接使用，无需 !!
            val config = configs[0]?.takeIf { chosen && numConfigs[0] > 0 }
            if (config == null) {
                Logger.i("GpuCollector: EGL 配置选择失败")
                return null
            }

            // 4. 创建 1x1 pbuffer surface（离屏，无需可见窗口）
            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) {
                Logger.i("GpuCollector: EGL pbuffer surface 创建失败")
                return null
            }

            // 5. 创建 GLES2 上下文
            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) {
                Logger.i("GpuCollector: EGL 上下文创建失败")
                return null
            }

            // 6. 绑定上下文到当前线程
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                Logger.i("GpuCollector: EGL makeCurrent 失败")
                return null
            }

            // 7. 查询 GPU 信息
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "unknown"
            val vendor = GLES20.glGetString(GLES20.GL_VENDOR) ?: "unknown"

            Logger.i("GpuCollector: GPU 型号检测成功 — Renderer='$renderer', Vendor='$vendor'")
            return Pair(renderer, vendor)
        } catch (e: Exception) {
            Logger.e("GpuCollector: EGL GPU 信息查询异常", e)
            return null
        } finally {
            // 8. 确保释放所有 EGL 资源（无论成功与否）
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    EGL14.eglTerminate(display)
                }
            } catch (e: Exception) {
                Logger.e("GpuCollector: EGL 资源释放异常", e)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 私有：探测链（首次调用时执行一次）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 执行完整的五级探测链，确定最优采集策略并缓存。
     *
     * 探测顺序：P0 → P1 → P1.5 → P2 → P3
     * 首次成功的策略会被缓存，后续调用直接复用。
     *
     * @param isRootMode 是否处于 Root/Shizuku 提权模式
     * @return 本次探测的 GPU 使用率列表
     */
    private suspend fun probeAndRead(isRootMode: Boolean, state: ModeState): GpuProbeResult {
        // A privileged negative-cache retry must repeat dynamic discovery as well as known paths.
        if (isRootMode && state.probeCompleted) {
            state.probeCompleted = false
        }

        // ── P0: Direct sysfs 直读（极速通道）──────────────────────────────
        // 仅在 Android 9 及以下（API ≤ 28，SELinux 对 untrusted_app 的限制还较松）时尝试；
        // Android 10+ 必然被拒，连一次探测都不必付出。
        // 两个分支最终都要置 directReadFailed：无论是版本不满足还是本次读失败，P0 都不再重试。
        if (!state.directReadFailed) {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                val result = probeDirectRead(state)
                if (result != null) {
                    Logger.i("GpuCollector: P0 直读成功 (路径=${state.sysfsPath})")
                    return GpuProbeResult(GpuCollectionStrategy.DIRECT, listOf(result))
                }
            }
            state.directReadFailed = true
        }

        // 以下策略均需要 Root/Shizuku
        if (!isRootMode) {
            if (!state.unavailableWarned) {
                Logger.i("GpuCollector: 普通模式下 GPU 使用率不可用（需 Root/Shizuku）")
                state.unavailableWarned = true
            }
            return GpuProbeResult(GpuCollectionStrategy.UNAVAILABLE, emptyList())
        }

        // ── P1: Shell-FS 已知厂商路径 ────────────────────────────────────
        val p1Result = probeKnownPaths(state)
        if (p1Result != null) {
            Logger.i("GpuCollector: P1 已知路径命中 (路径=${state.sysfsPath})")
            return GpuProbeResult(GpuCollectionStrategy.SHELL_FS, listOf(p1Result))
        }

        // ── P1.5: 动态扫描 /sys ──────────────────────────────────────────
        val p15Result = probeDynamicScan(state)
        if (p15Result != null) {
            Logger.i("GpuCollector: P1.5 动态扫描命中 (路径=${state.sysfsPath})")
            return GpuProbeResult(GpuCollectionStrategy.SHELL_FS, listOf(p15Result))
        }

        // ── P2: dumpsys gpu 兜底 ─────────────────────────────────────────
        val p2Result = readDumpsysInternal()
        if (p2Result != null) {
            state.dumpsysThrottle.recordInitial(p2Result)
            Logger.i("GpuCollector: P2 dumpsys 解析成功")
            return GpuProbeResult(GpuCollectionStrategy.DUMPSYS, listOf(p2Result))
        }

        // ── P3: 不可用 ──────────────────────────────────────────────────
        state.probeCompleted = true
        if (!state.unavailableWarned) {
            Logger.i("GpuCollector: 当前设备不兼容 GPU 硬件级监控，GPU 使用率不可用")
            state.unavailableWarned = true
        }
        return GpuProbeResult(GpuCollectionStrategy.UNAVAILABLE, emptyList())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 私有：P0 — Direct sysfs 直读
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * P0: 尝试以普通 App 权限直接读取 sysfs GPU 节点。
     *
     * 实际只在 Android 9 及以下（API ≤ 28）被调用，见 [probeAndRead] 的版本判断；
     * 那之前 SELinux 对 untrusted_app 的限制还较松。
     * 遍历已知路径，首个 canRead() 且解析成功的路径即为命中。
     *
     * @return 解析出的 GPU 使用率，所有路径均不可读返回 null
     */
    private fun probeDirectRead(state: ModeState): Double? {
        for (entry in KNOWN_SYSFS_ENTRIES) {
            try {
                val file = File(entry.path)
                if (file.exists() && file.canRead()) {
                    val raw = file.readText()
                    val value = entry.parser(raw)
                    if (value != null) {
                        state.sysfsPath = entry.path
                        state.parser = entry.parser
                        return value.coerceIn(0.0, 100.0)
                    }
                }
            } catch (_: Exception) {
                // SELinux 拒绝或文件不存在，继续下一个
            }
        }
        return null
    }

    /**
     * 使用缓存的 P0 策略直接读取 sysfs。
     *
     * @return 读取失败时返回 null，表示该策略已失效、需要清掉缓存重新探测。
     *   之所以用返回值上报而不是反过来去调用 [GpuModeStrategyCache] 上的方法作废缓存：
     *   本函数正跑在它的锁里，而那把 Mutex 不可重入，回调会把自己锁死。
     */
    private fun readDirect(state: ModeState): List<Double>? {
        val path = state.sysfsPath ?: return emptyList()
        val parser = state.parser ?: return emptyList()
        return try {
            val raw = File(path).readText()
            val value = parser(raw)
            if (value != null) listOf(value.coerceIn(0.0, 100.0)) else emptyList()
        } catch (_: Exception) {
            // 权限可能在系统更新后被收紧，降级处理
            Logger.i("GpuCollector: P0 直读失败，清除缓存以便重新探测")
            state.directReadFailed = true
            null
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 私有：P1 — Shell-FS 已知厂商路径
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * P1: 通过 RootShell 读取已知厂商 sysfs 路径。
     *
     * 按 GL_VENDOR 关键词优先匹配对应厂商的路径（减少无效探测），
     * 然后再尝试其余厂商的路径。
     *
     * @return 解析出的 GPU 使用率，所有路径均失败返回 null
     */
    private fun probeKnownPaths(state: ModeState): Double? {
        val vendor = cachedGpuVendor?.lowercase() ?: ""

        // 优先匹配 GL_VENDOR 对应的路径
        val sortedEntries = KNOWN_SYSFS_ENTRIES.sortedByDescending { entry ->
            if (vendor.contains(entry.vendorHint)) 1 else 0
        }

        for (entry in sortedEntries) {
            try {
                val raw = RootShell.executeFirstLine("cat ${shellEscape(entry.path)} 2>/dev/null")
                if (!raw.isNullOrBlank()) {
                    val value = entry.parser(raw)
                    if (value != null) {
                        state.sysfsPath = entry.path
                        state.parser = entry.parser
                        return value.coerceIn(0.0, 100.0)
                    }
                }
            } catch (_: Exception) {
                // 路径不存在或权限不足，继续下一个
            }
        }
        return null
    }

    /**
     * 使用缓存的 P1/P1.5 策略通过 RootShell 读取 sysfs。
     * 读取失败时自动降级。
     */
    private fun readShellFs(state: ModeState): List<Double> {
        val path = state.sysfsPath ?: return emptyList()
        val parser = state.parser ?: return emptyList()
        return try {
            val raw = RootShell.executeFirstLine("cat ${shellEscape(path)} 2>/dev/null")
            if (!raw.isNullOrBlank()) {
                val value = parser(raw)
                if (value != null) return listOf(value.coerceIn(0.0, 100.0))
            }
            // 读取失败但不立即降级（可能是瞬时错误）
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 私有：P1.5 — 动态扫描 /sys
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * P1.5: 当已知路径全部失败时，动态扫描 /sys 目录寻找 GPU 相关节点。
     *
     * 使用 find 命令限制搜索深度和结果数量，防止扫描耗时过长。
     * 仅执行一次，发现的路径缓存后按通用 parser 尝试解析。
     *
     * @return 解析出的 GPU 使用率，未找到或解析失败返回 null
     */
    private fun probeDynamicScan(state: ModeState): Double? {
        if (state.probeCompleted) return null // 已扫描过，不重复

        try {
            val scanCmd = "find /sys -maxdepth 6 -type f \\( " +
                "-name 'gpu_busy*' -o " +
                "-name 'gpu_util*' -o " +
                "-name 'gpu_loading' -o " +
                "-name 'utilization' -path '*gpu*' -o " +
                "-name 'utilization' -path '*mali*' " +
                "\\) 2>/dev/null | head -5"

            val output = RootShell.execute(scanCmd)
            if (output.isNotBlank()) {
                for (path in output.lineSequence()) {
                    val trimmed = path.trim()
                    if (trimmed.isEmpty()) continue

                    // 尝试读取并用通用 parser 解析
                    val raw = RootShell.executeFirstLine("cat ${shellEscape(trimmed)} 2>/dev/null")
                    if (!raw.isNullOrBlank()) {
                        val value = tryGenericParse(raw, trimmed)
                        if (value != null) {
                            state.sysfsPath = trimmed
                            // 缓存通用 parser
                            state.parser = { r -> tryGenericParse(r, trimmed) }
                            state.probeCompleted = true
                            return value.coerceIn(0.0, 100.0)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("GpuCollector: P1.5 动态扫描异常", e)
        }

        state.probeCompleted = true
        return null
    }

    /**
     * 通用解析器：尝试从原始文本中提取 GPU 使用率数值。
     *
     * 支持以下常见格式：
     * - 纯数字：`"45"` → 45.0（假定 0~100）
     * - 带百分号：`"45 %"` → 45.0
     * - 空格分隔的 busy/total：`"12345 67890"` → (12345/67890)*100
     *
     * @param raw sysfs 节点的原始文本
     * @param path 该节点的 sysfs 路径，用于针对性判断（如 mali 使用率 0~256）
     * @return [0.0, 100.0] 的 GPU 使用率，无法解析返回 null
     */
    private fun tryGenericParse(raw: String, path: String): Double? {
        val trimmed = raw.trim()

        // 格式 1: 纯数字或带百分号
        val percentMatch = PERCENT_RE.find(trimmed)
        if (percentMatch != null) {
            return percentMatch.groupValues[1].toDoubleOrNull()?.coerceIn(0.0, 100.0)
        }

        // 格式 2: 纯整数
        val directValue = trimmed.toDoubleOrNull()
        if (directValue != null) {
            // Mali 节点通常范围是 0~256
            val isMali = path.contains("mali", ignoreCase = true)
            if (isMali && directValue in 0.0..256.0) {
                return (directValue / 256.0 * 100.0).coerceIn(0.0, 100.0)
            } else if (directValue in 0.0..100.0) {
                return directValue
            }
        }

        // 格式 3: "busy total" 分数格式
        val parts = trimmed.split(WHITESPACE_RE)
        if (parts.size == 2) {
            val busy = parts[0].toLongOrNull()
            val total = parts[1].toLongOrNull()
            if (busy != null && total != null && total > 0) {
                return (busy.toDouble() / total.toDouble() * 100.0).coerceIn(0.0, 100.0)
            }
        }

        return null
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 私有：P2 — dumpsys 兜底
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 使用缓存的 P2 策略读取 dumpsys，内部时间戳节流。
     *
     * 距上次调用不足 5 秒时直接返回上次的缓存值，
     * 避免高频 dumpsys 调用（单次耗时 50~200ms）。
     */
    private suspend fun readDumpsys(state: ModeState): List<Double> {
        val result = state.dumpsysThrottle.read { readDumpsysInternal() }
        return result?.let { listOf(it) } ?: emptyList()
    }

    /**
     * P2 内部实现：执行 dumpsys 并解析利用率字段。
     *
     * 尝试匹配常见格式：
     * - "GPU Utilization: 15%"
     * - "GPUTotalUtilization: 15%"
     * - "Load: 15%"
     * - "gpu_loading: 15"
     *
     * 用 [RootShell.executeIsolated] 而不是共享持久会话：dumpsys 是本采集链上最慢的命令，
     * 而那个会话是全进程唯一的——指标循环每 2 秒已经要在它上面串行跑近十条命令，还要和
     * FileManager 的 ls/stat 抢同一把锁。把慢命令压在上面，累计耗时可能突破面板的状态回执
     * 预算，看门狗会据此判定连接已死。独立进程要多付一次 su 启动开销，但这条路本就被节流到
     * 最多 5 秒一次，且只在 sysfs 全部失败的设备上才会走到。
     *
     * @return 解析出的 GPU 使用率，解析失败返回 null
     */
    private suspend fun readDumpsysInternal(): Double? {
        try {
            // 尝试 dumpsys gpu
            val gpuOutput = RootShell.executeIsolated("dumpsys gpu 2>/dev/null")
            if (gpuOutput.isNotBlank()) {
                val match = DUMPSYS_UTIL_RE.find(gpuOutput)
                if (match != null) {
                    val value = match.groupValues[1].toDoubleOrNull()
                    if (value != null) return value.coerceIn(0.0, 100.0)
                }
            }
        } catch (cancellation: CancellationException) {
            // 改用 executeIsolated 之后这里才会收到取消：它是 suspend 的，而
            // CancellationException 是 Exception 的子类，被下面的兜底吞掉会让协程取消失效——
            // 白跑一次 SurfaceFlinger，还会把 null 连同新时间戳写进节流缓存，
            // 使重连后 5 秒内 GPU 一律上报空。
            throw cancellation
        } catch (_: Exception) {
            // dumpsys 不可用
        }

        try {
            // 备选: dumpsys SurfaceFlinger 中的 GPU 相关字段
            val sfOutput = RootShell.executeIsolated(
                "dumpsys SurfaceFlinger --latency 2>/dev/null | head -20"
            )
            if (sfOutput.isNotBlank()) {
                // SurfaceFlinger 的 latency 输出较间接，仅作最终尝试
                val match = DUMPSYS_UTIL_RE.find(sfOutput)
                if (match != null) {
                    val value = match.groupValues[1].toDoubleOrNull()
                    if (value != null) return value.coerceIn(0.0, 100.0)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // SurfaceFlinger 不可用
        }

        return null
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 进程级共享常量：正则与路径表都与实例状态无关，放在伴生对象里只编译/分配一次，
    // 而不是每 new 一个采集器就重建一遍。
    // ══════════════════════════════════════════════════════════════════════════

    private companion object {
        /** 空白分割正则 */
        val WHITESPACE_RE = Regex("\\s+")

        /** dumpsys 输出中 GPU 利用率字段的匹配正则 */
        val DUMPSYS_UTIL_RE = Regex(
            """(?:GPU\s*(?:Total\s*)?Utilization|gpu[_\s]*load(?:ing)?|Load)\s*[:=]\s*(\d+)""",
            RegexOption.IGNORE_CASE
        )

        /** 通用百分比匹配正则 */
        val PERCENT_RE = Regex("""(\d+(?:\.\d+)?)\s*%""")

        /** 已知厂商 sysfs 路径数据库（按优先级排列） */
        val KNOWN_SYSFS_ENTRIES = listOf(
            // ── Qualcomm Adreno ──────────────────────────────────────────────
            // gpu_busy_percentage 格式："xx %" → 直接取第一个数字
            SysfsEntry("qualcomm", "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage") { raw ->
                raw.trim().split(WHITESPACE_RE).firstOrNull()?.toDoubleOrNull()
            },
            // gpubusy 格式："busy_ticks total_ticks" → busy/total * 100
            SysfsEntry("qualcomm", "/sys/class/kgsl/kgsl-3d0/gpubusy") { raw ->
                val parts = raw.trim().split(WHITESPACE_RE)
                if (parts.size >= 2) {
                    val busy = parts[0].toLongOrNull() ?: return@SysfsEntry null
                    val total = parts[1].toLongOrNull() ?: return@SysfsEntry null
                    if (total > 0) (busy.toDouble() / total.toDouble() * 100.0) else null
                } else null
            },

            // ── ARM Mali (Samsung Exynos / Google Tensor / Huawei) ────────────
            // utilization 格式：整数 0~256 → (value / 256) * 100
            SysfsEntry("arm", "/sys/class/misc/mali0/device/utilization") { raw ->
                val value = raw.trim().toIntOrNull() ?: return@SysfsEntry null
                (value.toDouble() / 256.0 * 100.0).coerceIn(0.0, 100.0)
            },

            // ── MediaTek 天玑（使用 Mali GPU 但路径不同）──────────────────────
            // gpu_loading 格式：整数 0~100 → 直接使用
            SysfsEntry("mediatek", "/sys/module/ged/parameters/gpu_loading") { raw ->
                raw.trim().toDoubleOrNull()?.coerceIn(0.0, 100.0)
            },
            // 备选路径（部分联发科内核版本）
            SysfsEntry("mediatek", "/sys/kernel/ged/hal/gpu_utilization") { raw ->
                raw.trim().toDoubleOrNull()?.coerceIn(0.0, 100.0)
            },

            // ── Imagination PowerVR / IMG（市占极低，路径不统一）─────────────
            SysfsEntry("imagination", "/sys/kernel/debug/pvr/status") { raw ->
                // PowerVR 的 debug 节点格式因驱动版本而异，尝试匹配百分比
                PERCENT_RE.find(raw)?.groupValues?.get(1)?.toDoubleOrNull()
            }
        )
    }
}
