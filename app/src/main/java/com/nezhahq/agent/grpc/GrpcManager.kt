package com.nezhahq.agent.grpc

import android.content.Context
import com.nezhahq.agent.util.ConfigStore
import com.nezhahq.agent.util.Logger
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.okhttp.OkHttpChannelBuilder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import proto.NezhaServiceGrpcKt.NezhaServiceCoroutineStub
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * gRPC 连接状态枚举，用于驱动 UI 实时反馈。
 *
 * 状态转换流程：
 * IDLE → CONNECTING → CONNECTED ⇄ RECONNECTING
 *                   ↘ AUTH_FAILED
 *                   ↘ TLS_FALLBACK（TLS 失败降级为明文）
 */
enum class GrpcConnectionState {
    /** 初始/已停止状态 */
    IDLE,
    /** 正在建立 gRPC 连接 */
    CONNECTING,
    /** 双向流已建立，数据正常上报中 */
    CONNECTED,
    /** 连接断开后正在自动重连 */
    RECONNECTING,
    /** 认证失败（密钥或 UUID 不匹配） */
    AUTH_FAILED,
    /** TLS 连接失败已降级为明文传输 */
    TLS_FALLBACK
}

/**
 * gRPC 连接管理器（单例）。
 *
 * ## TLS 安全策略
 * 始终使用 TLS 加密连接，不会降级为明文传输。
 * 为兼容自签名 Dashboard 部署，TLS 层信任所有证书。
 * TLS 连接失败时持续重试并记录日志，确保凭据始终通过加密通道传输。
 *
 * ## 防闪退保护
 * TLS 初始化过程中的 SSL Context 创建、证书信任管理器等环节
 * 均有完整的异常捕获，任何异常仅记录日志，不会导致应用闪退。
 */
object GrpcManager {

    /** TLS 连续失败计数（仅用于日志，不触发降级） */
    @Suppress("unused")
    private const val MAX_TLS_FAILURES = 3

    private var channel: ManagedChannel? = null
    var stub: NezhaServiceCoroutineStub? = null
        private set

    // ── TLS 降级状态管理 ──────────────────────────────────────────────────
    /** TLS 连续失败计数（线程安全：仅在 AgentService 单协程循环中访问） */
    @Volatile
    private var tlsFailCount = 0

    /** 当前是否已降级为明文传输 */
    @Volatile
    private var tlsFallbackActive = false

    // ── gRPC 连接状态 StateFlow，供 ViewModel 收集并驱动 UI 变更 ──
    private val _connectionState = MutableStateFlow(GrpcConnectionState.IDLE)
    val connectionState: StateFlow<GrpcConnectionState> = _connectionState.asStateFlow()

    /** 更新连接状态（由 AgentService 在关键节点调用）。 */
    fun updateState(state: GrpcConnectionState) {
        _connectionState.value = state
    }

    /**
     * 记录一次 TLS 连接失败（仅用于日志和 UI 提示，不触发降级）。
     *
     * [安全修复] 移除了原始的 TLS→明文自动降级逻辑。
     * TLS 失败时持续重试 TLS，永不降级为明文，确保凭据不会以明文传输。
     */
    fun recordTlsFailure() {
        tlsFailCount++
        Logger.i("GrpcManager: TLS 连接失败计数 $tlsFailCount（将持续重试 TLS，不降级为明文）")
    }

    /**
     * 记录一次连接成功，重置 TLS 失败计数。
     */
    fun recordConnectionSuccess() {
        if (tlsFailCount > 0) {
            Logger.i("GrpcManager: 连接成功，重置 TLS 失败计数（之前 $tlsFailCount 次）")
        }
        tlsFailCount = 0
    }

    /**
     * 重置 TLS 降级状态（通常在 Service 重启时调用）。
     * 重置后下次 initialize 将重新尝试 TLS 连接。
     */
    fun resetTlsFallback() {
        tlsFailCount = 0
        tlsFallbackActive = false
        Logger.i("GrpcManager: TLS 降级状态已重置，下次将重新尝试 TLS 连接")
    }

    /** 查询当前是否处于 TLS 降级（明文）模式 */
    fun isTlsFallbackActive(): Boolean = tlsFallbackActive

    /**
     * 初始化 gRPC 通道和 Stub。
     *
     * [安全修复] 始终使用 TLS 加密传输，不再有明文降级路径。
     * 为兼容自签名 Dashboard 部署，TLS 层信任所有证书。
     * TLS 初始化异常（SSLContext 创建失败等）会被捕获并记录日志，
     * 但不会降级为明文传输。
     */
    fun initialize(context: Context) {
        val server = ConfigStore.getServer(context)
        val port = ConfigStore.getPort(context)
        val secret = ConfigStore.getSecret(context)
        val uuid = ConfigStore.getUuid(context)

        if (server.isEmpty() || secret.isEmpty() || uuid.isEmpty()) return

        shutdown() // 关闭之前的连接

        val builder = OkHttpChannelBuilder.forAddress(server, port)

        // ── 始终使用 TLS 加密连接 ──────────────────────────────────────────
        try {
            builder.useTransportSecurity()
            // 信任所有证书（兼容自签名 Dashboard 部署）
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory)
            Logger.i("GrpcManager: 使用 TLS 加密连接 $server:$port")
        } catch (e: Exception) {
            // TLS 初始化自身失败（如 SSLContext 创建异常），记录日志但不降级
            // [安全修复] 不再降级为明文连接，避免凭据以明文传输
            Logger.e("GrpcManager: TLS 初始化异常，连接可能无法建立（不降级为明文）", e)
        }

        channel = builder
            .keepAliveTime(10, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .intercept(AuthInterceptor(secret, uuid))
            .build()

        stub = NezhaServiceCoroutineStub(channel!!)
    }

    /**
     * 关闭 gRPC 连接。
     * 注意：仅关闭通道，不重置 TLS 降级状态（降级状态由 [resetTlsFallback] 管理）。
     */
    fun shutdown() {
        Logger.i("Closing Grpc connection stub.")
        channel?.shutdownNow()
        channel = null
        stub = null
        _connectionState.value = GrpcConnectionState.IDLE
    }
}
