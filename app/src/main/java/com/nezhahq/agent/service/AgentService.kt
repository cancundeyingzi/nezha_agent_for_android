package com.nezhahq.agent.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.nezhahq.agent.collector.GeoIpCollector
import com.nezhahq.agent.collector.SystemInfoCollector
import com.nezhahq.agent.collector.SystemStateCollector
import com.nezhahq.agent.executor.NatManager
import com.nezhahq.agent.executor.TaskExecutor
import com.nezhahq.agent.executor.TerminalManager
import com.nezhahq.agent.grpc.GrpcConnectionState
import com.nezhahq.agent.grpc.GrpcManager
import com.nezhahq.agent.util.ConfigStore
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import com.nezhahq.agent.util.KeepAliveAudioPlayer
import com.nezhahq.agent.util.FloatWindowManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import proto.Nezha.Receipt
import proto.Nezha.TaskResult

class AgentService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    
    private var wakeLock: PowerManager.WakeLock? = null
    private val stateCollector by lazy { SystemStateCollector(this) }
    private val audioPlayer = KeepAliveAudioPlayer()
    
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (ConfigStore.getEnableKeepAliveAudio(this)) {
            Logger.i("AgentService: 启用无声音频保活机制")
            audioPlayer.start()
        }
        if (ConfigStore.getEnableFloatWindow(this)) {
            Logger.i("AgentService: 启用悬浮窗保活机制")
            FloatWindowManager.show(this)
        }
        // ── 前台服务 startForeground 类型适配 ─────────────────────────────────
        // Android Q (10, API 29)+ 要求在调用时传入与 Manifest 声明一致的 serviceType。
        // Android 14 (API 34)+ 对 dataSync 的审查更严格（需真实数据同步活动），
        // 改用 FOREGROUND_SERVICE_TYPE_SPECIAL_USE 对长期系统监控进程保活效果更佳，
        // 且 Manifest 中已声明对应权限 FOREGROUND_SERVICE_SPECIAL_USE 与用途说明。
        when {
            Build.VERSION.SDK_INT >= 34 -> {
                @Suppress("InlinedApi")
                startForeground(
                    1001,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                startForeground(
                    1001,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
            else -> startForeground(1001, createNotification())
        }
        acquireWakeLock()
        
        Logger.i("Service started, configuring Grpc...")
        GrpcManager.initialize(this)
        
        Logger.i("Initializing network listeners and daemon coroutines...")
        setupNetworkListener()
        startWorkLoop()
    }

    private fun setupNetworkListener() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Network changed, update geoIP
                Logger.i("Network dynamically available, polling full GeoIP metadata...")
                scope.launch {
                    val geoIp = GeoIpCollector.fetchGeoIP()
                    if (geoIp != null) GrpcManager.stub?.reportGeoIP(geoIp)
                }
            }
        })
    }

    private fun startWorkLoop() {
        scope.launch {
            while (isActive) {
                try {
                    GrpcManager.updateState(GrpcConnectionState.CONNECTING)
                    Logger.i("Preparing to handshake and send reports to Dashboard...")
                    val stub = GrpcManager.stub ?: throw Exception("Stub not initialized")
                    
                    // 1. Report Host Info
                    Logger.i("Sending Static Host Information (ReportSystemInfo2)...")
                    val hostInfo = SystemInfoCollector.getHostInfo(this@AgentService, "1.0-android")
                    stub.reportSystemInfo2(hostInfo)
                    
                    // 2. Report Geo IP
                    Logger.i("Sending GeoIP Information...")
                    val geoIp = GeoIpCollector.fetchGeoIP()
                    if (geoIp != null) stub.reportGeoIP(geoIp)
                    
                    // 3. Bidirectional streams (Status & Tasks)
                    Logger.i("Handshake success. Opening Bidirectional streams for SystemState and Tasks...")
                    GrpcManager.updateState(GrpcConnectionState.CONNECTED)
                    coroutineScope {
                        launch {
                            val stateFlow = flow {
                                while (currentCoroutineContext().isActive) {
                                    // CPU 密集型的正则匹配和字符串解析切换到
                                    // Dispatchers.Default 以更好利用多核性能，
                                    // 避免占用有限的 IO 线程池
                                    emit(withContext(Dispatchers.Default) {
                                        stateCollector.getState()
                                    })
                                    delay(2000) // Report state every 2 seconds
                                }
                            }
                            stub.reportSystemState(stateFlow).collect { receipt ->
                                // Optional logic when dashboard acks state stream chunk (ignored typically)
                            }
                        }
                        
                        launch {
                            val resultChannel = kotlinx.coroutines.channels.Channel<TaskResult>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                            stub.requestTask(resultChannel.receiveAsFlow()).collect { task ->
                                launch {
                                    when (task.type) {
                                        8L -> {
                                            // ── TaskTypeTerminalGRPC ──
                                            // Dashboard 请求打开终端，解析 StreamID 并启动 IOStream
                                            try {
                                                val json = org.json.JSONObject(task.data)
                                                val streamId = json.getString("StreamID")
                                                Logger.i("收到终端任务 (TaskID=${task.id}, StreamID=$streamId)")
                                                val terminal = TerminalManager(
                                                    this@AgentService, stub, streamId
                                                )
                                                terminal.run()
                                            } catch (e: Exception) {
                                                if (e !is CancellationException) {
                                                    Logger.e("终端任务执行失败", e)
                                                }
                                            }
                                        }
                                        9L -> {
                                            // ── TaskTypeNAT（内网穿透/反向代理）──
                                            // Dashboard 请求建立 NAT 通道，解析 StreamID 和 Host，
                                            // 通过 IOStream 双向流将远端请求转发到本地目标服务
                                            try {
                                                val json = org.json.JSONObject(task.data)
                                                val streamId = json.getString("StreamID")
                                                val natHost = json.getString("Host")
                                                Logger.i("收到 NAT 内网穿透任务 (TaskID=${task.id}, StreamID=$streamId, Host=$natHost)")
                                                val natManager = NatManager(stub, streamId, natHost)
                                                natManager.run()
                                            } catch (e: Exception) {
                                                if (e !is CancellationException) {
                                                    Logger.e("NAT 内网穿透任务执行失败", e)
                                                }
                                            }
                                        }
                                        else -> {
                                            // 其他任务类型：HTTP/ICMP/TCP/Command 等
                                            val result = TaskExecutor.executeTask(task, isCommandEnabled = false)
                                            resultChannel.send(result)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 检测认证失败（gRPC UNAUTHENTICATED 状态码）
                    val isAuthError = e.message?.contains("UNAUTHENTICATED", ignoreCase = true) == true
                    if (isAuthError) {
                        GrpcManager.updateState(GrpcConnectionState.AUTH_FAILED)
                        Logger.e("Agent loop: 认证失败，请检查密钥和 UUID 配置", e)
                    } else {
                        GrpcManager.updateState(GrpcConnectionState.RECONNECTING)
                        Logger.e("Agent loop terminated/failed", e)
                    }
                    delay(5000) // Reconnect backoff
                    Logger.i("Re-initializing GrpcManager to attempt recovery...")
                    GrpcManager.initialize(this@AgentService)
                }
            }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NezhaAgent::BgWakeLock")
        wakeLock?.acquire(24 * 60 * 60 * 1000L) // Support for long-running
    }

    private fun createNotification(): Notification {
        val channelId = "nezha_agent_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Nezha Agent Status", 
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Nezha Agent Running")
            .setContentText("Connected to dashboard")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    override fun onDestroy() {
        Logger.i("Service is being destroyed globally by system or user intent.")
        super.onDestroy()
        audioPlayer.stop()
        FloatWindowManager.hide(this)
        job.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        GrpcManager.shutdown()
        // 清理 GPU 采集器缓存，确保服务重启时重新探测 sysfs 路径
        com.nezhahq.agent.collector.GpuCollector.resetCache()
        // 关闭持久化 Root Shell 会话，释放后台 su 进程资源，防止进程泄漏
        RootShell.shutdown()
        Logger.i("RootShell persistent session closed.")
    }
}
