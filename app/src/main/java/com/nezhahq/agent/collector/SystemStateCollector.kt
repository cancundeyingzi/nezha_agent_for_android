package com.nezhahq.agent.collector

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.nezhahq.agent.util.ConfigStore
import com.nezhahq.agent.util.Logger
import proto.Nezha.State
import proto.Nezha.State_SensorTemperature
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * 系统运行时状态采集器（动态数据）。
 *
 * 负责每次上报时采集：CPU 占用率、内存使用量、Swap 使用量、磁盘使用量、
 * 网络速度/流量、TCP/UDP 连接数、进程数、温度传感器。
 *
 * 设计原则：
 *  - 普通模式：优先尝试直接读取 /proc/stat（Android 8 及以下），
 *    若遭 SELinux 拒绝（Android 9+），自动回退到 `top` 命令并除以核心数归一化。
 *  - Root/Shizuku 模式：`su -c cat /proc/stat` 绕过 SELinux，使用精确差值法。
 *  - CPU 使用率保证在任意核心数、任意 Android 版本下均为 0~100%。
 */
class SystemStateCollector(private val context: Context) {

    // ──────────────────────────────────────────────────────────────────────────
    // 网络速度计算：记录上一次的收发字节数和时间戳
    // ──────────────────────────────────────────────────────────────────────────
    private var lastRxBytes = TrafficStats.getTotalRxBytes()
    private var lastTxBytes = TrafficStats.getTotalTxBytes()
    private var lastTimeMs = SystemClock.elapsedRealtime()

    // ──────────────────────────────────────────────────────────────────────────
    // CPU 差值采样：记录上一次读取 /proc/stat 的 CPU 时间片数据
    // ──────────────────────────────────────────────────────────────────────────
    private var lastCpuTotal = 0L
    private var lastCpuIdle  = 0L

    // ──────────────────────────────────────────────────────────────────────────
    // 公开采集入口
    // ──────────────────────────────────────────────────────────────────────────

