package com.nezhahq.agent.collector

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import java.io.File

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
 * | P0    | sysfs 直读 | 仅 Android 9 以下或已手动 chmod 过的节点 |
 * | P1    | RootShell sysfs（已知厂商路径） | 核心主力，支持 Qualcomm/Mali/MediaTek |
 * | P1.5  | RootShell 动态扫描 /sys | 未知厂商兜底探测 |
 * | P2    | dumpsys gpu 解析 | 最终兜底，内部节流降频 |
 * | P3    | 返回空列表 | 设备不兼容 GPU 监控 |
 *
 * ## 线程安全
 * 所有缓存字段使用 @Volatile 保护，探测逻辑通过 synchronized 保证单次执行。
 *
 * ## 性能设计
 * - GPU 名称：首次调用时采集并永久缓存（硬件不变）
 * - sysfs 路径：首次采集时探测并缓存，后续直接读取
 * - P2 dumpsys：内部时间戳节流，5 秒内复用上次结果
 */
object GpuCollector {

    // ══════════════════════════════════════════════════════════════════════════
    // 常量
    // ══════════════════════════════════════════════════════════════════════════

    /** P2 dumpsys 节流间隔（毫秒）：dumpsys 单次耗时 50~200ms，不宜高频调用 */
    private const val DUMPSYS_THROTTLE_MS = 5000L

    /**
     * 已知 GPU 厂商路径数据库。
     *
     * 每个条目包含：
     * - vendorHint: GL_VENDOR 中的关键词（用于优先匹配对应厂商路径，减少无效探测）
     * - path: sysfs 文件绝对路径
     * - parser: 原始文本 → [0.0, 100.0] 的解析函数
     */
    private data class SysfsEntry(
        val vendorHint: String,
        val path: String,
        val parser: (String) -> Double?
    )

