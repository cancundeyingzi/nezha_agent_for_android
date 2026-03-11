package com.nezhahq.agent.service

import android.app.*
import android.content.Context
import android.content.Intent
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
import com.nezhahq.agent.executor.TaskExecutor
import com.nezhahq.agent.grpc.GrpcManager
import com.nezhahq.agent.util.ConfigStore
import com.nezhahq.agent.util.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import proto.Nezha.Receipt
import proto.Nezha.TaskResult

class AgentService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    
    private var wakeLock: PowerManager.WakeLock? = null
    private val stateCollector by lazy { SystemStateCollector(this) }
    
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1001, createNotification())
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
                    coroutineScope {
                        launch {
                            val stateFlow = flow {
                                while (currentCoroutineContext().isActive) {
                                    emit(stateCollector.getState())
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
                                    val result = TaskExecutor.executeTask(task, isCommandEnabled = false)
                                    resultChannel.send(result)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Logger.e("Agent loop terminated/failed", e)
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
        job.cancel()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        GrpcManager.shutdown()
    }
}