    fun getState(): State {

        // ── 1. RAM ─────────────────────────────────────────────────────────────
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val memUsed = memInfo.totalMem - memInfo.availMem

        // ── 2. Swap (读取 /proc/meminfo，普通权限即可) ─────────────────────────
        val swapUsed = readSwapUsedBytes()

        // ── 3. Disk ────────────────────────────────────────────────────────────
        val statFs   = StatFs(Environment.getDataDirectory().path)
        val diskTotal = statFs.blockCountLong * statFs.blockSizeLong
        val diskFree  = statFs.availableBlocksLong * statFs.blockSizeLong
        val diskUsed  = diskTotal - diskFree

        // ── 4. 网络速度 ────────────────────────────────────────────────────────
        val currentRx   = TrafficStats.getTotalRxBytes()
        val currentTx   = TrafficStats.getTotalTxBytes()
        val currentTime = SystemClock.elapsedRealtime()
        val timeDiff    = currentTime - lastTimeMs

        var rxSpeed = 0L
        var txSpeed = 0L
        if (timeDiff > 0) {
            rxSpeed = (currentRx - lastRxBytes) * 1000 / timeDiff
            txSpeed = (currentTx - lastTxBytes) * 1000 / timeDiff
        }
        lastRxBytes = currentRx
        lastTxBytes = currentTx
        lastTimeMs  = currentTime

        // ── 5. 温度（使用电池温度作为系统回退值）────────────────────────────────
        val batteryIntent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val tempCelsius = batteryIntent
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            ?.toDouble()?.div(10) ?: 0.0

        val sensorTemp = State_SensorTemperature.newBuilder()
            .setName("Battery")
            .setTemperature(tempCelsius)
            .build()

        // ── 6. CPU 使用率（多层兜底：Root→su+/proc/stat 差值；普通→/proc/stat 或 top 归一化）
        val isRootMode  = ConfigStore.getRootMode(context)
        val cpuUsage = readCpuUsagePercent(isRootMode)

        // ── 7. 进程数 & 连接数（区分 root / 普通模式）───────────────────────────
        var processCount = 0L
        var tcpConnCount = 0L
        var udpConnCount = 0L

        try {
            // 7-a. 进程数
            processCount = readProcessCount(isRootMode)

            // 7-b. TCP/UDP 连接数
            val (tcp, udp) = readConnectionCounts(isRootMode)
            tcpConnCount = tcp
            udpConnCount = udp

        } catch (e: Exception) {
            Logger.e("StateCollector: 采集进程/连接数时异常", e)
        }

        return State.newBuilder()
            .setCpu(cpuUsage)
            .setMemUsed(memUsed)
            .setSwapUsed(swapUsed)
            .setDiskUsed(diskUsed)
            .setNetInTransfer(currentRx)
            .setNetOutTransfer(currentTx)
            .setNetInSpeed(rxSpeed)
            .setNetOutSpeed(txSpeed)
            .setUptime(SystemClock.elapsedRealtime() / 1000)
            .setLoad1(0.0)
            .setLoad5(0.0)
            .setLoad15(0.0)
            .setTcpConnCount(tcpConnCount)
            .setUdpConnCount(udpConnCount)
            .setProcessCount(processCount)
            .addTemperatures(sensorTemp)
            .build()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 私有实现：CPU 使用率（三层兜底策略）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 获取 CPU 综合使用率（0.0 ~ 100.0）。
     *
     * 采用三层兜底策略，保证在任意 Android 版本和权限级别下均能返回有效数据：
     *
     * 层级 1 (Root 模式)：
     *   `su -c "cat /proc/stat"` → 绕过 Android 9+ SELinux 限制 → 差值法精确计算
     *
     * 层级 2 (普通模式，Android ≤ 8)：
     *   直接读取 `/proc/stat` → 差值法精确计算（Android 9 前无限制）
     *
     * 层级 3 (普通模式，Android 9+ SELinux 拒绝时自动触发)：
     *   执行 `top -n 1 -d 1` → 解析首个 CPU 汇总行 → 除以核心数归一化到 0-100%
     *
     * @param isRootMode 是否处于 Root/Shizuku 提权模式
     * @return CPU 使用率百分比，范围 [0.0, 100.0]
     */
    private fun readCpuUsagePercent(isRootMode: Boolean): Double {
        // ── 层级 1：Root 模式使用 su 读取 /proc/stat，绕过 SELinux ──────────────
        if (isRootMode) {
            return try {
                val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat /proc/stat"))
                val firstLine = BufferedReader(InputStreamReader(proc.inputStream))
                    .readLine()
                proc.waitFor()
                parseProcStatLine(firstLine)
            } catch (e: Exception) {
                Logger.e("StateCollector: Root 模式读取 /proc/stat 失败，回退到 top", e)
                // Root 也可能失败（su 不可用），回退到 top 方案
                readCpuFromTop()
            }
        }

        // ── 层级 2：普通模式，直接读取 /proc/stat（Android 8 及以下可行）──────────
        return try {
            val firstLine = File("/proc/stat").bufferedReader().use { it.readLine() }
            parseProcStatLine(firstLine)
        } catch (e: Exception) {
            // Android 9+ SELinux 策略收紧，/proc/stat 对普通应用不可访问（EACCES）
            // 不记录 ERROR 日志（此情况是预知的 SELinux 行为），静默降级到 top 方案
            Logger.i("StateCollector: /proc/stat 受 SELinux 限制，降级到 top 命令采集 CPU")
            readCpuFromTop()
        }
    }

    /**
     * 解析 `/proc/stat` 第一行（"cpu" 综合行），通过差值法计算 CPU 使用率。
     *
     * /proc/stat 行格式：
     *   `cpu  <user> <nice> <system> <idle> <iowait> <irq> <softirq> ...`
     *
     * 差值法：对比本次与上次采样的时间片数，计算这段时间内的实际 CPU 使用比例。
     * 天然结果为 0-100%（不受核心数影响），因为第一行是所有核心的累加总计。
     *
     * @param line /proc/stat 的第一行文本，为 null 时返回 0.0
     * @return [0.0, 100.0] 范围内的 CPU 使用率
     */
    private fun parseProcStatLine(line: String?): Double {
        if (line == null) return 0.0
        val parts = line.trim().split("\\s+".toRegex())
        if (parts.size < 5 || parts[0] != "cpu") return 0.0

        val user    = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val nice    = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        val system  = parts.getOrNull(3)?.toLongOrNull() ?: 0L
        val idle    = parts.getOrNull(4)?.toLongOrNull() ?: 0L
        val iowait  = parts.getOrNull(5)?.toLongOrNull() ?: 0L
        val irq     = parts.getOrNull(6)?.toLongOrNull() ?: 0L
        val softirq = parts.getOrNull(7)?.toLongOrNull() ?: 0L

        val total   = user + nice + system + idle + iowait + irq + softirq
        val idleAll = idle + iowait  // iowait 期间 CPU 本质上是空闲的

        val deltaTotal = total - lastCpuTotal
        val deltaIdle  = idleAll - lastCpuIdle

        // 更新基准值，供下次差值计算使用
        lastCpuTotal = total
        lastCpuIdle  = idleAll

        // 首次调用时无历史数据，deltaTotal == 0，返回 0.0
        if (deltaTotal <= 0L) return 0.0

        return ((deltaTotal - deltaIdle).toDouble() / deltaTotal.toDouble() * 100.0)
            .coerceIn(0.0, 100.0)
    }

    /**
     * 通过执行 `top -n 1 -d 1` 命令获取 CPU 使用率（兜底方案）。
     *
     * 适用场景：Android 9+ 普通应用因 SELinux 策略被拒绝直接读取 /proc/stat 时。
     * `top` 命令本身以更高权限运行，可以访问 /proc/stat。
     *
     * 解析逻辑：
     *   - 找到含有 "%cpu" 或 "user" 关键字的 CPU 汇总行
     *   - 提取 user% 和 system%，两者之和为活跃 CPU 使用率
     *   - 除以 CPU 核心数，将多核总和归一化到单核等效的 0-100%
     *
     * @return [0.0, 100.0] 范围内的 CPU 使用率，失败时返回 0.0
     */
    private fun readCpuFromTop(): Double {
        return try {
            // 获取设备物理 CPU 核心数，用于归一化多核总和
            val coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

            val proc = Runtime.getRuntime().exec(arrayOf("top", "-n", "1", "-d", "1"))
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var cpuUsage = 0.0

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                // 匹配 top 输出的 CPU 汇总行，不同 Android 版本格式略有差异：
                //   示例 A: "%cpu  12.5 user   3.2 sys  ..."  (Android toolbox top)
                //   示例 B: "Tasks: 312 total; ..."
                //   示例 C: "CPU(total): 15.2% user + 4.3% sys ..." (部分 OEM)
                if (l.contains("%cpu", ignoreCase = true) ||
                    (l.contains("user", ignoreCase = true) && l.contains("%"))
                ) {
                    // 提取第一个 user 值（多核总和）
                    val userMatch = Regex("(\\d+(?:\\.\\d+)?)%?\\s*user", RegexOption.IGNORE_CASE)
                        .find(l)
                    val sysMatch  = Regex("(\\d+(?:\\.\\d+)?)%?\\s*sys", RegexOption.IGNORE_CASE)
                        .find(l)

                    val userVal = userMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                    val sysVal  = sysMatch?.groupValues?.get(1)?.toDoubleOrNull()  ?: 0.0

                    if (userVal > 0.0 || sysVal > 0.0) {
                        // top 在 Irix 模式下显示的是所有核心总和，需除以核心数归一化
                        cpuUsage = ((userVal + sysVal) / coreCount).coerceIn(0.0, 100.0)
                        break // 找到第一个有效 CPU 行即可退出
                    }
                }
            }
            proc.waitFor()
            cpuUsage
        } catch (e: Exception) {
            Logger.e("StateCollector: top 命令读取 CPU 失败", e)
            0.0
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 私有实现：Swap 使用量（读取 /proc/meminfo，所有应用均可访问）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 从 /proc/meminfo 解析 Swap 使用量（字节）。
     *
     * Android 现代设备普遍使用 ZRAM 作为 Swap，/proc/meminfo 包含完整信息。
     * 此接口无需 root 权限。
     */
    private fun readSwapUsedBytes(): Long {
        var swapTotal = 0L
        var swapFree  = 0L
        try {
            File("/proc/meminfo").forEachLine { line ->
                when {
                    line.startsWith("SwapTotal:") -> {
                        // 格式："SwapTotal:   2097148 kB"
                        swapTotal = Regex("\\d+").find(line)?.value?.toLongOrNull() ?: 0L
                    }
                    line.startsWith("SwapFree:") -> {
                        swapFree = Regex("\\d+").find(line)?.value?.toLongOrNull() ?: 0L
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("StateCollector: 读取 /proc/meminfo Swap 失败", e)
        }
        // /proc/meminfo 单位为 kB，转换为 Bytes
        return (swapTotal - swapFree).coerceAtLeast(0L) * 1024L
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 私有实现：进程数采集
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 获取当前进程总数。
     *
     * - Root 模式：使用 `su -c "ps -A | wc -l"` 获取全量进程表行数（减去标题行 1）。
     * - 普通模式：统计 /proc 下所有纯数字目录（每个进程在此有一个目录）。
     *
     * @param isRootMode 是否启用了 Root/Shizuku 提权模式
     */
    private fun readProcessCount(isRootMode: Boolean): Long {
        return if (isRootMode) {
            // 使用 su 获取全量进程表
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "ps -A | wc -l"))
                val count = BufferedReader(InputStreamReader(p.inputStream))
                    .readLine()?.trim()?.toLongOrNull() ?: 0L
                p.waitFor()
                // wc -l 包含标题行，减 1 得到进程数
                (count - 1).coerceAtLeast(0L)
            } catch (e: Exception) {
                Logger.e("StateCollector: Root 模式读取进程数失败，回退到 /proc 目录法", e)
                readProcessCountFromProc()
            }
        } else {
            // 普通模式：枚举 /proc 目录下的数字子目录即为进程列表
            readProcessCountFromProc()
        }
    }

    /**
     * 通过遍历 /proc 目录下的纯数字子目录统计进程数。
     * 每个进程在 /proc 下都有一个以其 PID 命名的目录，此方法无需任何权限。
     */
    private fun readProcessCountFromProc(): Long {
        return try {
            File("/proc").listFiles { file ->
                file.isDirectory && file.name.all { it.isDigit() }
            }?.size?.toLong() ?: 0L
        } catch (e: Exception) {
            Logger.e("StateCollector: 遍历 /proc 目录统计进程数失败", e)
            0L
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 私有实现：TCP/UDP 连接数采集
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 获取 TCP 和 UDP 连接数，返回 Pair(tcpCount, udpCount)。
     *
     * Root 模式：通过 `su -c "ss -tn"` / `ss -un` 获取全量连接。
     *   ss 输出中每合法连接行即为一条连接记录（排除标题行）。
     *
     * 普通模式：分别读取 /proc/net/tcp、/proc/net/tcp6（TCP）
     *   和 /proc/net/udp、/proc/net/udp6（UDP），各文件首行均为列标题，跳过之。
     *   注意：非 root 下只能看到本应用的连接，无法获取系统全量连接，但至少数据正确。
     *
     * @param isRootMode 是否启用了 Root/Shizuku 提权模式
     */
    private fun readConnectionCounts(isRootMode: Boolean): Pair<Long, Long> {
        return if (isRootMode) {
            readConnectionCountsRoot()
        } else {
            readConnectionCountsFromProc()
        }
    }

    /**
     * Root 模式：通过 `ss` 命令读取全量连接数。
     */
    private fun readConnectionCountsRoot(): Pair<Long, Long> {
        var tcpCount = 0L
        var udpCount = 0L
        try {
            // 使用 -t 仅统计 TCP，-u 仅统计 UDP；-n 不解析主机名（更快）
            // -H 跳过标题行（部分 Android 版本的 ss 不支持 -H，用 grep -c 代替）
            val tcpProcess = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "ss -tn 2>/dev/null | tail -n +2 | wc -l")
            )
            tcpCount = BufferedReader(InputStreamReader(tcpProcess.inputStream))
                .readLine()?.trim()?.toLongOrNull() ?: 0L
            tcpProcess.waitFor()

            val udpProcess = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "ss -un 2>/dev/null | tail -n +2 | wc -l")
            )
            udpCount = BufferedReader(InputStreamReader(udpProcess.inputStream))
                .readLine()?.trim()?.toLongOrNull() ?: 0L
            udpProcess.waitFor()

        } catch (e: Exception) {
            Logger.e("StateCollector: Root 模式读取连接数(ss)失败，回退到 /proc/net", e)
            // 回退到 /proc/net 解析
            return readConnectionCountsFromProc()
        }
        return Pair(tcpCount, udpCount)
    }

    /**
     * 普通模式：分别读取 /proc/net/tcp(6) 和 /proc/net/udp(6) 文件统计行数。
     *
     * 每个文件的第一行是列标题，需跳过。
     * 后续每行代表一个 socket 条目，即一个连接（或监听端口）。
     */
    private fun readConnectionCountsFromProc(): Pair<Long, Long> {
        // TCP 相关文件
        val tcpFiles = listOf(
            File("/proc/net/tcp"),
            File("/proc/net/tcp6")
        )
        // UDP 相关文件
        val udpFiles = listOf(
            File("/proc/net/udp"),
            File("/proc/net/udp6")
        )

        var tcpCount = 0L
        var udpCount = 0L

        fun countLines(files: List<File>): Long {
            var count = 0L
            for (file in files) {
                if (!file.exists() || !file.canRead()) continue
                try {
                    file.bufferedReader().use { reader ->
                        var firstLine = true
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (firstLine) {
                                firstLine = false // 跳过标题行
                                continue
                            }
                            if (line!!.isNotBlank()) count++
                        }
                    }
                } catch (e: Exception) {
                    Logger.e("StateCollector: 读取 ${file.path} 失败", e)
                }
            }
            return count
        }

        tcpCount = countLines(tcpFiles)
        udpCount = countLines(udpFiles)

        return Pair(tcpCount, udpCount)
    }
}
