package com.nezhahq.agent.collector

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.nezhahq.agent.util.ConfigStore
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import com.nezhahq.agent.util.VirtualizationDetector
import proto.Nezha.Host
import java.io.File

/**
 * 系统静态信息采集器（主机元数据，一般在连接建立时上报一次）。
 *
 * ## 采集内容
 * 平台信息、CPU 型号（增强版：含 SoC 品牌 + 最大频率）、CPU 核心数、
 * 内存总量、磁盘总量、Swap 总量（/proc/meminfo）、架构等。
 *
 * ## 权限策略
 * 根据 Root/Shizuku/普通 三种权限级别，选择最优的数据源：
 * - **Root/Shizuku 模式**：通过 [RootShell] 读取 `/sys` 和 `/proc` 的
 *   受限节点，获取最完整的 SoC 信息和精确核心数。
 * - **普通模式**：使用公开 API（Build.SOC_MODEL、Runtime.availableProcessors()）
 *   和可访问的 `/proc/cpuinfo`，逐级回退。
 */
object SystemInfoCollector {

    /**
     * 采集并返回宿主机静态信息。
     *
     * @param context    Android Context，用于获取系统服务和读取配置
     * @param appVersion 当前探针 APK 版本号字符串
     */
    fun getHostInfo(context: Context, appVersion: String): Host {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val isRootMode = ConfigStore.getRootMode(context)

        // ── 磁盘总量（多分区扫描 + 设备去重）──────────────────────────────
        val diskTotal = DiskCollector.getDiskInfo(isRootMode).totalBytes

        // ── CPU 详细名称 + 核心数 ──────────────────────────────────────────────
        val cpuName  = readCpuName(isRootMode)
        val cpuCores = readCpuCoreCount(isRootMode)

        // ── Swap 总量（从 /proc/meminfo 读取 SwapTotal 字段）─────────────────
        val swapTotal = readSwapTotalBytes()

        // ── 虚拟化环境检测 ──────────────────────────────────────────────────
        // 原版 Go Agent 通过 host.Info().VirtualizationRole 判断 Physical/Virtual
        // Android 端通过五层策略自行检测（cpuinfo/Build指纹/特征文件/传感器/系统属性）
        val virtualizationSystem = VirtualizationDetector.detect(context, isRootMode)
        val cpuCoreType = if (virtualizationSystem.isNotEmpty()) "Virtual" else "Physical"

        // 构建 CPU 列表：采用原版哪吒探针格式
        // Go Agent 格式："{CPU名称} {核心数} {Physical/Virtual} Core"
        // 只返回一个元素的列表（去重汇总），避免面板上重复显示 CPU 名称
        val coreCount = if (cpuCores > 0) cpuCores else 1
        val cpuDisplayName = "$cpuName $coreCount $cpuCoreType Core"
        val cpuList = listOf(cpuDisplayName)

        return Host.newBuilder()
            .setPlatform("Android")
            .setPlatformVersion(Build.VERSION.RELEASE)
            .addAllCpu(cpuList)
            .addAllGpu(GpuCollector.getGpuNames())
            .setMemTotal(memInfo.totalMem)
            .setDiskTotal(diskTotal)
            .setSwapTotal(swapTotal)
            .setArch(System.getProperty("os.arch") ?: Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            .setVirtualization(virtualizationSystem.ifEmpty { "" })
            // bootTime 为 Unix 时间戳（秒），等于"当前时间 - 已开机时长"
            .setBootTime((System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 1000)
            .setVersion(appVersion)
            .build()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CPU 名称采集（三级策略）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 读取 CPU（SoC）详细名称。
     *
     * ## 策略分层（按信息丰富度从高到低）
     *
     * ### Root/Shizuku 模式
     * 1. 读取 `/proc/cpuinfo` 的 `Hardware` 字段（完整 SoC 名称）
     * 2. 如果 Hardware 信息较短，尝试通过 `/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq`
     *    追加最大频率信息，形如 "Qualcomm SM8350 @ 2.84 GHz"
     * 3. 回退到 `model name` 字段（x86 模拟器环境）
     *
     * ### 普通模式
     * 1. 尝试 `Build.SOC_MODEL`（API 31+，返回 SoC 型号如 "SM8350"）
     * 2. 读取 `/proc/cpuinfo` 的 `Hardware` 或 `model name`
     * 3. 回退到 `Build.SOC_MANUFACTURER + Build.SOC_MODEL`（API 31+）
     * 4. 最终回退到 `Build.HARDWARE`
     *
     * @param isRootMode 是否处于 Root/Shizuku 提权模式
     * @return CPU 名称字符串，保证非空
     */
    private fun readCpuName(isRootMode: Boolean): String {
        return if (isRootMode) {
            readCpuNameRoot()
        } else {
            readCpuNameNormal()
        }
    }

    /**
     * Root/Shizuku 模式下的 CPU 名称采集。
     *
     * 可以访问 /sys 下的 cpufreq 节点获取最大频率等信息，
     * 用于拼接更详细的 CPU 名称（如 "Qualcomm Technologies, Inc SM8350 @ 2.84 GHz"）。
     */
    private fun readCpuNameRoot(): String {
        try {
            // 策略 1：通过 RootShell 读取 /proc/cpuinfo
            val cpuInfo = RootShell.execute("cat /proc/cpuinfo")
            if (cpuInfo.isNotBlank()) {
                val baseName = extractCpuNameFromCpuInfo(cpuInfo)
                if (baseName != null) {
                    // 尝试通过 /sys 节点追加最大频率信息，使名称更详细
                    val enhancedName = enhanceCpuNameWithFrequency(baseName)
                    return enhancedName
                }
            }
        } catch (e: Exception) {
            Logger.e("SystemInfoCollector: Root 模式读取 /proc/cpuinfo 失败，回退到普通模式", e)
        }

        // Root Shell 异常时回退到普通模式策略
        return readCpuNameNormal()
    }

    /**
     * 普通模式下的 CPU 名称采集。
     *
     * 逐级回退，保证在任何环境下都能返回有意义的名称。
     */
    private fun readCpuNameNormal(): String {
        // 策略 1（API 31+）：使用 Build.SOC_MANUFACTURER + Build.SOC_MODEL
        // 这是 Android 12 新增的 API，能返回厂商认定的 SoC 标准名称
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val manufacturer = Build.SOC_MANUFACTURER  // 如 "Qualcomm"
                val model = Build.SOC_MODEL                // 如 "SM8350"
                if (manufacturer.isNotBlank() && manufacturer != "unknown"
                    && model.isNotBlank() && model != "unknown") {
                    val socName = "$manufacturer $model"
                    // 尝试追加 /proc/cpuinfo 中更详细的 Hardware 信息
                    val enhanced = tryEnhanceWithCpuInfoDirect(socName)
                    // 尝试追加频率信息
                    return enhanceCpuNameWithFrequencyDirect(enhanced)
                }
            } catch (e: Exception) {
                Logger.i("SystemInfoCollector: Build.SOC_MODEL 不可用: ${e.message}")
            }
        }

        // 策略 2：直接读取 /proc/cpuinfo（普通权限通常可读）
        try {
            val cpuInfo = File("/proc/cpuinfo").readText()
            val baseName = extractCpuNameFromCpuInfo(cpuInfo)
            if (baseName != null) {
                return enhanceCpuNameWithFrequencyDirect(baseName)
            }
        } catch (e: Exception) {
            Logger.i("SystemInfoCollector: 普通模式读取 /proc/cpuinfo 失败: ${e.message}")
        }

        // 策略 3（API 31+）：回退到 Build.SOC_MODEL 单独使用
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val model = Build.SOC_MODEL
                if (model.isNotBlank() && model != "unknown") {
                    return model
                }
            } catch (_: Exception) { /* 忽略，继续回退 */ }
        }

