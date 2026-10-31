package com.nezhahq.agent.collector

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.os.SystemClock
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import proto.Nezha.State
import java.io.File
import java.net.NetworkInterface

/**
 * 系统运行时状态采集器（动态数据，每次上报前调用一次）。
 *
 * ## 采集内容
 * CPU 占用率、内存使用量、Swap（ZRAM）使用量、磁盘使用量、
 * 网络速度/累计流量、TCP/UDP 连接数、进程数、电池温度。
 *
 * ## 模式说明
 *
 * ### 普通模式（isRootMode = false）
 *  - CPU：优先读取 /proc/stat（差值法，精确 0-100%）。
 *    若遭 Android 9+ SELinux 拒绝（EACCES），**直接返回 0.0** 而非使用 top。
 *    原因：top 命令第一帧输出的是系统启动以来的平均值，非当前瞬时值；
 *    且各家 OEM ROM 格式差异极大，解析准确率堪忧，不如诚实返回 0。
 *  - 连接数：通过字节级换行符计数读取 /proc/net/tcp(6) / /proc/net/udp(6)，
 *    性能远优于按行 readLine() + String 对象创建。
 *  - 进程数：枚举 /proc 下的数字子目录（每 PID 一个）。
 *
 * ### Root/Shizuku 模式（isRootMode = true）
 * CPU、网络、负载、连接和进程数据通过一次带分段标记的 [RootShell] 请求采集，
 * 避免多个 Binder 往返产生部分成功、部分降级的不一致状态。
 *
 * ## 性能优化
 *  - 热点解析所需 Regex 均为单例，避免重复编译和 GC。
 *  - /proc/meminfo 解析改用纯字符串操作，避免临时 Regex 对象和 GC。
 *  - /proc/net/ 等连接数统计改为字节缓冲区计换行符，无 String 对象分配。
 *  - Root 模式的动态系统指标合并为一次持久 Shell 请求。
 */
