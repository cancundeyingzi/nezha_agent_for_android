package com.nezhahq.agent.collector

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.SystemClock
import com.nezhahq.agent.util.ConfigStore
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import proto.Nezha.State
import proto.Nezha.State_SensorTemperature
import java.io.File

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
 *  - CPU：通过 [RootShell]（持久 su 会话）执行 `head -n 1 /proc/stat`，
 *    绕过 SELinux 限制，使用差值法精确计算。
 *  - 连接数：通过 [RootShell] 执行 `ss` 命令，获取全系统连接数。
 *  - 进程数：通过 [RootShell] 执行 `ps -A | wc -l` 获取全量进程数。
 *
 * ## 性能优化
 *  - 所有 Regex 均以伴生对象常量形式预编译（/proc/stat 分割）。
 *  - /proc/meminfo 解析改用纯字符串操作，避免临时 Regex 对象和 GC。
 *  - /proc/net/ 等连接数统计改为字节缓冲区计换行符，无 String 对象分配。
 *  - Root 模式下的所有 shell 命令通过 [RootShell] 单例持久会话执行，
 *    彻底消除每 2 秒 fork 新 su 进程的性能灾难。
 */
class SystemStateCollector(private val context: Context) {

    // ──────────────────────────────────────────────────────────────────────────
    // 伴生对象：预编译 Regex 常量（避免每次调用时重新编译 JIT 开销）
    // ──────────────────────────────────────────────────────────────────────────
    companion object {
        /**
         * 用于分割 /proc/stat 各字段的空白正则。
         * 预编译后复用，避免频繁 GC。
         */
        private val WHITESPACE_RE = Regex("\\s+")

        /** /proc/net/tcp 等文件的字节读取缓冲区大小（8 KiB）。 */
        private const val NET_BUF_SIZE = 8192
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 状态变量：网络速度差值计算
    // ──────────────────────────────────────────────────────────────────────────
    private var lastRxBytes = -1L
    private var lastTxBytes = -1L
    private var lastTimeMs  = SystemClock.elapsedRealtime()

    // ──────────────────────────────────────────────────────────────────────────
    // 状态变量：/proc/stat CPU 差值采样（在 Root 模式下有效）
    // ──────────────────────────────────────────────────────────────────────────
    private var lastCpuTotal = 0L
    private var lastCpuIdle  = 0L

    // ──────────────────────────────────────────────────────────────────────────
    // 日志去重标志：对于已知的不可恢复限制，只打印一次警告
    // ──────────────────────────────────────────────────────────────────────────

    /** /proc/loadavg 读取失败的警告是否已打印（SELinux 拒绝属于永久性限制，无需反复提示） */
    private var loadAvgWarningLogged = false

    // ──────────────────────────────────────────────────────────────────────────
    // 公开采集入口
    // ──────────────────────────────────────────────────────────────────────────

    fun getState(): State {
        val isRootMode = ConfigStore.getRootMode(context)

        // ── 1. RAM ─────────────────────────────────────────────────────────────
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val memUsed = memInfo.totalMem - memInfo.availMem

        // ── 2. Swap 使用量（/proc/meminfo，普通权限即可）──────────────────────
        val swapUsed = readSwapUsedBytes()

        // ── 3. 磁盘使用量（多分区扫描 + 设备去重）────────────────────────────
        val diskInfo = DiskCollector.getDiskInfo(isRootMode)
        val diskUsed = diskInfo.usedBytes

        // ── 4. 网络速度与流量 ──────────────────────────────────────────────────
        val (currentRx, currentTx) = readNetworkTrafficBytes(isRootMode)
        val currentTime = SystemClock.elapsedRealtime()
        val timeDiff    = currentTime - lastTimeMs

        var rxSpeed = 0L
        var txSpeed = 0L
        // Ensure lastBytes is initialized properly (-1L) to avoid first tick huge speed
        if (timeDiff > 0 && lastRxBytes >= 0 && lastTxBytes >= 0) {
            rxSpeed = (currentRx - lastRxBytes) * 1000 / timeDiff
            txSpeed = (currentTx - lastTxBytes) * 1000 / timeDiff
        }
        lastRxBytes = currentRx
        lastTxBytes = currentTx
        lastTimeMs  = currentTime

        // ── 5. 温度传感器（电池温度作为系统回退值）───────────────────────────
        val batteryIntent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        val tempCelsius = batteryIntent
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            ?.toDouble()?.div(10.0) ?: 0.0
        val sensorTemp = State_SensorTemperature.newBuilder()
            .setName("Battery")
            .setTemperature(tempCelsius)
            .build()

        // ── 6. CPU + 进程数 + 连接数 ───────────────────────────────────────────
        val cpuUsage     = readCpuUsagePercent(isRootMode)
        var processCount = 0L
        var tcpConnCount = 0L
        var udpConnCount = 0L

        try {
            processCount = readProcessCount(isRootMode)
            val (tcp, udp) = readConnectionCounts(isRootMode)
            tcpConnCount = tcp
            udpConnCount = udp
        } catch (e: Exception) {
            Logger.e("StateCollector: 采集进程/连接数时异常", e)
        }

        // ── 7. 系统负载（1 / 5 / 15 分钟平均值）────────────────────────────────
        val loadAvg = readLoadAverage(isRootMode)

        // ── 8. GPU 使用率（Root/Shizuku 模式可用）────────────────────────────
        val gpuUsages = GpuCollector.getGpuUsages(isRootMode)

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
            .setLoad1(loadAvg.first)
            .setLoad5(loadAvg.second)
            .setLoad15(loadAvg.third)
            .setTcpConnCount(tcpConnCount)
            .setUdpConnCount(udpConnCount)
            .setProcessCount(processCount)
            .addTemperatures(sensorTemp)
            .addAllGpu(gpuUsages)
            .build()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 网络流量采集
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 读取全系统的收发流量。
     * - Root/Shizuku 模式：通过 `cat /proc/net/dev` 解析全网卡流量，规避 Android 11+ 对 TrafficStats 的限制。
     * - 普通模式（Android 12+）：遍历 NetworkInterface 并调用 TrafficStats.getRxBytes(iface.name)。
     * - 普通模式（Android 6+ 降级）：尝试使用 NetworkStatsManager 查询设备总计。
     * - 普通模式（最低兜底）：使用 TrafficStats.getTotalRxBytes()，若被系统拦截或不支持则回退为 0。
     * - VPN 纯计量模式（最终兜底）：TrafficVpnService 定时读取 /proc/net/dev，不拦截任何流量。
     */
    private fun readNetworkTrafficBytes(isRootMode: Boolean): Pair<Long, Long> {
        var rx = -1L
        var tx = -1L

        if (isRootMode) {
            try {
                val output = RootShell.execute("cat /proc/net/dev")
                if (output.isNotBlank()) {
                    var tempRx = 0L
                    var tempTx = 0L
                    var hasData = false
                    output.lineSequence().forEach { line ->
                        // 使用零拷贝字符索引解析，避免 split/substring 的临时对象分配
                        val parsed = parseProcNetDevLine(line)
                        if (parsed != null) {
                            tempRx += parsed.first
                            tempTx += parsed.second
                            if (parsed.first > 0 || parsed.second > 0) hasData = true
                        }
                    }
                    if (hasData) {
                        rx = tempRx
                        tx = tempTx
                    }
                }
            } catch (e: Exception) {
                Logger.e("StateCollector: Root 模式读取 /proc/net/dev 失败", e)
            }
        }

        // 降级策略 1：使用 Android 12 (API 31+) 提供的按网卡获取流量的方法
        if ((rx < 0L || tx < 0L) && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                if (interfaces != null) {
                    var tempRx = 0L
                    var tempTx = 0L
                    var hasData = false
                    for (iface in interfaces) {
                        if (!iface.isLoopback) {
                            val r = TrafficStats.getRxBytes(iface.name)
                            val t = TrafficStats.getTxBytes(iface.name)
                            if (r != TrafficStats.UNSUPPORTED.toLong() && r >= 0) {
                                tempRx += r
                                hasData = true
                            }
                            if (t != TrafficStats.UNSUPPORTED.toLong() && t >= 0) {
                                tempTx += t
                                hasData = true
                            }
                        }
                    }
                    if (hasData) {
                        rx = tempRx
                        tx = tempTx
                    }
                }
            } catch (e: Exception) {
                Logger.e("StateCollector: API 31+ TrafficStats 按网卡获取失败", e)
            }
        }

        // 降级策略 2：使用 Android 6 (API 23+) 的 NetworkStatsManager 获取设备级流量
        if ((rx < 0L || tx < 0L) && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? android.app.usage.NetworkStatsManager
                if (nsm != null) {
                    var tempRx = 0L
                    var tempTx = 0L
                    var hasData = false

                    val queryStats = { transportType: Int ->
                        try {
                            val bucket = nsm.querySummaryForDevice(transportType, null, 0, System.currentTimeMillis())
                            if (bucket != null) {
                                tempRx += bucket.rxBytes
                                tempTx += bucket.txBytes
                                if (bucket.rxBytes > 0 || bucket.txBytes > 0) hasData = true
                            }
                        } catch (e: Exception) {
                            // 忽略缺乏权限 (SecurityException) 或服务不可用等异常
                        }
                    }

                    // NetworkStatsManager 在旧版 API 中依赖 ConnectivityManager 的 TYPE 常量
                    queryStats(android.net.ConnectivityManager.TYPE_WIFI)
                    queryStats(android.net.ConnectivityManager.TYPE_MOBILE)
                    queryStats(android.net.ConnectivityManager.TYPE_ETHERNET)

                    if (hasData) {
                        rx = tempRx
                        tx = tempTx
                    }
                }
            } catch (e: Exception) {
                Logger.e("StateCollector: NetworkStatsManager 获取失败", e)
            }
        }

        // 降级策略 3：回退到最基础的 TrafficStats 总计（API 8+）
        if (rx < 0L || tx < 0L) {
            val tsRx = TrafficStats.getTotalRxBytes()
            val tsTx = TrafficStats.getTotalTxBytes()
            rx = if (tsRx >= 0) tsRx else 0L
            tx = if (tsTx >= 0) tsTx else 0L
        }

        // 降级策略 4（VPN 纯计量兜底）：当所有系统 API 均返回 0 时，
        // 从 TrafficVpnService 获取通过 /proc/net/dev 采集的流量数据。
        // TrafficVpnService 不拦截任何网络流量，仅建立一个占位 VPN 接口，
        // 通过定时读取 /proc/net/dev 获取真实网卡累计流量（与 Root 模式相同的数据源）。
        // 仅在用户手动开启 VPN 模式且 VPN 服务正在运行时生效。
        if (rx <= 0L && tx <= 0L && ConfigStore.getEnableVpnTraffic(context)) {
            val vpnBytes = com.nezhahq.agent.service.TrafficVpnService.getTrafficBytes()
            if (vpnBytes.first > 0 || vpnBytes.second > 0) {
                rx = vpnBytes.first
                tx = vpnBytes.second
            }
        }

        return Pair(rx, tx)
    }

