package com.nezhahq.agent.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.nezhahq.agent.util.Logger
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 纯计量透传 VPN 服务（不代理、不转发、不影响网络）。
 *
 * ## 设计目标
 * 在无 Root/Shizuku 且 Android < 12 的设备上，通过建立一个
 * **不拦截任何真实流量** 的 VPN 接口，利用 `/proc/net/dev` 内核文件
 * 精确统计全系统网络流量（rx/tx 字节数）。
 *
 * ## 工作原理
 * 1. 通过 [VpnService.Builder] 建立 TUN 接口，但 **仅路由虚拟网段**
 *    `10.0.0.0/32`（不捕获任何真实流量）
 * 2. 定时线程每 [POLL_INTERVAL_SEC] 秒读取 `/proc/net/dev`，
 *    解析所有非 `lo`、非 `tun` 网卡的累计收发字节数
 * 3. 通过 [AtomicLong] 暴露最新的 rx/tx 字节值，供 [SystemStateCollector] 查询
 *
 * ## 与旧版的核心区别
 * - **旧版**：路由 `0.0.0.0/0` 捕获所有流量，手动实现 TCP/UDP 代理转发
 *   → 代理 bug 导致整机断网
 * - **新版**：不捕获真实流量，直接读取内核统计数据
 *   → 零网络干扰，代码从 1200+ 行精简至 ~150 行
 *
 * ## 安全性
 * - 不读取任何数据包内容，仅读取 `/proc/net/dev` 中的字节计数器
 * - 不修改系统路由，真实流量走原始网络路径
 * - `/proc/net/dev` 在所有 Android 版本上对普通应用开放（无需 Root）
 *
 * @see SystemStateCollector.readNetworkTrafficBytes 作为降级策略被调用
 */
class TrafficVpnService : VpnService() {