class SystemStateCollector(
    private val context: Context,
    private val gpuCollector: GpuCollector,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    // ──────────────────────────────────────────────────────────────────────────
    // 伴生对象：共享常量
    // ──────────────────────────────────────────────────────────────────────────
    companion object {
        /** /proc/net/tcp 等文件的字节读取缓冲区大小（8 KiB）。 */
        private const val NET_BUF_SIZE = 8192

        private val DEFAULT_LOAD_AVERAGE = Triple(0.0, 0.0, 0.0)
        private val PRIVILEGED_TRAFFIC_SOURCES = listOf(
            TrafficSource.PRIVILEGED_PROC_NET_DEV,
            TrafficSource.INTERFACE_TRAFFIC_STATS,
            TrafficSource.NETWORK_STATS,
            TrafficSource.TOTAL_TRAFFIC_STATS,
            TrafficSource.DIRECT_PROC_NET_DEV
        )
        private val UNPRIVILEGED_TRAFFIC_SOURCES = PRIVILEGED_TRAFFIC_SOURCES.drop(1)
    }

    private val cpuUsageSampler = CpuUsageSampler()
    private val batteryCollector = BatteryCollector(context)
    private val trafficSourceSelector = TrafficSourceSelector()
    private val networkSpeedSampler = NetworkSpeedSampler()
    private val processCountMetric = StableMetric<Long>()
    private val connectionCountMetric = StableMetric<Pair<Long, Long>>()
    private val loadAverageMetric = StableMetric<Triple<Double, Double, Double>>()

    // ──────────────────────────────────────────────────────────────────────────
    // 日志去重标志：对于已知的不可恢复限制，只打印一次警告
    // ──────────────────────────────────────────────────────────────────────────

    /** /proc/loadavg 读取失败的警告是否已打印（SELinux 拒绝属于永久性限制，无需反复提示） */
    private var loadAvgWarningLogged = false

    // ──────────────────────────────────────────────────────────────────────────
    // 公开采集入口
    // ──────────────────────────────────────────────────────────────────────────

    suspend fun getState(isRootMode: Boolean): State = withContext(ioDispatcher) {
        collectState(isRootMode)
    }

    private suspend fun collectState(isRootMode: Boolean): State {
        // One framed transaction keeps all Shizuku-backed values from the same sampling tick and
        // avoids repeatedly crossing Binder for six tiny shell commands.
        val privilegedMetrics = if (isRootMode) readPrivilegedMetrics() else null

        // ── 1. RAM ─────────────────────────────────────────────────────────────
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val memUsed = memInfo.totalMem - memInfo.availMem

        // ── 2. Swap 使用量（/proc/meminfo，普通权限即可）──────────────────────
        val swapUsed = readSwapUsedBytes()

        // ── 3. 磁盘使用量（/data 基准 + 附加存储扫描）────────────────────────
        val diskInfo = DiskCollector.getDiskInfo(isRootMode)
        val diskUsed = diskInfo.usedBytes

        // ── 4. 网络速度与流量 ──────────────────────────────────────────────────
        val networkSample = networkSpeedSampler.sample(
            readNetworkTraffic(isRootMode, privilegedMetrics?.traffic),
            SystemClock.elapsedRealtime()
        )

        // ── 5. 温度传感器（电池温度作为系统回退值）───────────────────────────
        val batteryMetrics = batteryCollector.collect(privilegedMetrics?.batteryUevent)

        // ── 6. CPU + 进程数 + 连接数 ───────────────────────────────────────────
        val cpuUsage = readCpuUsagePercent(isRootMode, privilegedMetrics?.cpuLine)
        val processCount = readProcessCount(isRootMode, privilegedMetrics?.processCount)
        val (tcpConnCount, udpConnCount) =
            readConnectionCounts(isRootMode, privilegedMetrics?.connectionCounts)

        // ── 7. 系统负载（1 / 5 / 15 分钟平均值）────────────────────────────────
        val loadAvg = readLoadAverage(isRootMode, privilegedMetrics?.loadAverage)

        // ── 8. GPU 使用率（Root/Shizuku 模式可用）────────────────────────────
        val stateBuilder = State.newBuilder()
            .addGpuSample { gpuCollector.getGpuUsages(isRootMode) }

        return stateBuilder
            .setCpu(cpuUsage)
            .setMemUsed(memUsed)
            .setSwapUsed(swapUsed)
            .setDiskUsed(diskUsed)
            .setNetInTransfer(networkSample.snapshot.rxBytes)
            .setNetOutTransfer(networkSample.snapshot.txBytes)
            .setNetInSpeed(networkSample.rxBytesPerSecond)
            .setNetOutSpeed(networkSample.txBytesPerSecond)
            .setUptime(SystemClock.elapsedRealtime() / 1000)
            .setLoad1(loadAvg.first)
            .setLoad5(loadAvg.second)
            .setLoad15(loadAvg.third)
            .setTcpConnCount(tcpConnCount)
            .setUdpConnCount(udpConnCount)
            .setProcessCount(processCount)
            .addAllTemperatures(batteryMetrics)
            .build()
    }

    private fun readPrivilegedMetrics(): PrivilegedMetricsSnapshot? {
        return try {
            PrivilegedMetricsSnapshotParser.parse(RootShell.execute(PRIVILEGED_METRICS_COMMAND))
        } catch (e: Exception) {
            Logger.e("StateCollector: Root/Shizuku 指标快照读取失败", e)
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 网络流量采集
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 读取全系统的收发流量。
     * - Root/Shizuku 模式：通过 `cat /proc/net/dev` 解析全网卡流量，规避 Android 11+ 对 TrafficStats 的限制。
     * - 普通模式（Android 12+）：遍历 NetworkInterface 并调用 TrafficStats.getRxBytes(iface.name)。
     * - 普通模式（Android 6+ 降级）：尝试使用 NetworkStatsManager 查询设备总计。
     * - 普通模式（最低兜底）：使用 TrafficStats 总计，再尝试直读 `/proc/net/dev`。
     *
     * 选中的来源会保持稳定；短暂失败不会立刻切换计数域。确需切换时，网速采样器会先
     * 重新建立基线，因此不会把两个口径的累计值相减。
     */
    private fun readNetworkTraffic(
        isRootMode: Boolean,
        privilegedTraffic: TrafficSnapshot?
    ): TrafficReading? {
        val candidates = if (isRootMode) PRIVILEGED_TRAFFIC_SOURCES else UNPRIVILEGED_TRAFFIC_SOURCES
        return trafficSourceSelector.read(candidates) { source ->
            when (source) {
                TrafficSource.PRIVILEGED_PROC_NET_DEV -> privilegedTraffic
                TrafficSource.INTERFACE_TRAFFIC_STATS -> readInterfaceTrafficStats()
                TrafficSource.NETWORK_STATS -> readNetworkStats()
                TrafficSource.TOTAL_TRAFFIC_STATS -> readTotalTrafficStats()
                TrafficSource.DIRECT_PROC_NET_DEV -> ProcNetDevReader.read()
            }
        }
    }

    private fun readInterfaceTrafficStats(): TrafficSnapshot? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return null
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
            var rx = 0L
            var tx = 0L
            var hasRx = false
            var hasTx = false
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || isIgnoredTrafficInterface(networkInterface.name)) continue

                val interfaceRx = TrafficStats.getRxBytes(networkInterface.name)
                if (interfaceRx >= 0L) {
                    rx = addWithoutOverflow(rx, interfaceRx) ?: return@runCatching null
                    hasRx = true
                }
                val interfaceTx = TrafficStats.getTxBytes(networkInterface.name)
                if (interfaceTx >= 0L) {
                    tx = addWithoutOverflow(tx, interfaceTx) ?: return@runCatching null
                    hasTx = true
                }
            }
            if (hasRx && hasTx) TrafficSnapshot(rx, tx) else null
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun readNetworkStats(): TrafficSnapshot? {
        val manager = context.getSystemService(Context.NETWORK_STATS_SERVICE)
            as? android.app.usage.NetworkStatsManager ?: return null
        var rx = 0L
        var tx = 0L
        var hasSnapshot = false
        val networkTypes = intArrayOf(
            android.net.ConnectivityManager.TYPE_WIFI,
            android.net.ConnectivityManager.TYPE_MOBILE,
            android.net.ConnectivityManager.TYPE_ETHERNET
        )
        for (networkType in networkTypes) {
            val bucket = runCatching {
                manager.querySummaryForDevice(networkType, null, 0L, System.currentTimeMillis())
            }.getOrNull() ?: continue
            if (bucket.rxBytes < 0L || bucket.txBytes < 0L) continue
            rx = addWithoutOverflow(rx, bucket.rxBytes) ?: return null
            tx = addWithoutOverflow(tx, bucket.txBytes) ?: return null
            hasSnapshot = true
        }
        return if (hasSnapshot) TrafficSnapshot(rx, tx) else null
    }

    private fun readTotalTrafficStats(): TrafficSnapshot? {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        return if (rx >= 0L && tx >= 0L) TrafficSnapshot(rx, tx) else null
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CPU 使用率采集（两层策略）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 读取 CPU 使用率，范围 [0.0, 100.0]。
     *
     * ### Root 模式
     * 从 [RootShell] 合并快照读取 `/proc/stat`，绕过 SELinux，再通过差值法
     * （本次 - 上次）计算精确使用率。
     *
     * ### 普通模式（Android ≤ 8）
     * 直接读取 `/proc/stat` 并差值法计算。
     *
     * ### 普通模式（Android 9+ SELinux 拒绝）
     * 直接返回 **0.0**。
     * 放弃 `top` 降级方案的原因：
     * 1. `top -n 1` 输出的是系统自启动以来的 **平均值**，非当前瞬时值。
     * 2. 各 OEM ROM 对 top 输出格式改动较大，解析准确率堪忧。
     * 3. 若需准确数据，应提示用户启用 Root 模式。
     *
     * @param isRootMode 是否处于 Root/Shizuku 提权模式
     * @return [0.0, 100.0] 内的 CPU 使用率
     */
    private fun readCpuUsagePercent(isRootMode: Boolean, privilegedLine: String?): Double {
        val line = try {
            if (isRootMode) {
                privilegedLine
            } else {
                File("/proc/stat").bufferedReader().use { it.readLine() }
            }
        } catch (e: Exception) {
            // Android 9+ SELinux 策略收紧导致 EACCES，属预期行为
            // 诚实返回 0.0，不使用 top（第一帧陷阱 + OEM 格式混乱）
            null
        }
        return parseProcStatLine(line)
    }

    /**
     * 解析 `/proc/stat` 第一行（"cpu" 综合行），通过差值法计算使用率。
     *
     * 行格式：`cpu  <user> <nice> <system> <idle> <iowait> <irq> <softirq> ...`
     *
     * 第一行是**所有核心**的累加，因此差值法的结果天然为 0-100%，
     * 无需额外除以核心数。
     *
     * @param line /proc/stat 的第一行
     * @return [0.0, 100.0] 的 CPU 使用率；首次调用返回 0.0，短暂缺样沿用上一有效值
     */
    private fun parseProcStatLine(line: String?): Double {
        return cpuUsageSampler.sample(line)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 系统负载采集（/proc/loadavg）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 读取系统负载平均值（1 分钟 / 5 分钟 / 15 分钟）。
     *
     * /proc/loadavg 格式示例：`0.34 0.28 0.22 1/345 12345`
     * 前三个字段分别为 1/5/15 分钟的 CPU 队列平均长度。
     *
     * ### 权限策略
     * - **Root/Shizuku 模式**：从 [RootShell] 合并快照读取 `/proc/loadavg`，
     *   绕过 Android 9+ 的 SELinux 限制。
     * - **普通模式**：直接读取 `/proc/loadavg`。
     *   Android 7~8 的内核通常允许读取此文件；
     *   Android 9+ 部分 OEM ROM 可能通过 SELinux 策略拒绝读取，
     *   此时返回 (0.0, 0.0, 0.0) 作为安全默认值。
     *
     * @param isRootMode 是否处于 Root/Shizuku 提权模式
     * @return Triple(load1, load5, load15)，读取失败返回 (0.0, 0.0, 0.0)
     */
    private fun readLoadAverage(
        isRootMode: Boolean,
        privilegedLoad: Triple<Double, Double, Double>?
    ): Triple<Double, Double, Double> {
        val fallback = { parseLoadAverage(readLoadAvgDirect()) ?: DEFAULT_LOAD_AVERAGE }
        return if (isRootMode) loadAverageMetric.resolve(privilegedLoad, fallback) else fallback()
    }

    /**
     * 直接读取 /proc/loadavg 文件内容（普通模式和 Root 模式回退时使用）。
     *
     * @return 文件第一行内容，读取失败返回 null
     */
    private fun readLoadAvgDirect(): String? {
        return try {
            val result = File("/proc/loadavg").bufferedReader().use { it.readLine() }
            // 如果曾经失败后又变为可读（例如切换了模式），重置标志允许未来再次打印
            if (result != null) loadAvgWarningLogged = false
            result
        } catch (e: Exception) {
            // Android 9+ SELinux 拒绝属已知的不可恢复限制，只在首次失败时打印一次警告
            if (!loadAvgWarningLogged) {
                Logger.i("StateCollector: 普通模式无法读取 /proc/loadavg（SELinux 限制），Load 数据不可用")
                loadAvgWarningLogged = true
            }
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Swap 使用量（/proc/meminfo，纯字符串解析）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 从 /proc/meminfo 读取 Swap 使用量（Bytes）。
     *
     * Android 设备的 ZRAM 虚拟 Swap 会完整反映在 /proc/meminfo 中。
     * 此接口无需任何特殊权限。
     *
     * ### 性能说明
     * 不使用 `Regex("\\d+").find(line)` 提取数字，而是通过纯字符串
     * 操作直接截取，避免每次调用的临时 Regex 对象分配和 GC 压力。
     */
    private fun readSwapUsedBytes(): Long {
        var swapTotal = 0L
        var swapFree  = 0L
        var foundTotal = false
        var foundFree  = false
        try {
            File("/proc/meminfo").bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    when {
                        !foundTotal && l.startsWith("SwapTotal:") -> {
                            swapTotal = parseKbFromMeminfoLine(l)
                            foundTotal = true
                        }
                        !foundFree && l.startsWith("SwapFree:") -> {
                            swapFree = parseKbFromMeminfoLine(l)
                            foundFree = true
                        }
                    }
                    // 两个值都读到后提前退出，避免读取整个文件
                    if (foundTotal && foundFree) break
                }
            }
        } catch (e: Exception) {
            Logger.e("StateCollector: 读取 /proc/meminfo Swap 失败", e)
        }
        // /proc/meminfo 单位为 kB，转换为 Bytes
        return (swapTotal - swapFree).coerceAtLeast(0L) * 1024L
    }

    /**
     * 从 /proc/meminfo 的一行中提取以 kB 为单位的数值（Long）。
     *
     * 格式示例：`SwapTotal:   2097148 kB`
     * 纯字符串实现，无临时 Regex 对象，无额外内存分配。
     *
     * @param line meminfo 中的一行，含冒号分隔的 key: value
     * @return 数值（kB），解析失败返回 0
     */
    private fun parseKbFromMeminfoLine(line: String): Long {
        // 找到冒号后的内容，例如 "   2097148 kB"
        val colonIdx = line.indexOf(':')
        if (colonIdx < 0) return 0L
        val rest = line.substring(colonIdx + 1).trimStart()
        // rest 类似 "2097148 kB"：找到第一个非数字字符截断
        var end = 0
        while (end < rest.length && rest[end].isDigit()) end++
        return if (end > 0) rest.substring(0, end).toLongOrNull() ?: 0L else 0L
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 进程数采集
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 获取当前进程总数。
     *
     * - **Root 模式**：从 [RootShell] 合并快照获取 `ps -A` 进程数（全量，含系统进程）。
     * - **普通模式**：枚举 `/proc` 下的数字子目录（每 PID 一目录，无需权限）。
     */
    private fun readProcessCount(isRootMode: Boolean, privilegedCount: Long?): Long {
        return if (isRootMode) {
            processCountMetric.resolve(privilegedCount, ::readProcessCountFromProc)
        } else readProcessCountFromProc()
    }

    /**
     * 枚举 `/proc` 目录下的纯数字子目录统计进程数。
     * 每个进程在 /proc 下均有一个以其 PID 命名的目录，此法无需任何权限。
     */
    private fun readProcessCountFromProc(): Long {
        return try {
            File("/proc").listFiles { f -> f.name.all { it.isDigit() } && f.isDirectory }
                ?.size?.toLong() ?: 0L
        } catch (e: Exception) {
            Logger.e("StateCollector: 枚举 /proc 目录统计进程数失败", e)
            0L
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TCP/UDP 连接数采集
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 获取 TCP 和 UDP 连接数，返回 Pair(tcpCount, udpCount)。
     *
     * - **Root 模式**：从 [RootShell] 合并快照获取 `ss` 全系统连接数。
     * - **普通模式**：字节级扫描 /proc/net/tcp(6) 和 /proc/net/udp(6)，
     *   统计换行符数量（减去标题行），性能远优于按行 readLine() + String 分配。
     */
    private fun readConnectionCounts(
        isRootMode: Boolean,
        privilegedCounts: Pair<Long, Long>?
    ): Pair<Long, Long> {
        return if (isRootMode) {
            connectionCountMetric.resolve(privilegedCounts, ::readConnectionCountsFromProc)
        } else readConnectionCountsFromProc()
    }

    /**
     * 普通模式：通过字节缓冲区扫描 /proc/net/tcp(6) 和 udp(6) 统计条目数。
     *
     * ### 性能优化说明
     * 传统 `bufferedReader().readLine()` 每行都会创建一个 String 对象，
     * 在有数百条连接的场景（高并发服务端）会造成大量短生命周期对象和 GC 停顿。
     *
     * 改为直接以字节流扫描，统计 `\n` 出现次数（每行一个换行符），
     * 完全避免 String 对象分配，CPU 和内存占用降低一个数量级。
     *
     * 标题行也包含换行符，最终结果减 1 以排除。
     */
    private fun readConnectionCountsFromProc(): Pair<Long, Long> {
        val tcpCount = countNewlinesInFiles(
            File("/proc/net/tcp"),
            File("/proc/net/tcp6")
        )
        val udpCount = countNewlinesInFiles(
            File("/proc/net/udp"),
            File("/proc/net/udp6")
        )
        return Pair(tcpCount, udpCount)
    }

    /**
     * 统计一组文件中换行符 `\n` 的总出现次数（减 1 排除每文件标题行）。
     *
     * 使用 [NET_BUF_SIZE] 字节的复用缓冲区，单次最多读取 8 KiB，
     * 同一缓冲区在循环中重复使用，不进行额外内存分配。
     *
     * @param files 要扫描的文件列表（不存在或无读权限的文件会被跳过）
     * @return 所有文件的有效条目总数（已减去标题行）
     */
    private fun countNewlinesInFiles(vararg files: File): Long {
        val buf = ByteArray(NET_BUF_SIZE)
        var total = 0L

        for (file in files) {
            if (!file.exists() || !file.canRead()) continue
            var newlines = 0L
            try {
                file.inputStream().use { stream ->
                    var bytesRead: Int
                    while (stream.read(buf).also { bytesRead = it } > 0) {
                        // 逐字节扫描换行符，无 String 对象分配
                        for (i in 0 until bytesRead) {
                            if (buf[i] == '\n'.code.toByte()) newlines++
                        }
                    }
                }
                // 每个文件首行为列标题（也含换行符），减 1 得实际条目数
                total += (newlines - 1L).coerceAtLeast(0L)
            } catch (e: Exception) {
                Logger.e("StateCollector: 字节扫描 ${file.name} 失败", e)
            }
        }
        return total
    }
}
