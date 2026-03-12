package com.nezhahq.agent.collector

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.nezhahq.agent.util.Logger
import proto.Nezha.Host
import java.io.File

/**
 * 系统静态信息采集器（主机元数据，一般在连接建立时上报一次）。
 *
 * 采集内容：平台信息、CPU 型号（/proc/cpuinfo）、内存总量、
 * 磁盘总量、Swap 总量（/proc/meminfo）、架构等。
 *
 * 此采集器所有操作均依赖公开 API 或可读的 /proc 文件系统，
 * 无需任何特殊权限，普通应用即可使用。
 */
object SystemInfoCollector {

    /**
     * 采集并返回宿主机静态信息。
     *
     * @param context    Android Context，用于获取系统服务
     * @param appVersion 当前探针 APK 版本号字符串
     */
    fun getHostInfo(context: Context, appVersion: String): Host {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        // ── 磁盘总量（Data 分区）──────────────────────────────────────────────
        val statFs    = StatFs(Environment.getDataDirectory().path)
        val diskTotal = statFs.blockCountLong * statFs.blockSizeLong

        // ── CPU 型号（从 /proc/cpuinfo 读取 Hardware 字段）───────────────────
        val cpuName = readCpuName()

        // ── Swap 总量（从 /proc/meminfo 读取 SwapTotal 字段）─────────────────
        val swapTotal = readSwapTotalBytes()

        return Host.newBuilder()
            .setPlatform("Android")
            .setPlatformVersion(Build.VERSION.RELEASE)
            .addAllCpu(listOf(cpuName))
            .setMemTotal(memInfo.totalMem)
            .setDiskTotal(diskTotal)
            .setSwapTotal(swapTotal)
            .setArch(System.getProperty("os.arch") ?: Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            .setVirtualization("none")
            // bootTime 为 Unix 时间戳（秒），等于"当前时间 - 已开机时长"
            .setBootTime((System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 1000)
            .setVersion(appVersion)
            .build()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 私有辅助方法
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 从 /proc/cpuinfo 读取 SoC 硬件型号名称。
     *
     * 优先读取 "Hardware" 字段（如 "Snapdragon 888"）；
     * 若不存在，则回退到 Build.HARDWARE（如 "qcom"）。
     */
    private fun readCpuName(): String {
        return try {
            val cpuInfo = File("/proc/cpuinfo").readText()
            // 匹配 "Hardware\t: Qualcomm Technologies, Inc KONA" 格式
            val hardwareMatch = Regex("Hardware\\s*:\\s*(.+)").find(cpuInfo)
            if (hardwareMatch != null) {
                hardwareMatch.groupValues[1].trim()
            } else {
                // 部分内核的 /proc/cpuinfo 不含 Hardware 行
                // 尝试读取 "model name"（x86 模拟器）或回退 Build.HARDWARE
                val modelMatch = Regex("model name\\s*:\\s*(.+)").find(cpuInfo)
                modelMatch?.groupValues?.get(1)?.trim() ?: Build.HARDWARE
            }
        } catch (e: Exception) {
            Logger.e("SystemInfoCollector: 读取 /proc/cpuinfo 失败", e)
            Build.HARDWARE
        }
    }

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