    /** 已知厂商 sysfs 路径数据库（按优先级排列） */
    private val KNOWN_SYSFS_ENTRIES = listOf(
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

    // ══════════════════════════════════════════════════════════════════════════
    // 预编译正则常量
    // ══════════════════════════════════════════════════════════════════════════

    /** 空白分割正则 */
    private val WHITESPACE_RE = Regex("\\s+")

    /** dumpsys 输出中 GPU 利用率字段的匹配正则 */
    private val DUMPSYS_UTIL_RE = Regex(
        """(?:GPU\s*(?:Total\s*)?Utilization|gpu[_\s]*load(?:ing)?|Load)\s*[:=]\s*(\d+)""",
        RegexOption.IGNORE_CASE
    )

    /** 通用百分比匹配正则 */
    private val PERCENT_RE = Regex("""(\d+(?:\.\d+)?)\s*%""")

    // ══════════════════════════════════════════════════════════════════════════
    // 策略枚举
    // ══════════════════════════════════════════════════════════════════════════

    /** GPU 使用率采集策略，标识当前使用的回退级别 */
    private enum class Strategy {
        /** P0: 普通模式直接 File.readText() */
        DIRECT,
        /** P1/P1.5: 通过 RootShell cat sysfs 路径 */
        SHELL_FS,
        /** P2: 通过 RootShell 执行 dumpsys gpu */
        DUMPSYS,
        /** P3: 所有方案均失败，不可用 */
        UNAVAILABLE
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 缓存字段（@Volatile 保证多线程可见性）
    // ══════════════════════════════════════════════════════════════════════════

    /** 缓存的 GPU 渲染器名称（GL_RENDERER），如 "Adreno (TM) 730" */
    @Volatile private var cachedGpuName: String? = null

    /** 缓存的 GPU 厂商名称（GL_VENDOR），如 "Qualcomm"，用于路径优先匹配 */
    @Volatile private var cachedGpuVendor: String? = null

    /** 缓存的可用 sysfs 路径 */
    @Volatile private var cachedSysfsPath: String? = null

    /** 缓存的 sysfs 路径对应的解析函数 */
    @Volatile private var cachedParser: ((String) -> Double?)? = null

    /** 缓存的当前策略级别 */
    @Volatile private var cachedStrategy: Strategy? = null

    /** P0 直读是否已确认失败（true 后永久跳过 P0） */
    @Volatile private var directReadFailed = false

    /** sysfs 路径探测是否已完成（防止重复探测） */
    @Volatile private var probeCompleted = false

    /** P3 不可用警告是否已打印（日志去重） */
    @Volatile private var gpuUnavailableWarned = false

    /** P2 dumpsys 上次执行的时间戳（用于节流） */
    @Volatile private var lastDumpsysTimeMs = 0L

    /** P2 dumpsys 上次缓存的结果 */
    @Volatile private var lastDumpsysResult: Double? = null

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

            val gpuInfo = queryGpuInfoViaEgl()
            cachedGpuName = gpuInfo?.first ?: return emptyList()
            cachedGpuVendor = gpuInfo.second
            return listOf(cachedGpuName!!)
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
    fun getGpuUsages(isRootMode: Boolean): List<Double> {
        // ── 已缓存策略：直接执行 ──────────────────────────────────────────
        cachedStrategy?.let { strategy ->
            return when (strategy) {
                Strategy.DIRECT -> readDirect()
                Strategy.SHELL_FS -> readShellFs()
                Strategy.DUMPSYS -> readDumpsys()
                Strategy.UNAVAILABLE -> emptyList()
            }
        }

        // ── 首次调用：启动探测链 ──────────────────────────────────────────
        synchronized(this) {
            // 双重检查锁
            cachedStrategy?.let { strategy ->
                return when (strategy) {
                    Strategy.DIRECT -> readDirect()
                    Strategy.SHELL_FS -> readShellFs()
                    Strategy.DUMPSYS -> readDumpsys()
                    Strategy.UNAVAILABLE -> emptyList()
                }
            }

            return probeAndRead(isRootMode)
        }
    }

    /**
     * 获取当前策略推荐的采样间隔（毫秒）。
     *
     * - P0/P1: 2000ms（与 CPU 等指标同频）
     * - P2:    5000ms（降频，减少 dumpsys 开销）
     * - P3:    Long.MAX_VALUE（不采集）
     */
    fun getRecommendedIntervalMs(): Long = when (cachedStrategy) {
        Strategy.DIRECT, Strategy.SHELL_FS -> 2000L
        Strategy.DUMPSYS -> DUMPSYS_THROTTLE_MS
        Strategy.UNAVAILABLE, null -> Long.MAX_VALUE
    }

    /**
     * 清理所有缓存，强制下次调用时重新探测。
     *
     * 应在 AgentService.onDestroy() 中调用，确保服务重启时
     * 重新适配（例如用户在运行期间切换了 Root 模式）。
     */
    fun resetCache() {
        // 型号名称不清理（硬件不变），仅清理路径和策略缓存
        cachedSysfsPath = null
        cachedParser = null
        cachedStrategy = null
        directReadFailed = false
        probeCompleted = false
        gpuUnavailableWarned = false
        lastDumpsysTimeMs = 0L
        lastDumpsysResult = null
        Logger.i("GpuCollector: 缓存已重置")
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
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                || numConfigs[0] == 0 || configs[0] == null) {
                Logger.i("GpuCollector: EGL 配置选择失败")
                return null
            }

            // 4. 创建 1x1 pbuffer surface（离屏，无需可见窗口）
            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            surface = EGL14.eglCreatePbufferSurface(display, configs[0]!!, surfaceAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) {
                Logger.i("GpuCollector: EGL pbuffer surface 创建失败")
                return null
            }

            // 5. 创建 GLES2 上下文
            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            context = EGL14.eglCreateContext(display, configs[0]!!, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
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
    private fun probeAndRead(isRootMode: Boolean): List<Double> {
        // ── P0: Direct sysfs 直读（极速通道）──────────────────────────────
        // 仅在 Android 9 以下（SELinux untrusted_app 限制较松）时尝试
        if (!directReadFailed && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val result = probeDirectRead()
            if (result != null) {
                Logger.i("GpuCollector: P0 直读成功 (路径=${cachedSysfsPath})")
                return listOf(result)
            }
            // P0 失败，标记后永久跳过
            directReadFailed = true
        } else if (!directReadFailed) {
            // Android 9+，直接标记跳过 P0
            directReadFailed = true
        }

        // 以下策略均需要 Root/Shizuku
        if (!isRootMode) {
            cachedStrategy = Strategy.UNAVAILABLE
            if (!gpuUnavailableWarned) {
                Logger.i("GpuCollector: 普通模式下 GPU 使用率不可用（需 Root/Shizuku）")
                gpuUnavailableWarned = true
            }
            return emptyList()
        }

        // ── P1: Shell-FS 已知厂商路径 ────────────────────────────────────
        val p1Result = probeKnownPaths()
        if (p1Result != null) {
            cachedStrategy = Strategy.SHELL_FS
            Logger.i("GpuCollector: P1 已知路径命中 (路径=${cachedSysfsPath})")
            return listOf(p1Result)
        }

        // ── P1.5: 动态扫描 /sys ──────────────────────────────────────────
        val p15Result = probeDynamicScan()
        if (p15Result != null) {
            cachedStrategy = Strategy.SHELL_FS
            Logger.i("GpuCollector: P1.5 动态扫描命中 (路径=${cachedSysfsPath})")
            return listOf(p15Result)
        }

        // ── P2: dumpsys gpu 兜底 ─────────────────────────────────────────
        val p2Result = readDumpsysInternal()
        if (p2Result != null) {
            cachedStrategy = Strategy.DUMPSYS
            Logger.i("GpuCollector: P2 dumpsys 解析成功")
            return listOf(p2Result)
        }

        // ── P3: 不可用 ──────────────────────────────────────────────────
        cachedStrategy = Strategy.UNAVAILABLE
        probeCompleted = true
        if (!gpuUnavailableWarned) {
            Logger.i("GpuCollector: 当前设备不兼容 GPU 硬件级监控，GPU 使用率不可用")
            gpuUnavailableWarned = true
        }
        return emptyList()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 私有：P0 — Direct sysfs 直读
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * P0: 尝试以普通 App 权限直接读取 sysfs GPU 节点。
     *
     * 仅在 Android 9 以下有效（SELinux 对 untrusted_app 限制较松）。
     * 遍历已知路径，首个 canRead() 且解析成功的路径即为命中。
     *
     * @return 解析出的 GPU 使用率，所有路径均不可读返回 null
     */
    private fun probeDirectRead(): Double? {
        for (entry in KNOWN_SYSFS_ENTRIES) {
            try {
                val file = File(entry.path)
                if (file.exists() && file.canRead()) {
                    val raw = file.readText()
                    val value = entry.parser(raw)
                    if (value != null) {
                        cachedSysfsPath = entry.path
                        cachedParser = entry.parser
                        cachedStrategy = Strategy.DIRECT
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
     * 读取失败时自动降级（清除缓存，下次重新探测）。
     */
    private fun readDirect(): List<Double> {
        val path = cachedSysfsPath ?: return emptyList()
        val parser = cachedParser ?: return emptyList()
        return try {
            val raw = File(path).readText()
            val value = parser(raw)
            if (value != null) listOf(value.coerceIn(0.0, 100.0)) else emptyList()
        } catch (_: Exception) {
            // 权限可能在系统更新后被收紧，降级处理
            Logger.i("GpuCollector: P0 直读失败，清除缓存以便重新探测")
            cachedStrategy = null
            directReadFailed = true
            emptyList()
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
    private fun probeKnownPaths(): Double? {
        val vendor = cachedGpuVendor?.lowercase() ?: ""

        // 优先匹配 GL_VENDOR 对应的路径
        val sortedEntries = KNOWN_SYSFS_ENTRIES.sortedByDescending { entry ->
            if (vendor.contains(entry.vendorHint)) 1 else 0
        }

        for (entry in sortedEntries) {
            try {
                val raw = RootShell.executeFirstLine("cat ${entry.path} 2>/dev/null")
                if (!raw.isNullOrBlank()) {
                    val value = entry.parser(raw)
                    if (value != null) {
                        cachedSysfsPath = entry.path
                        cachedParser = entry.parser
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
    private fun readShellFs(): List<Double> {
        val path = cachedSysfsPath ?: return emptyList()
        val parser = cachedParser ?: return emptyList()
        return try {
            val raw = RootShell.executeFirstLine("cat $path 2>/dev/null")
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
    private fun probeDynamicScan(): Double? {
        if (probeCompleted) return null // 已扫描过，不重复

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
                    val raw = RootShell.executeFirstLine("cat $trimmed 2>/dev/null")
                    if (!raw.isNullOrBlank()) {
                        val value = tryGenericParse(raw, trimmed)
                        if (value != null) {
                            cachedSysfsPath = trimmed
                            // 缓存通用 parser
                            cachedParser = { r -> tryGenericParse(r, trimmed) }
                            probeCompleted = true
                            return value.coerceIn(0.0, 100.0)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("GpuCollector: P1.5 动态扫描异常", e)
        }

        probeCompleted = true
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
    private fun readDumpsys(): List<Double> {
        val now = System.currentTimeMillis()
        if (now - lastDumpsysTimeMs < DUMPSYS_THROTTLE_MS) {
            // 节流期内，返回上次缓存的值
            return lastDumpsysResult?.let { listOf(it) } ?: emptyList()
        }

        val result = readDumpsysInternal()
        lastDumpsysTimeMs = now
        lastDumpsysResult = result
        return result?.let { listOf(it) } ?: emptyList()
    }

    /**
     * P2 内部实现：执行 dumpsys gpu 并解析利用率字段。
     *
     * 尝试匹配常见格式：
     * - "GPU Utilization: 15%"
     * - "GPUTotalUtilization: 15%"
     * - "Load: 15%"
     * - "gpu_loading: 15"
     *
     * @return 解析出的 GPU 使用率，解析失败返回 null
     */
    private fun readDumpsysInternal(): Double? {
        try {
            // 尝试 dumpsys gpu
            val gpuOutput = RootShell.execute("dumpsys gpu 2>/dev/null")
            if (gpuOutput.isNotBlank()) {
                val match = DUMPSYS_UTIL_RE.find(gpuOutput)
                if (match != null) {
                    val value = match.groupValues[1].toDoubleOrNull()
                    if (value != null) return value.coerceIn(0.0, 100.0)
                }
            }
        } catch (_: Exception) {
            // dumpsys 不可用
        }

        try {
            // 备选: dumpsys SurfaceFlinger 中的 GPU 相关字段
            val sfOutput = RootShell.execute("dumpsys SurfaceFlinger --latency 2>/dev/null | head -20")
            if (sfOutput.isNotBlank()) {
                // SurfaceFlinger 的 latency 输出较间接，仅作最终尝试
                val match = DUMPSYS_UTIL_RE.find(sfOutput)
                if (match != null) {
                    val value = match.groupValues[1].toDoubleOrNull()
                    if (value != null) return value.coerceIn(0.0, 100.0)
                }
            }
        } catch (_: Exception) {
            // SurfaceFlinger 不可用
        }

        return null
    }
}
