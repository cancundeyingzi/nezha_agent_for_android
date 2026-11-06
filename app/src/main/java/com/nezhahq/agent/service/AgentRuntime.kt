package com.nezhahq.agent.service

import android.content.Context
import com.nezhahq.agent.collector.GeoIpCollector
import com.nezhahq.agent.collector.GpuCollector
import com.nezhahq.agent.collector.SystemInfoCollector
import com.nezhahq.agent.collector.SystemStateCollector
import com.nezhahq.agent.core.mcp.McpPolicy
import com.nezhahq.agent.core.mcp.McpProtocolVersion
import com.nezhahq.agent.core.mcp.McpTaskFamily
import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.executor.FileManager
import com.nezhahq.agent.executor.NatManager
import com.nezhahq.agent.executor.TerminalManager
import com.nezhahq.agent.grpc.GrpcConnection
import com.nezhahq.agent.grpc.GrpcConnectionState
import com.nezhahq.agent.mcp.McpSession
import com.nezhahq.agent.mcp.McpTaskBackend
import com.nezhahq.agent.mcp.exec.McpExecBackend
import com.nezhahq.agent.mcp.fs.McpFsBackend
import com.nezhahq.agent.mcp.privileged.McpHelperLaunchers
import com.nezhahq.agent.mcp.transport.HelperLifecycleOwner
import com.nezhahq.agent.mcp.transport.McpHelperTransport
import com.nezhahq.agent.service.keepalive.KeepAliveController
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import proto.Nezha.GeoIP

internal fun interface RuntimeKeepAliveFactory {
    fun create(context: Context, scope: CoroutineScope): KeepAliveController
}