    companion object {
        private const val TAG = "TrafficVPN"

        /** 流量采集轮询间隔（秒） */
        private const val POLL_INTERVAL_SEC = 2L

        /** VPN 接口虚拟 IP（仅用于满足 Builder 的必填参数，不参与真实路由） */
        private const val VPN_ADDRESS = "10.0.0.2"

        // ── 全局流量计数器（线程安全，供 SystemStateCollector 静态查询）──

        /** 设备累计接收字节数（来自 /proc/net/dev） */
        private val sTotalRxBytes = AtomicLong(0L)

        /** 设备累计发送字节数（来自 /proc/net/dev） */
        private val sTotalTxBytes = AtomicLong(0L)

        /** VPN 服务是否正在运行 */
        private val sRunning = AtomicBoolean(false)

        /**
         * 获取 VPN 计量的流量字节数。
         * 供 [SystemStateCollector] 在其他策略失败后调用。
         *
         * @return Pair(rxBytes, txBytes)，VPN 未运行时返回 (0, 0)
         */
        fun getTrafficBytes(): Pair<Long, Long> {
            return if (sRunning.get()) {
                Pair(sTotalRxBytes.get(), sTotalTxBytes.get())
            } else {
                Pair(0L, 0L)
            }
        }

        /** VPN 服务是否正在运行 */
        fun isRunning(): Boolean = sRunning.get()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 实例状态
    // ──────────────────────────────────────────────────────────────────────────

    /** TUN 接口文件描述符（仅用于保持 VPN 会话，不进行读写） */
    private var vpnInterface: ParcelFileDescriptor? = null

    /** 定时采集调度器（单线程） */
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "$TAG-Poller").apply { isDaemon = true }
    }

    /** 定时任务句柄（用于取消） */
    private var pollTask: ScheduledFuture<*>? = null

    // ──────────────────────────────────────────────────────────────────────────
    // Service 生命周期
    // ──────────────────────────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (sRunning.get()) {
            Logger.i("$TAG: VPN 已在运行，忽略重复启动")
            return START_STICKY
        }

        if (establishVpn()) {
            sRunning.set(true)
            // 立即采集一次，然后定时采集
            pollTrafficStats()
            pollTask = scheduler.scheduleAtFixedRate(
                ::pollTrafficStats,
                POLL_INTERVAL_SEC,
                POLL_INTERVAL_SEC,
                TimeUnit.SECONDS
            )
            Logger.i("$TAG: VPN 纯计量服务已启动（不拦截流量）")
        } else {
            Logger.e("$TAG: VPN 接口建立失败")
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Logger.i("$TAG: VPN 服务正在销毁...")
        sRunning.set(false)

        // 取消定时采集
        pollTask?.cancel(false)
        pollTask = null
        scheduler.shutdownNow()

        // 关闭 TUN 接口
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null

        Logger.i("$TAG: VPN 服务已停止，最终 RX=${sTotalRxBytes.get()}, TX=${sTotalTxBytes.get()}")
        super.onDestroy()
    }

    override fun onRevoke() {
        // 用户在系统设置中撤销了 VPN 权限
        Logger.i("$TAG: VPN 权限被用户撤销")
        stopSelf()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // VPN 接口建立（不拦截真实流量）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 建立 VPN TUN 接口（纯占位模式）。
     *
     * 关键配置：
     * - `addAddress("10.0.0.2", 32)`：虚拟 VPN 接口 IP
     * - `addRoute("10.0.0.0", 32)`：**仅路由虚拟网段**，不捕获真实流量
     * - `addDisallowedApplication`：排除自身，防止 gRPC 上报受影响
     * - 不设置 DNS 服务器：DNS 查询走系统原始路由
     *
     * 与旧版的区别：旧版使用 `addRoute("0.0.0.0", 0)` 捕获所有流量，
     * 新版仅路由一个无人使用的虚拟 IP，真实流量完全不受影响。
     *
     * @return true 表示建立成功
     */
    private fun establishVpn(): Boolean {
        return try {
            val builder = Builder()
                .setSession("哪吒流量计量")
                .addAddress(VPN_ADDRESS, 32)
                // ── 核心改动：仅路由虚拟网段，不拦截真实流量 ──
                .addRoute("10.0.0.0", 32)
                .setMtu(1500)
                .setBlocking(false)  // 非阻塞模式，无需读取 TUN

            // ── DNS 配置：继承系统 DNS，确保 DNS 查询不受 VPN 影响 ──
            var dnsAdded = false
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val linkProps: LinkProperties? = cm?.activeNetwork?.let { cm.getLinkProperties(it) }
                linkProps?.dnsServers?.forEach { dns ->
                    builder.addDnsServer(dns)
                    dnsAdded = true
                }
            } catch (_: Exception) {}
            if (!dnsAdded) {
                builder.addDnsServer("8.8.8.8")
                builder.addDnsServer("8.8.4.4")
            }

            // 排除自身应用
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Logger.e("$TAG: 排除自身应用失败", e)
            }

            vpnInterface = builder.establish()
            vpnInterface != null
        } catch (e: Exception) {
            Logger.e("$TAG: 建立 VPN 接口异常", e)
            false
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // 流量采集（读取 /proc/net/dev）
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * 从 `/proc/net/dev` 读取所有真实网卡的累计收发字节数。
     *
     * `/proc/net/dev` 格式示例：
     * ```
     * Inter-|   Receive                            |  Transmit
     *  face |   bytes  packets  ...                |   bytes  packets  ...
     *  wlan0: 123456789  98765  ...                  654321098   87654  ...
     * rmnet0:  87654321  54321  ...                  432109876   43210  ...
     *    lo:   1234567   1234  ...                    1234567    1234  ...
     *  tun0:         0      0  ...                          0       0  ...
     * ```
     *
     * 解析规则：
     * - 跳过 `lo`（环回）和 `tun`（VPN 自身）接口
     * - 第 1 个数字字段 = 接收字节，第 9 个字段 = 发送字节
     *
     * 此文件在所有 Android 版本上对普通应用开放（内核级文件），无需 Root。
     */
    private fun pollTrafficStats() {
        try {
            val content = File("/proc/net/dev").readText()
            var totalRx = 0L
            var totalTx = 0L
            var hasData = false

            content.lineSequence().forEach { line ->
                val parsed = parseProcNetDevLine(line)
                if (parsed != null) {
                    totalRx += parsed.first
                    totalTx += parsed.second
                    if (parsed.first > 0 || parsed.second > 0) hasData = true
                }
            }

            if (hasData) {
                sTotalRxBytes.set(totalRx)
                sTotalTxBytes.set(totalTx)
            }
        } catch (e: Exception) {
            Logger.e("$TAG: 读取 /proc/net/dev 失败", e)
        }
    }

    /**
     * 零拷贝解析 `/proc/net/dev` 的单行数据。
     *
     * 通过纯字符索引定位，提取冒号后的第 1 个字段（接收字节）
     * 和第 9 个字段（发送字节），**无 split()、无临时 List 分配**。
     *
     * 跳过环回接口 `lo` 和 VPN 隧道接口 `tun*`。
     *
     * @param line /proc/net/dev 中的一行
     * @return Pair(rxBytes, txBytes)，行格式不匹配则返回 null
     */
    private fun parseProcNetDevLine(line: String): Pair<Long, Long>? {
        // 查找冒号，冒号前是接口名
        val colonIdx = line.indexOf(':')
        if (colonIdx < 0) return null

        // 提取接口名（跳过前导空格）
        var nameEnd = colonIdx - 1
        while (nameEnd >= 0 && line[nameEnd] == ' ') nameEnd--
        if (nameEnd < 0) return null
        var nameStart = nameEnd
        while (nameStart > 0 && line[nameStart - 1] != ' ') nameStart--

        // 跳过环回接口 lo
        val nameLen = nameEnd - nameStart + 1
        if (nameLen == 2 && line[nameStart] == 'l' && line[nameStart + 1] == 'o') return null

        // 跳过 VPN 隧道接口（tun0, tun1, ...）
        if (nameLen >= 3 && line[nameStart] == 't' && line[nameStart + 1] == 'u'
            && line[nameStart + 2] == 'n'
        ) return null

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
}