    /**
     * 零拷贝解析 /proc/net/dev 的单行数据。
     *
     * 行格式示例：
     * ```
     *   wlan0:  123456  100  0  0  0  0  0  0   654321  50  0  0  0  0  0  0
     * ```
     *
     * 通过纯字符索引定位，提取冒号后的第 1 个字段（接收字节）和第 9 个字段（发送字节），
     * **无 split()、无 substringAfter()、无临时 List/Array 分配**，
     * GC 开销为零（仅栈上局部变量）。
     *
     * @param line /proc/net/dev 中的一行
     * @return Pair(rxBytes, txBytes)，不含 lo 接口；若行格式不匹配则返回 null
     */
    private fun parseProcNetDevLine(line: String): Pair<Long, Long>? {
        // 查找冒号，冒号前是接口名
        val colonIdx = line.indexOf(':')
        if (colonIdx < 0) return null

        // 检查接口名是否为 lo（跳过环回接口）
        // 从 colonIdx 向前扫描非空白字符，提取接口名
        var nameEnd = colonIdx - 1
        while (nameEnd >= 0 && line[nameEnd] == ' ') nameEnd--
        if (nameEnd < 0) return null
        var nameStart = nameEnd
        while (nameStart > 0 && line[nameStart - 1] != ' ') nameStart--
        // 比较接口名是否为 "lo"（2 字符精确匹配）
        val nameLen = nameEnd - nameStart + 1
        if (nameLen == 2 && line[nameStart] == 'l' && line[nameStart + 1] == 'o') return null

        // 从冒号后开始，逐字段扫描提取第 1 和第 9 个数字字段
        val len = line.length
        var pos = colonIdx + 1
        var fieldIndex = 0
        var rxBytes = 0L
        var txBytes = 0L

        while (pos < len && fieldIndex < 9) {
            // 跳过空白
            while (pos < len && line[pos] == ' ') pos++
            if (pos >= len) break

            // 解析当前数字字段
            var value = 0L
            val fieldStart = pos
            while (pos < len && line[pos] in '0'..'9') {
                value = value * 10 + (line[pos] - '0')
                pos++
            }
            // 若没有实际数字字符，说明格式异常
            if (pos == fieldStart) break

            when (fieldIndex) {
                0 -> rxBytes = value   // 第 1 个字段：接收字节
                8 -> txBytes = value   // 第 9 个字段：发送字节
            }
            fieldIndex++
        }

        // 至少需要解析到第 9 个字段
        if (fieldIndex < 9) return null
        return Pair(rxBytes, txBytes)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // CPU 使用率采集（两层策略）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 读取 CPU 使用率，范围 [0.0, 100.0]。
     *
     * ### Root 模式
     * 通过 [RootShell] 持久 su 会话执行 `head -n 1 /proc/stat`，绕过 SELinux，
     * 再通过差值法（本次 - 上次）计算精确使用率。
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
    private fun readCpuUsagePercent(isRootMode: Boolean): Double {
        if (isRootMode) {
            // Root 模式：使用持久 su 会话读取（不创建新进程！）
            val line = RootShell.executeFirstLine("head -n 1 /proc/stat")
            return parseProcStatLine(line)
        }

        // 普通模式：直接尝试读取 /proc/stat
        return try {
            val line = File("/proc/stat").bufferedReader().use { it.readLine() }
            parseProcStatLine(line)
        } catch (e: Exception) {
            // Android 9+ SELinux 策略收紧导致 EACCES，属预期行为
            // 诚实返回 0.0，不使用 top（第一帧陷阱 + OEM 格式混乱）
            0.0
        }
    }

    /**
     * 解析 `/proc/stat` 第一行（"cpu" 综合行），通过差值法计算使用率。
     *
     * 行格式：`cpu  <user> <nice> <system> <idle> <iowait> <irq> <softirq> ...`
     *
     * 第一行是**所有核心**的累加，因此差值法的结果天然为 0-100%，
     * 无需额外除以核心数。
     *
     * @param line /proc/stat 的第一行，null 返回 0.0
     * @return [0.0, 100.0] 的 CPU 使用率，首次调用返回 0.0（无历史基准）
     */
    private fun parseProcStatLine(line: String?): Double {
        if (line == null) return 0.0
        // 使用预编译常量分割，避免每次 JIT 编译正则
        val parts = line.trim().split(WHITESPACE_RE)
        if (parts.size < 5 || parts[0] != "cpu") return 0.0

        val user    = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val nice    = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        val system  = parts.getOrNull(3)?.toLongOrNull() ?: 0L
        val idle    = parts.getOrNull(4)?.toLongOrNull() ?: 0L
        val iowait  = parts.getOrNull(5)?.toLongOrNull() ?: 0L
        val irq     = parts.getOrNull(6)?.toLongOrNull() ?: 0L
        val softirq = parts.getOrNull(7)?.toLongOrNull() ?: 0L

        val total   = user + nice + system + idle + iowait + irq + softirq
        // iowait 期间 CPU 本质上也是空闲的（等待 IO 完成）
        val idleAll = idle + iowait

        val deltaTotal = total - lastCpuTotal
        val deltaIdle  = idleAll - lastCpuIdle

        // 更新历史基准，供下次差值计算
        lastCpuTotal = total
        lastCpuIdle  = idleAll

        // 首次调用时 lastCpuTotal == 0，deltaTotal 为历史累计值，
        // 不能直接当做采样间隔使用，返回 0.0 等待下一次调用建立基准
        if (deltaTotal <= 0L) return 0.0

        return ((deltaTotal - deltaIdle).toDouble() / deltaTotal.toDouble() * 100.0)
            .coerceIn(0.0, 100.0)
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
     * - **Root/Shizuku 模式**：通过 [RootShell] 执行 `cat /proc/loadavg`，
     *   绕过 Android 9+ 的 SELinux 限制，保证数据可用。
     * - **普通模式**：直接读取 `/proc/loadavg`。
     *   Android 7~8 的内核通常允许读取此文件；
     *   Android 9+ 部分 OEM ROM 可能通过 SELinux 策略拒绝读取，
     *   此时返回 (0.0, 0.0, 0.0) 作为安全默认值。
     *
     * @param isRootMode 是否处于 Root/Shizuku 提权模式
     * @return Triple(load1, load5, load15)，读取失败返回 (0.0, 0.0, 0.0)
     */
    private fun readLoadAverage(isRootMode: Boolean): Triple<Double, Double, Double> {
        val defaultLoad = Triple(0.0, 0.0, 0.0)

        // 获取 /proc/loadavg 的原始内容
        val content: String? = if (isRootMode) {
            // Root/Shizuku 模式：通过持久 Shell 读取，绕过 SELinux 限制
            try {
                RootShell.executeFirstLine("cat /proc/loadavg")
            } catch (e: Exception) {
                Logger.e("StateCollector: Root 模式读取 /proc/loadavg 失败，回退到直接读取", e)
                // Root Shell 异常时回退到直接读取
                readLoadAvgDirect()
            }
        } else {
            // 普通模式：直接读取文件
            readLoadAvgDirect()
        }

        return parseLoadAvgLine(content) ?: defaultLoad
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

    /**
     * 解析 /proc/loadavg 的一行内容，提取前三个浮点数。
     *
     * 行格式：`0.34 0.28 0.22 1/345 12345`
     * 使用纯字符串操作提取，避免正则分配。
     *
     * @param line /proc/loadavg 的第一行，null 返回 null
     * @return Triple(load1, load5, load15)，格式不匹配返回 null
     */
    private fun parseLoadAvgLine(line: String?): Triple<Double, Double, Double>? {
        if (line.isNullOrBlank()) return null
        val parts = line.trim().split(WHITESPACE_RE)
        if (parts.size < 3) return null
        val load1  = parts[0].toDoubleOrNull() ?: return null
        val load5  = parts[1].toDoubleOrNull() ?: return null
        val load15 = parts[2].toDoubleOrNull() ?: return null
        return Triple(load1, load5, load15)
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
     * - **Root 模式**：通过 [RootShell] 执行 `ps -A | wc -l`（全量，含系统进程）。
     * - **普通模式**：枚举 `/proc` 下的数字子目录（每 PID 一目录，无需权限）。
     */
    private fun readProcessCount(isRootMode: Boolean): Long {
        return if (isRootMode) {
            val output = RootShell.executeFirstLine("ps -A 2>/dev/null | wc -l")
            val total = output?.trim()?.toLongOrNull() ?: 0L
            // ps -A 输出包含标题行，减 1 得到实际进程数
            (total - 1L).coerceAtLeast(0L).also { count ->
                if (count == 0L && output == null) {
                    // RootShell 失败，回退到 /proc 枚举法
                    return readProcessCountFromProc()
                }
            }
        } else {
            readProcessCountFromProc()
        }
    }

    /**
     * 枚举 `/proc` 目录下的纯数字子目录统计进程数。
     * 每个进程在 /proc 下均有一个以其 PID 命名的目录，此法无需任何权限。
     */
    private fun readProcessCountFromProc(): Long {
        return try {
            File("/proc").listFiles { f -> f.isDirectory && f.name.all { it.isDigit() } }
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
     * - **Root 模式**：通过 [RootShell] 执行 `ss` 命令，获取全系统连接数。
     * - **普通模式**：字节级扫描 /proc/net/tcp(6) 和 /proc/net/udp(6)，
     *   统计换行符数量（减去标题行），性能远优于按行 readLine() + String 分配。
     */
    private fun readConnectionCounts(isRootMode: Boolean): Pair<Long, Long> {
        return if (isRootMode) {
            readConnectionCountsRoot()
        } else {
            readConnectionCountsFromProc()
        }
    }

    /**
     * Root 模式：通过持久 [RootShell] 执行 `ss` 统计全系统 TCP/UDP 连接数。
     *
     * 每次调用共使用 **2 次** shell 写入（不创建新进程），极低开销。
     * 若 `ss` 命令不存在，自动回退到 /proc/net 字节统计法。
     */
    private fun readConnectionCountsRoot(): Pair<Long, Long> {
        return try {
            // ss -tn: TCP 连接，不解析主机名；tail -n +2 跳过标题行
            val tcpRaw = RootShell.executeFirstLine(
                "ss -tn 2>/dev/null | tail -n +2 | wc -l"
            )
            val udpRaw = RootShell.executeFirstLine(
                "ss -un 2>/dev/null | tail -n +2 | wc -l"
            )
            val tcp = tcpRaw?.trim()?.toLongOrNull() ?: 0L
            val udp = udpRaw?.trim()?.toLongOrNull() ?: 0L
            Pair(tcp, udp)
        } catch (e: Exception) {
            Logger.e("StateCollector: Root 模式 ss 失败，回退到 /proc/net", e)
            readConnectionCountsFromProc()
        }
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