/** One configuration snapshot's composition root and complete, retryable lifecycle owner. */
internal class AgentRuntime(
    context: Context,
    private val config: AgentConfig,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    statusSink: (GrpcConnectionState, String) -> Unit,
    // The composition point must wire observable state; a silent default would hide an idle runtime.
    private val grpcConnection: GrpcConnection,
    private val networkRegistrar: RuntimeNetworkRegistrar =
        AndroidRuntimeNetworkRegistrar(context.applicationContext),
    keepAliveFactory: RuntimeKeepAliveFactory = RuntimeKeepAliveFactory(KeepAliveController::create),
    fetchGeoIp: suspend () -> GeoIP? = GeoIpCollector::fetchGeoIP
) : AgentRuntimeHandle {
    private val appContext = context.applicationContext
    private val lifecycleLock = Any()
    private val supervisor = SupervisorJob()
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Logger.e("AgentRuntime: 协程未捕获异常（已兜底，不闪退）", throwable)
    }
    private val scope = CoroutineScope(ioDispatcher + supervisor + exceptionHandler)
    private val keepAliveController = keepAliveFactory.create(appContext, scope)
    private val helperLifecycle = HelperLifecycleOwner()
    private val gpuCollector = GpuCollector()
    private val stateCollector = SystemStateCollector(appContext, gpuCollector, ioDispatcher)
    private val metricReporter = MetricReporter(
        runtimeScope = scope,
        collectHost = {
            SystemInfoCollector.getHostInfo(
                context = appContext,
                appVersion = McpProtocolVersion.hostVersion(runtimeAppVersionName(appContext)),
                gpuCollector = gpuCollector,
                isRootMode = RootShell.isAuthorized()
            )
        },
        collectState = { stateCollector.getState(RootShell.isAuthorized()) },
        fetchGeoIp = fetchGeoIp,
        currentGeoIpReporter = {
            val stub = grpcConnection.stub
            if (stub == null) null else { geoIp -> stub.reportGeoIP(geoIp); Unit }
        }
    )
    // The false branch stays lazy: constructing an ordinary runtime never discovers su/Shizuku.
    private val mcpLauncher = McpHelperLaunchers.select(appContext, config.rootMode, helperLifecycle)
    private val mcpExec = McpExecBackend(mcpLauncher, McpHelperTransport(helperLifecycle))
    private val mcpFs = McpFsBackend(mcpLauncher, McpHelperTransport(helperLifecycle))
    private val mcpBackend = McpTaskBackend { task, snapshot, reserveResult ->
        when (McpPolicy.family(task.type)) {
            McpTaskFamily.EXEC -> mcpExec.execute(task, snapshot, reserveResult = reserveResult)
            else -> mcpFs.execute(task, snapshot, reserveResult = reserveResult)
        }
    }
    private val taskDispatcher = TaskDispatcher(config.remoteCapabilities, { streamScope, capabilities ->
        McpSession(streamScope, capabilities, mcpBackend)
    })
    private val connectionSupervisor = ConnectionSupervisor(
        connection = grpcConnection,
        statusSink = statusSink,
        reportInitial = { stub ->
            metricReporter.reportInitial(
                reportHost = { stub.reportSystemInfo2(it) }
            )
        },
        reportState = { stub, onReceipt -> metricReporter.reportState({ stub.reportSystemState(it) }, onReceipt) },
        dispatchTasks = { stub ->
            taskDispatcher.run({ stub.requestTask(it) }) { sessionScope, results ->
                StreamSessionManager(sessionScope, results, runSession = { request ->
                    when (request) {
                        is StreamTaskRequest.Terminal -> TerminalManager(appContext, stub, request.streamId).run()
                        is StreamTaskRequest.Nat -> NatManager(stub, request.streamId, request.host).run()
                        is StreamTaskRequest.FileManager -> FileManager(appContext, stub, request.streamId).run()
                    }
                })
            }
        }
    )

    private var state = LifecycleState.NEW

    /**
     * Handles for the two long-lived jobs [start] launches.
     *
     * `@Volatile` rather than [lifecycleLock]: they are cleared inside the shutdown coordinator's
     * suspending teardown step, and holding a blocking monitor across `supervisor.cancelAndJoin()`
     * would trade a memory-visibility question for a real deadlock risk. Cancellation itself never
     * goes through these fields — `supervisor` owns both jobs — so publication is all they need.
     */
    @Volatile
    private var connectionJob: Job? = null

    @Volatile
    private var cleanupJob: Job? = null

    private var networkRegistration: RuntimeNetworkRegistration? = null
    private val shutdownCoordinator = RuntimeShutdownCoordinator(
        cancelAndJoinWork = {
            helperLifecycle.beginShutdown()
            supervisor.cancelAndJoin()
            helperLifecycle.close()
            connectionJob = null
            cleanupJob = null
        },
        closeNetwork = {
            networkRegistration?.close()
            networkRegistration = null
        },
        closeKeepAlive = {
            keepAliveController.closeWithin(KEEP_ALIVE_CLOSE_TIMEOUT_MILLIS)
        },
        closeGrpc = grpcConnection::close,
        onKeepAliveCloseIncomplete = {
            Logger.e("AgentRuntime: 保活资源清理超时，继续其余运行时清理")
        }
    )

    override suspend fun start() {
        synchronized(lifecycleLock) {
            when (state) {
                LifecycleState.RUNNING, LifecycleState.STARTING -> return
                LifecycleState.STOPPING, LifecycleState.STOPPED ->
                    error("A stopped AgentRuntime cannot be reused.")
                LifecycleState.NEW -> state = LifecycleState.STARTING
            }
        }

        // Privileged authorization is applied by AgentRuntimeController before this runs, so that
        // acquiring and releasing it stay in one place rather than split across two classes.
        grpcConnection.connect()
        withContext(mainDispatcher) {
            keepAliveController.reconfigure(config.keepAlive)
        }
        cleanupJob = scope.launch { cleanupStaleUploads() }
        networkRegistration = networkRegistrar.register(metricReporter::onNetworkAvailable)
        connectionJob = scope.launch { connectionSupervisor.run() }
        synchronized(lifecycleLock) { state = LifecycleState.RUNNING }
    }

    override suspend fun updateCapabilities(capabilities: RemoteCapabilities) {
        taskDispatcher.updateCapabilities(capabilities)
    }

    /**
     * Stops this runtime, and may be called again after a failed attempt.
     *
     * [RuntimeShutdownCoordinator] skips the phases that already succeeded, so a retry resumes at
     * the failed one. Only a fully successful teardown is terminal; a caller arriving while another
     * teardown is in flight waits for it instead of returning while resources are still held.
     */
    override suspend fun stop() {
        val alreadyStopped = synchronized(lifecycleLock) {
            if (state == LifecycleState.STOPPED) {
                true
            } else {
                state = LifecycleState.STOPPING
                false
            }
        }
        if (alreadyStopped) return

        shutdownCoordinator.close()
        synchronized(lifecycleLock) { state = LifecycleState.STOPPED }
    }

    private suspend fun cleanupStaleUploads() {
        try {
            appContext.cacheDir.listFiles { file ->
                file.name.startsWith("nezha_upload_") && file.name.endsWith(".tmp")
            }?.forEach { staleFile ->
                Logger.i("AgentRuntime: 清理残留临时文件")
                staleFile.delete()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            Logger.e("AgentRuntime: 清理残留上传临时文件失败", exception)
        }
    }

    private enum class LifecycleState {
        NEW,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED
    }

    private companion object {
        const val KEEP_ALIVE_CLOSE_TIMEOUT_MILLIS = 750L
    }
}