        // 最终回退：Build.HARDWARE（如 "qcom"、"exynos"）
        return Build.HARDWARE.ifBlank { "unknown" }
    }

    /**
     * 从 /proc/cpuinfo 文本中提取 CPU 名称。
     *
     * 按优先级尝试：
     * 1. `Hardware` 字段（ARM SoC 专用，如 "Qualcomm Technologies, Inc KONA"）
     * 2. `model name` 字段（x86/模拟器，如 "Intel Core i7-10700"）
     *
     * @param cpuInfo /proc/cpuinfo 的完整文本
     * @return 提取到的 CPU 名称，未找到返回 null
     */
    private fun extractCpuNameFromCpuInfo(cpuInfo: String): String? {
        // 优先 Hardware 字段
        val hardwareMatch = Regex("Hardware\\s*:\\s*(.+)").find(cpuInfo)
        if (hardwareMatch != null) {
            return hardwareMatch.groupValues[1].trim()
        }
        // 回退到 model name 字段
        val modelMatch = Regex("model name\\s*:\\s*(.+)").find(cpuInfo)
        if (modelMatch != null) {
            return modelMatch.groupValues[1].trim()
        }
        return null
    }

    /**
     * 通过 RootShell 读取 CPU 最大频率，增强 CPU 名称。
     *
     * 从 `/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq` 读取最大频率（kHz），
     * 转换为 GHz 后追加到名称末尾。
     *
     * @param baseName 基础 CPU 名称
     * @return 增强后的名称（如 "Qualcomm SM8350 @ 2.84 GHz"），失败时返回原名称
     */
    private fun enhanceCpuNameWithFrequency(baseName: String): String {
        return try {
            val freqKhzStr = RootShell.executeFirstLine(
                "cat /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq 2>/dev/null"
            )
            val freqKhz = freqKhzStr?.trim()?.toLongOrNull()
            if (freqKhz != null && freqKhz > 0) {
                val freqGhz = freqKhz / 1_000_000.0
                "$baseName @ ${"%.2f".format(freqGhz)} GHz"
            } else {
                baseName
            }
        } catch (e: Exception) {
            Logger.i("SystemInfoCollector: Root 模式读取 CPU 频率失败: ${e.message}")
            baseName
        }
    }

    /**
     * 普通模式下直接读取 CPU 最大频率文件（无需 Root 权限，部分设备可读）。
     *
     * @param baseName 基础 CPU 名称
     * @return 增强后的名称，失败时返回原名称
     */
    private fun enhanceCpuNameWithFrequencyDirect(baseName: String): String {
        return try {
            val freqFile = File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")
            if (freqFile.exists() && freqFile.canRead()) {
                val freqKhz = freqFile.readText().trim().toLongOrNull()
                if (freqKhz != null && freqKhz > 0) {
                    val freqGhz = freqKhz / 1_000_000.0
                    "$baseName @ ${"%.2f".format(freqGhz)} GHz"
                } else {
                    baseName
                }
            } else {
                baseName
            }
        } catch (e: Exception) {
            // 部分设备的 /sys 节点可能受 SELinux 限制
            baseName
        }
    }

    /**
     * 尝试用 /proc/cpuinfo 中更详细的 Hardware 信息来增强 SoC 名称。
     *
     * 例如 Build.SOC_MODEL 返回 "SM8350"，但 /proc/cpuinfo Hardware
     * 可能返回 "Qualcomm Technologies, Inc SM8350"，信息更丰富。
     *
     * @param socName 从 Build API 获取的 SoC 名称
     * @return 增强后的名称，/proc/cpuinfo 不可读时返回原名称
     */
    private fun tryEnhanceWithCpuInfoDirect(socName: String): String {
        return try {
            val cpuInfo = File("/proc/cpuinfo").readText()
            val hardwareName = extractCpuNameFromCpuInfo(cpuInfo)
            // 只在 cpuinfo 名称更长（信息更丰富）时才替换
            if (hardwareName != null && hardwareName.length > socName.length) {
                hardwareName
            } else {
                socName
            }
        } catch (e: Exception) {
            socName
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CPU 核心数采集（三级策略）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 读取 CPU 逻辑核心数量。
     *
     * ## 策略分层
     *
     * ### Root/Shizuku 模式
     * 1. 读取 `/sys/devices/system/cpu/online`（格式如 "0-7" → 8 核）
     * 2. 回退到 `nproc` 命令
     * 3. 最终回退到普通模式策略
     *
     * ### 普通模式
     * 1. 尝试直接读取 `/sys/devices/system/cpu/online`（部分设备允许）
     * 2. `Runtime.getRuntime().availableProcessors()`
     *    注意：此 API 返回的是"当前可用核心数"，在部分设备上可能因
     *    大核休眠而返回小于实际物理核心数的值，但作为兜底方案足够可靠
     * 3. 枚举 `/sys/devices/system/cpu/cpu[0-9]*` 目录数量
     *
     * @param isRootMode 是否处于 Root/Shizuku 提权模式
     * @return CPU 核心数，保证 >= 1
     */
    private fun readCpuCoreCount(isRootMode: Boolean): Int {
        return if (isRootMode) {
            readCpuCoreCountRoot()
        } else {
            readCpuCoreCountNormal()
        }
    }

    /**
     * Root/Shizuku 模式下的 CPU 核心数采集。
     */
    private fun readCpuCoreCountRoot(): Int {
        // 策略 1：通过 RootShell 读取 /sys/devices/system/cpu/online
        try {
            val online = RootShell.executeFirstLine("cat /sys/devices/system/cpu/online 2>/dev/null")
            val count = parseCpuOnlineRange(online)
            if (count > 0) return count
        } catch (e: Exception) {
            Logger.i("SystemInfoCollector: Root 模式读取 cpu/online 失败: ${e.message}")
        }

        // 策略 2：通过 nproc 命令
        try {
            val nprocOutput = RootShell.executeFirstLine("nproc 2>/dev/null")
            val count = nprocOutput?.trim()?.toIntOrNull()
            if (count != null && count > 0) return count
        } catch (e: Exception) {
            Logger.i("SystemInfoCollector: Root 模式 nproc 失败: ${e.message}")
        }

        // 回退到普通模式策略
        return readCpuCoreCountNormal()
    }

    /**
     * 普通模式下的 CPU 核心数采集。
     */
    private fun readCpuCoreCountNormal(): Int {
        // 策略 1：直接读取 /sys/devices/system/cpu/online（部分设备普通权限可读）
        try {
            val onlineFile = File("/sys/devices/system/cpu/online")
            if (onlineFile.exists() && onlineFile.canRead()) {
                val content = onlineFile.readText().trim()
                val count = parseCpuOnlineRange(content)
                if (count > 0) return count
            }
        } catch (e: Exception) {
            // SELinux 可能拒绝，继续回退
        }

        // 策略 2：Runtime.availableProcessors()
        val runtimeCores = Runtime.getRuntime().availableProcessors()
        if (runtimeCores > 0) return runtimeCores

        // 策略 3：枚举 /sys/devices/system/cpu/ 目录下的 cpu* 子目录
        try {
            val cpuDir = File("/sys/devices/system/cpu/")
            if (cpuDir.exists() && cpuDir.canRead()) {
                val count = cpuDir.listFiles { file ->
                    file.isDirectory && file.name.matches(Regex("cpu\\d+"))
                }?.size ?: 0
                if (count > 0) return count
            }
        } catch (e: Exception) {
            Logger.i("SystemInfoCollector: 枚举 cpu 目录失败: ${e.message}")
        }

        // 最终兜底：至少 1 核
        return 1
    }

    /**
     * 解析 `/sys/devices/system/cpu/online` 的范围格式。
     *
     * 支持的格式：
     * - "0-7"      → 8 核
     * - "0-3,4-7"  → 8 核
     * - "0,1,2,3"  → 4 核
     * - "0-3,6-7"  → 6 核
     *
     * @param rangeStr 范围字符串，null 返回 0
     * @return 核心总数，格式不合法返回 0
     */
    private fun parseCpuOnlineRange(rangeStr: String?): Int {
        if (rangeStr.isNullOrBlank()) return 0
        var total = 0
        try {
            // 按逗号分隔各段
            for (segment in rangeStr.trim().split(",")) {
                val trimmed = segment.trim()
                if (trimmed.contains("-")) {
                    // 范围格式：如 "0-7"
                    val parts = trimmed.split("-")
                    if (parts.size == 2) {
                        val start = parts[0].trim().toIntOrNull() ?: continue
                        val end   = parts[1].trim().toIntOrNull() ?: continue
                        total += (end - start + 1)
                    }
                } else {
                    // 单核格式：如 "0"
                    if (trimmed.toIntOrNull() != null) {
                        total += 1
                    }
                }
            }
        } catch (e: Exception) {
            Logger.i("SystemInfoCollector: 解析 cpu/online 格式异常: $rangeStr")
            return 0
        }
        return total
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Swap 总量
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 从 /proc/meminfo 读取系统 Swap 总量（字节）。
     *
     * 现代 Android 设备（Android 8+）普遍通过 ZRAM 提供 Swap 空间，
     * /proc/meminfo 的 "SwapTotal:" 字段即为 ZRAM 虚拟 Swap 总量。
     * 单位为 kB，此处转换为 Bytes 后返回。
     */
    private fun readSwapTotalBytes(): Long {
        return try {
            var swapTotalKb = 0L
            File("/proc/meminfo").forEachLine { line ->
                if (line.startsWith("SwapTotal:")) {
                    // 格式："SwapTotal:   2097148 kB"
                    swapTotalKb = Regex("\\d+").find(line)?.value?.toLongOrNull() ?: 0L
                    return@forEachLine // 找到后提前退出循环
                }
            }
            swapTotalKb * 1024L
        } catch (e: Exception) {
            Logger.e("SystemInfoCollector: 读取 /proc/meminfo SwapTotal 失败", e)
            0L
        }
    }
}
