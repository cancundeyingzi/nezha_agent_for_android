package com.nezhahq.agent.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nezhahq.agent.R
import com.nezhahq.agent.appContainer
import com.nezhahq.agent.grpc.ManagedGrpcConnection
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Android lifecycle, foreground notification, and reload-command adapter.
 *
 * Business work and resource ownership live in an [AgentRuntime] instance.
 */
class AgentService : Service() {
    private val commandJob = SupervisorJob()
    private val commandScope = CoroutineScope(
        Dispatchers.IO +
            commandJob +
            CoroutineExceptionHandler { _, throwable ->
                Logger.e("AgentService: reload worker failed", throwable)
            }
    )

    private lateinit var runtimeController: AgentRuntimeController
    private lateinit var commandProcessor: AgentConfigCommandProcessor

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AgentServiceRunningState.onCreated()
        // Built before startForeground so onDestroy always finds initialized collaborators.
        val container = applicationContext.appContainer
        val connectionState = container.connectionState
        runtimeController = AgentRuntimeController(
            factory = AgentRuntimeFactory { config ->
                AgentRuntime(
                    context = applicationContext,
                    config = config,
                    statusSink = { state, message ->
                        connectionState.updateState(state)
                        updateNotification(message)
                    },
                    grpcConnection = ManagedGrpcConnection(
                        config,
                        connectionState::updateState
                    )
                )
            },
            applyPrivilegedAccess = RootShell::configureAuthorization,
            finalCleanup = {
                RootShell.shutdown()
                Logger.i("AgentService: process-owned RootShell resources closed.")
            },
            onTeardownFailure = { failure ->
                Logger.e("AgentService: 旧运行时清理未完成，已继续启动新连接", failure)
            }
        )
        commandProcessor = AgentConfigCommandProcessor(
            scope = commandScope,
            repository = container.configRepository,
            controller = runtimeController,
            ioDispatcher = Dispatchers.IO,
            onConfigurationUnusable = { failure ->
                Logger.e("AgentService: 连接配置不可用，服务停止，等待用户修正配置", failure)
                updateNotification("连接配置无效或不可用，服务已停止")
                stopSelf()
            },
            onRuntimeUnavailable = { failure, retryDelayMillis ->
                val retrySeconds = retryDelayMillis / MILLIS_PER_SECOND
                Logger.e("AgentService: 探针启动失败，$retrySeconds 秒后重试", failure)
                updateNotification("启动失败，$retrySeconds 秒后重试")
            },
            onReloadRejected = { failure ->
                Logger.e("AgentService: 重载被拒绝，继续使用当前连接", failure)
                updateNotification("重载失败，继续使用当前连接")
            },
            awaitPreviousShutdown = SHUTDOWN_GATE::awaitIdle
        )
        createNotificationChannel()
        startAgentForeground("正在读取连接配置...")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val decision = AgentServiceCommandPolicy.decide(intent?.action)
        when (decision.command) {
            AgentServiceCommand.START_OR_RELOAD,
            AgentServiceCommand.RECOVER_LATEST -> commandProcessor.requestReload()
            AgentServiceCommand.REFRESH_CAPABILITIES -> commandProcessor.requestCapabilityRefresh()
            AgentServiceCommand.IGNORE ->
                Logger.i("AgentService: ignored unknown action ${intent?.action}")
        }
        return decision.startResult
    }

    /**
     * Hands teardown to [SHUTDOWN_GATE] instead of blocking the main thread.
     *
     * Joining stream sessions can take arbitrarily long because their socket reads do not observe
     * coroutine cancellation; the gate keeps the next runtime from starting until this finishes.
     *
     * Closing the processor first cancels its worker, so a reload waiting on the gate unblocks
     * instead of waiting for the shutdown it is itself part of.
     */
    override fun onDestroy() {
        AgentServiceRunningState.onDestroyed()
        val processor = commandProcessor
        val controller = runtimeController
        val scope = commandScope
        SHUTDOWN_GATE.submit {
            try {
                processor.close()
                controller.stop()
            } finally {
                scope.cancel()
            }
        }
        super.onDestroy()
    }

    private fun startAgentForeground(statusText: String) {
        when {
            Build.VERSION.SDK_INT >= 34 -> {
                @Suppress("InlinedApi")
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(statusText),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(statusText),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
            else -> startForeground(NOTIFICATION_ID, createNotification(statusText))
        }
    }

    /** Idempotent, but called once from [onCreate] rather than on every status change. */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Nezha Agent Status",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun createNotification(statusText: String): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Nezha Agent Running")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .build()

    private fun updateNotification(statusText: String) {
        try {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, createNotification(statusText))
        } catch (exception: Exception) {
            Logger.e("AgentService: notification update failed", exception)
        }
    }

    companion object {
        const val ACTION_START_OR_RELOAD =
            "com.nezhahq.agent.action.START_OR_RELOAD"
        const val ACTION_REFRESH_CAPABILITIES =
            "com.nezhahq.agent.action.REFRESH_CAPABILITIES"

        private const val NOTIFICATION_CHANNEL_ID = "nezha_agent_service"
        private const val NOTIFICATION_ID = 1001
        private const val MILLIS_PER_SECOND = 1_000L

        /** Outlives any single service instance so a restart waits for the previous teardown. */
        private val SHUTDOWN_GATE = RuntimeShutdownGate { failure ->
            Logger.e("AgentService: 停机未完全成功，已继续释放其余资源", failure)
        }

        fun startOrReload(context: Context) = send(context, ACTION_START_OR_RELOAD)

        /**
         * Applies changed remote capability grants to the live runtime without reconnecting.
         *
         * Returns false when no service instance is running, in which case the next start already
         * picks the grants up from storage.
         */
        fun requestCapabilityRefreshIfRunning(context: Context): Boolean =
            sendIfRunning(context, ACTION_REFRESH_CAPABILITIES)

        private fun sendIfRunning(context: Context, action: String): Boolean {
            if (!AgentServiceRunningState.canRequestReload()) return false
            send(context, action)
            return true
        }

        private fun send(context: Context, action: String) {
            val intent = Intent(context, AgentService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
