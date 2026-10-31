package com.nezhahq.agent.executor

/**
 * `host:port` 目标字符串解析。
 *
 * 抽出来的原因：NAT 穿透与 TCP Ping 都要把面板下发的一个字符串拆成主机和端口，
 * 而两边各写各的——TCP Ping 那份直接把整串当主机、端口留 0，于是"兼容旧版面板"的
 * 纯字符串格式一次都没连通过。规则集中在这里，两处只在"缺省端口"上有区别
 * （见 [parse] 的 defaultPort）。
 *
 * 解析刻意保守：IPv6 必须写成方括号形式。裸的 `2001:db8::1` 与"地址 + 端口"在字面上
 * 无法区分，猜错的代价是连到一个完全不相干的地址，不如明确报错。
 */
internal object HostPort {

    /** 端口合法范围。0 是"由内核分配"的语义，作为连接目标没有意义。 */
    private val VALID_PORTS = 1..65535

    sealed interface Result {
        data class Parsed(val host: String, val port: Int) : Result

        /**
         * [reason] 面向诊断，可以回报给面板（目标串本来就是面板下发的）；
         * 但不要写进本地日志——App 内日志窗和 logcat 都可读。
         */
        data class Invalid(val reason: String) : Result
    }

    /**
     * 解析 `host:port` / `[IPv6]:port` / `host`。
     *
     * @param defaultPort 省略端口时使用的端口；传 null 表示"必须显式给出端口"。
     */
    fun parse(value: String, defaultPort: Int? = null): Result {
        // ── 方括号形式：[::1] 或 [::1]:8080 ──
        if (value.startsWith("[")) {
            val close = value.indexOf(']')
            // close <= 1 同时覆盖"没有右括号"和"[]"（主机为空）两种畸形输入
            if (close <= 1) return malformed(value)
            val host = value.substring(1, close)
            val rest = value.substring(close + 1)
            return when {
                rest.isEmpty() -> withDefaultPort(host, defaultPort, value)
                rest.startsWith(":") -> parsePort(host, rest.substring(1))
                else -> malformed(value)
            }
        }

        val firstColon = value.indexOf(':')
        val lastColon = value.lastIndexOf(':')

        // ── 没有冒号：只有主机 ──
        if (lastColon < 0) {
            return if (value.isEmpty()) malformed(value) else withDefaultPort(value, defaultPort, value)
        }

        // ── 多个冒号却没有方括号：裸 IPv6，末段是端口还是地址段无从判定 ──
        if (firstColon != lastColon) {
            return Result.Invalid("IPv6 地址需要方括号: $value（期望格式: [address]:port）")
        }

        val host = value.substring(0, lastColon)
        val portText = value.substring(lastColon + 1)
        // 空主机（":80"）与空端口（"host:"）都归为格式错误，比"端口号无效"更贴近实情
        if (host.isEmpty() || portText.isEmpty()) return malformed(value)
        return parsePort(host, portText)
    }

    /**
     * 校验一份已经拆开的主机/端口（例如来自 JSON 字段），使结构化来源与字符串来源
     * 走同一套范围检查，不会出现"JSON 写 port=0 就静默连不上"。
     */
    fun of(host: String, port: Int): Result = when {
        host.isBlank() -> Result.Invalid("主机名为空")
        port !in VALID_PORTS -> Result.Invalid("端口号超出范围: $port")
        else -> Result.Parsed(host, port)
    }

    private fun parsePort(host: String, portText: String): Result {
        // toIntOrNull 同时挡下非数字和超出 Int 范围的输入
        val port = portText.toIntOrNull() ?: return Result.Invalid("无效的端口号: $portText")
        if (port !in VALID_PORTS) return Result.Invalid("端口号超出范围: $port")
        return Result.Parsed(host, port)
    }

    private fun withDefaultPort(host: String, defaultPort: Int?, original: String): Result =
        if (defaultPort == null) malformed(original) else Result.Parsed(host, defaultPort)

    private fun malformed(value: String): Result =
        Result.Invalid("无效的 Host 格式: $value（期望格式: host:port）")
}
