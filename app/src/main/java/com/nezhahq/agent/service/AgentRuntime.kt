package com.nezhahq.agent.service

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.nezhahq.agent.collector.GeoIpCollector
import com.nezhahq.agent.collector.GpuCollector
import com.nezhahq.agent.collector.SystemInfoCollector
import com.nezhahq.agent.collector.SystemStateCollector
import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.core.task.RemoteCapabilityPolicy
import com.nezhahq.agent.core.task.TaskTypes
import com.nezhahq.agent.executor.FileManager
import com.nezhahq.agent.executor.NatManager
import com.nezhahq.agent.executor.TaskExecutor
import com.nezhahq.agent.executor.TerminalManager
import com.nezhahq.agent.grpc.GrpcConnection
import com.nezhahq.agent.grpc.GrpcConnectionState
import com.nezhahq.agent.grpc.GrpcTransportMode
import com.nezhahq.agent.service.keepalive.KeepAliveController
import com.nezhahq.agent.util.DashboardSessionWatchdog
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import java.security.cert.CertificateException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import proto.Nezha.Task
import proto.Nezha.TaskResult
import proto.NezhaServiceGrpcKt.NezhaServiceCoroutineStub

internal fun interface RuntimeNetworkRegistration : AutoCloseable {
    override fun close()
}

internal fun interface RuntimeNetworkRegistrar {
    fun register(onNetworkAvailable: () -> Unit): RuntimeNetworkRegistration
}

internal class AndroidRuntimeNetworkRegistrar(context: Context) : RuntimeNetworkRegistrar {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

    override fun register(onNetworkAvailable: () -> Unit): RuntimeNetworkRegistration {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkAvailable()
        }
        connectivityManager.registerNetworkCallback(request, callback)
        return IdempotentNetworkRegistration {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }
}

private class IdempotentNetworkRegistration(
    private val unregister: () -> Unit
) : RuntimeNetworkRegistration {
    private val closed = AtomicBoolean()

    override fun close() {
        if (closed.compareAndSet(false, true)) unregister()
    }
}

internal fun interface RuntimeKeepAliveFactory {
    fun create(context: Context, scope: CoroutineScope): KeepAliveController
}

/**
 * One immutable configuration snapshot's complete business runtime.
 */
internal class AgentRuntime(
    context: Context,
    private val config: AgentConfig,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val statusSink: (GrpcConnectionState, String) -> Unit,
    // No default: a connection built here would have no state sink, and the UI would never learn
    // that the runtime went idle. The composition point wires it to the observable holder.
    private val grpcConnection: GrpcConnection,
    private val networkRegistrar: RuntimeNetworkRegistrar =
        AndroidRuntimeNetworkRegistrar(context.applicationContext),
    keepAliveFactory: RuntimeKeepAliveFactory =
        RuntimeKeepAliveFactory(KeepAliveController::create)
) : AgentRuntimeHandle {
    private val appContext = context.applicationContext
    private val lifecycleLock = Any()
    private val supervisor = SupervisorJob()
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Logger.e("AgentRuntime: 协程未捕获异常（已兜底，不闪退）", throwable)
    }
    private val scope = CoroutineScope(ioDispatcher + supervisor + exceptionHandler)
    private val keepAliveController = keepAliveFactory.create(appContext, scope)
    private val gpuCollector = GpuCollector()
    private val stateCollector = SystemStateCollector(appContext, gpuCollector, ioDispatcher)
    private val capabilities = AtomicReference(config.remoteCapabilities)
    private val reconnectBackoff = ReconnectBackoffPolicy()

    /**
     * The single in-flight GeoIP report triggered by a network change.
     *
     * `onAvailable` arrives on a system thread and fires again on every WiFi/cellular flip, so each
     * event atomically replaces the previous attempt instead of stacking another fetch-and-report
     * coroutine beside it. Only the newest network is worth reporting, and a switch that flaps
     * would otherwise leave several probes racing to describe networks that are already gone.
     */
    private val geoIpReportJob = AtomicReference<Job?>(null)

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
            supervisor.cancelAndJoin()
            connectionJob = null
            cleanupJob = null
        },
        closeNetwork = {
            runCatching { networkRegistration?.close() }
                .onFailure { Logger.e("AgentRuntime: 注销 NetworkCallback 异常", it) }
            networkRegistration = null
        },
        closeKeepAlive = {
            val closed = keepAliveController.closeWithin(KEEP_ALIVE_CLOSE_TIMEOUT_MILLIS)
            if (!closed) Logger.e("AgentRuntime: 保活资源清理超时，继续其余运行时清理")
        },
        closeGrpc = grpcConnection::close
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
        networkRegistration = networkRegistrar.register(::reportGeoIpAfterNetworkChange)
        connectionJob = scope.launch { runConnectionLoop() }
        synchronized(lifecycleLock) { state = LifecycleState.RUNNING }
    }

    override suspend fun updateCapabilities(capabilities: RemoteCapabilities) {
        this.capabilities.set(capabilities)
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
                Logger.i("AgentRuntime: 清理残留临时文件: ${staleFile.name}")
                staleFile.delete()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            Logger.e("AgentRuntime: 清理残留上传临时文件失败", exception)
        }
    }

    private fun reportGeoIpAfterNetworkChange() {
        Logger.i("Network dynamically available, polling full GeoIP metadata...")
        // Started lazily so the swap below is complete before the attempt can run: the previous one
        // is always cancelled first, and two probes can never overlap however fast the system
        // thread delivers callbacks.
        val attempt = scope.launch(start = CoroutineStart.LAZY) { reportGeoIpNow() }
        geoIpReportJob.getAndSet(attempt)?.cancel()
        // Clears only its own entry, so a newer attempt installed in the meantime is not dropped.
        attempt.invokeOnCompletion { geoIpReportJob.compareAndSet(attempt, null) }
        attempt.start()
    }

    private suspend fun reportGeoIpNow() {
        try {
            val geoIp = DashboardSessionWatchdog.callWithin(
                DashboardSessionWatchdog.HANDSHAKE_TIMEOUT_MS,
                "NetworkCallback FetchGeoIP"
            ) {
                GeoIpCollector.fetchGeoIP()
            }
            val stub = grpcConnection.stub
            if (geoIp != null && stub != null) {
                DashboardSessionWatchdog.callWithin(
                    DashboardSessionWatchdog.HANDSHAKE_TIMEOUT_MS,
                    "NetworkCallback ReportGeoIP"
                ) {
                    stub.reportGeoIP(geoIp)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            Logger.e("GeoIP 上报失败（将在下次连接成功后重试）", exception)
        }
    }

    /**
     * Reconnects until cancelled, and never exits for any other reason.
     *
     * Anything that escapes this loop leaves the runtime looking healthy while it is doing nothing:
     * the service stays in the foreground, its notification stays up, and `isRunning` stays true,
     * so nothing retries and the agent is offline until the user notices. That includes a failure
     * inside [handleConnectionFailure] itself, which is why the backoff is inside the guard rather
     * than beside it, and an [Error] such as OutOfMemoryError, which is logged and retried rather
     * than allowed to end the loop silently.
     */
    private suspend fun runConnectionLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                showConnectingStatus()
                val stub = grpcConnection.stub ?: error("gRPC channel is not initialized.")
                reportInitialDashboardInfo(stub)
                val connectionMarked = AtomicBoolean(false)
                coroutineScope {
                    launch { handleSystemStateStream(stub, connectionMarked) }
                    launch { handleTaskStream(stub) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                try {
                    handleConnectionFailure(failure)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (secondary: Throwable) {
                    Logger.e("AgentRuntime: 连接失败处理自身异常，仍将继续重连", secondary)
                    // Draws from the same schedule, so a handler that keeps failing backs off too
                    // rather than spinning at whatever the first tier happens to be.
                    delay(reconnectBackoff.nextDelayMillis())
                }
            }
        }
    }

    private suspend fun reportInitialDashboardInfo(stub: NezhaServiceCoroutineStub) {
        val hostInfo = SystemInfoCollector.getHostInfo(
            context = appContext,
            appVersion = getAppVersionName(),
            gpuCollector = gpuCollector,
            isRootMode = RootShell.isAuthorized()
        )
        DashboardSessionWatchdog.callWithin(
            DashboardSessionWatchdog.HANDSHAKE_TIMEOUT_MS,
            "ReportSystemInfo2"
        ) {
            stub.reportSystemInfo2(hostInfo)
        }
        // Bounded like every other handshake step. Probing four endpoints can otherwise run for
        // forty seconds, during which neither the state stream nor the task stream has started and
        // the dashboard shows the device as offline.
        val geoIp = DashboardSessionWatchdog.callWithin(
            DashboardSessionWatchdog.HANDSHAKE_TIMEOUT_MS,
            "FetchGeoIP"
        ) {
            GeoIpCollector.fetchGeoIP()
        }
        geoIp?.let {
            DashboardSessionWatchdog.callWithin(
                DashboardSessionWatchdog.HANDSHAKE_TIMEOUT_MS,
                "ReportGeoIP"
            ) {
                stub.reportGeoIP(it)
            }
        }
    }

    private suspend fun handleSystemStateStream(
        stub: NezhaServiceCoroutineStub,
        connectionMarked: AtomicBoolean
    ) = coroutineScope {
        val stateFlow = flow {
            while (currentCoroutineContext().isActive) {
                emit(stateCollector.getState(RootShell.isAuthorized()))
                delay(DashboardSessionWatchdog.STATE_REPORT_INTERVAL_MS)
            }
        }
        val receipts = stub.reportSystemState(stateFlow).produceIn(this)
        try {
            while (isActive) {
                DashboardSessionWatchdog.receiveWithin(
                    receipts,
                    DashboardSessionWatchdog.STATE_RECEIPT_TIMEOUT_MS,
                    "ReportSystemState receipt"
                )
                if (connectionMarked.compareAndSet(false, true)) {
                    // The first receipt is the only proof the session works end to end: a rebuilt
                    // channel proves nothing, and resetting on that instead would hand a Dashboard
                    // that accepts connections and then drops them the flat five-second retry the
                    // backoff exists to replace.
                    reconnectBackoff.reset()
                    showConnectedStatus()
                }
            }
        } finally {
            receipts.cancel()
        }
    }

    /**
     * Owns everything the task side of a connection needs, and outlives any single requestTask
     * stream.
     *
     * The workers, both channels, the session registry and every live stream session belong to this
     * scope; the requestTask stream itself belongs to a shorter-lived one inside
     * [consumeWithIdleRestart]. That split is the whole point: the stream may be replaced without
     * a terminal, NAT tunnel or file transfer noticing, and — because this used to be a sibling of
     * the state stream under one non-supervising scope — without the metrics heartbeat going down
     * with it. Real stream failures still propagate and rebuild the connection.
     *
     * A result already taken out of the result channel by the cancelled collector is lost when the
     * stream is replaced. That is a task result the Dashboard will not see, accepted because the
     * deadline only expires after minutes of total silence, which is also minutes of idle workers.
     */
    private suspend fun handleTaskStream(stub: NezhaServiceCoroutineStub): Unit = coroutineScope {
        val results = Channel<TaskResult>(TASK_RESULT_BUFFER_CAPACITY)
        val shortTasks = Channel<Task>(SHORT_TASK_QUEUE_CAPACITY)
        val streamSessions = createStreamSessionRegistry()
        val sessionScope = this
        val workers = List(SHORT_TASK_WORKER_COUNT) {
            launch {
                for (task in shortTasks) executeShortTask(task, results)
            }
        }
        try {
            consumeWithIdleRestart(
                idleTimeoutMillis = DashboardSessionWatchdog.TASK_IDLE_TIMEOUT_MS,
                streamName = "RequestTask stream",
                openStream = {
                    stub.requestTask(results.receiveAsFlow())
                        .buffer(TASK_INPUT_BUFFER_CAPACITY)
                        .produceIn(this)
                },
                onIdleRestart = {
                    Logger.i("AgentRuntime: 任务流长时间空闲，仅重建任务流，连接与会话保持不变")
                }
            ) { task ->
                routeIncomingTask(sessionScope, stub, task, shortTasks, results, streamSessions)
            }
        } finally {
            workers.forEach(Job::cancel)
            shortTasks.close()
            results.close()
        }
    }

    private fun createStreamSessionRegistry() = StreamSessionRegistry(
        maxTotal = MAX_STREAM_SESSIONS,
        maxByTaskType = mapOf(
            TaskTypes.TERMINAL to MAX_TERMINAL_SESSIONS,
            TaskTypes.NAT to MAX_NAT_SESSIONS,
            TaskTypes.FILE_MANAGER to MAX_FILE_MANAGER_SESSIONS
        )
    )

    /**
     * [sessionScope] is passed explicitly rather than taken as a receiver because which scope a
     * stream session lands in is load-bearing: it must be the one that survives a task-stream
     * replacement, never the stream's own.
     */
    private suspend fun routeIncomingTask(
        sessionScope: CoroutineScope,
        stub: NezhaServiceCoroutineStub,
        task: Task,
        shortTasks: SendChannel<Task>,
        results: SendChannel<TaskResult>,
        streamSessions: StreamSessionRegistry
    ) {
        RemoteCapabilityPolicy.denialReason(task.type, capabilities.get())?.let { reason ->
            reportTaskFailure(task, reason, results)
            return
        }
        if (task.type in TaskTypes.STREAM_TASKS) {
            launchStreamTask(sessionScope, stub, task, results, streamSessions)
        } else {
            enqueueShortTaskWithBackpressure(task, shortTasks, results)
        }
    }

    /**
     * Nothing suspends between admission and [StreamSessionLease] ownership.
     *
     * `tryAcquire` hands back a lease that only the completion callback can release, so a
     * cancellation landing in a gap between the two would strip the session's slot from the
     * registry for the lifetime of the connection. Keep the acquire, the launch and
     * `invokeOnCompletion` free of suspension points.
     */
    private suspend fun launchStreamTask(
        sessionScope: CoroutineScope,
        stub: NezhaServiceCoroutineStub,
        task: Task,
        results: SendChannel<TaskResult>,
        streamSessions: StreamSessionRegistry
    ) {
        val request = try {
            parseStreamTask(task)
        } catch (exception: Exception) {
            reportTaskFailure(
                task,
                "Invalid stream task: ${exception.message ?: "unknown error"}",
                results
            )
            return
        }
        val lease = when (val admission = streamSessions.tryAcquire(task.type, request.streamId)) {
            is StreamSessionAdmission.Accepted -> admission.lease
            is StreamSessionAdmission.Rejected -> {
                reportTaskFailure(task, admission.reason, results)
                return
            }
        }
        val session = when (request) {
            is StreamTaskRequest.Terminal -> sessionScope.launchTerminalSession(stub, request)
            is StreamTaskRequest.Nat -> sessionScope.launchNatSession(stub, request)
            is StreamTaskRequest.FileManager ->
                sessionScope.launchFileManagerSession(stub, request)
        }
        session.invokeOnCompletion { lease.close() }
    }

    private fun parseStreamTask(task: Task): StreamTaskRequest {
        val json = JSONObject(task.data)
        val streamId = json.getString("StreamID")
        require(streamId.isNotBlank()) { "StreamID must not be blank." }
        require(streamId.toByteArray(Charsets.UTF_8).size <= MAX_STREAM_ID_BYTES) {
            "StreamID exceeds the $MAX_STREAM_ID_BYTES-byte limit."
        }
        return when (task.type) {
            TaskTypes.TERMINAL -> StreamTaskRequest.Terminal(task, streamId)
            TaskTypes.NAT -> {
                val host = json.getString("Host")
                require(host.isNotBlank()) { "NAT Host must not be blank." }
                require(host.toByteArray(Charsets.UTF_8).size <= MAX_NAT_HOST_BYTES) {
                    "NAT Host exceeds the $MAX_NAT_HOST_BYTES-byte limit."
                }
                StreamTaskRequest.Nat(task, streamId, host)
            }
            TaskTypes.FILE_MANAGER -> StreamTaskRequest.FileManager(task, streamId)
            else -> error("Task type ${task.type} is not a stream task.")
        }
    }

    private fun CoroutineScope.launchTerminalSession(
        stub: NezhaServiceCoroutineStub,
        request: StreamTaskRequest.Terminal
    ) = launch {
        runStreamTask("终端", request.task) {
            TerminalManager(appContext, stub, request.streamId).run()
        }
    }

    private fun CoroutineScope.launchNatSession(
        stub: NezhaServiceCoroutineStub,
        request: StreamTaskRequest.Nat
    ) = launch {
        runStreamTask("NAT 内网穿透", request.task) {
            NatManager(stub, request.streamId, request.host).run()
        }
    }

    private fun CoroutineScope.launchFileManagerSession(
        stub: NezhaServiceCoroutineStub,
        request: StreamTaskRequest.FileManager
    ) = launch {
        runStreamTask("文件管理器", request.task) {
            FileManager(appContext, stub, request.streamId).run()
        }
    }

    private suspend fun runStreamTask(
        taskName: String,
        task: Task,
        block: suspend () -> Unit
    ) {
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            Logger.e("$taskName 任务执行失败 (TaskID=${task.id}, Type=${task.type})", exception)
        }
    }

    private suspend fun executeShortTask(task: Task, results: SendChannel<TaskResult>) {
        val result = TaskExecutor.executeTask(task, capabilities = capabilities.get())
        results.send(result)
    }

    private suspend fun reportTaskFailure(
        task: Task,
        message: String,
        results: SendChannel<TaskResult>
    ) {
        results.send(buildFailedTaskResult(task, message))
    }

    private fun showConnectingStatus() {
        if (grpcConnection.transportMode == GrpcTransportMode.PLAINTEXT) {
            statusSink(GrpcConnectionState.PLAINTEXT_CONNECTING, "明文模式，正在连接...")
        } else {
            statusSink(GrpcConnectionState.CONNECTING, "正在连接...")
        }
    }

    private fun showConnectedStatus() {
        if (grpcConnection.transportMode == GrpcTransportMode.PLAINTEXT) {
            statusSink(GrpcConnectionState.PLAINTEXT_CONNECTED, "已连接到面板（明文传输）")
        } else {
            statusSink(GrpcConnectionState.CONNECTED, "已连接到面板")
        }
    }

    private fun showReconnectStatus() {
        if (grpcConnection.transportMode == GrpcTransportMode.PLAINTEXT) {
            statusSink(GrpcConnectionState.PLAINTEXT_RECONNECTING, "明文模式，正在重连...")
        } else {
            statusSink(GrpcConnectionState.RECONNECTING, "连接断开，正在重连...")
        }
    }

    private suspend fun handleConnectionFailure(failure: Throwable) {
        if (isAuthenticationFailure(failure)) {
            statusSink(GrpcConnectionState.AUTH_FAILED, "认证失败，请检查密钥和 UUID")
            Logger.e("AgentRuntime: 认证失败，请检查密钥和 UUID 配置", failure)
        } else {
            if (grpcConnection.transportMode == GrpcTransportMode.TLS &&
                isGenuineTlsFailure(failure)
            ) {
                Logger.e("Grpc: TLS 连接失败，将继续按 TLS 重试，不自动切换明文", failure)
            }
            showReconnectStatus()
            Logger.e("AgentRuntime: 连接中断，关闭通道后重试", failure)
        }
        grpcConnection.disconnect(preserveConnectionState = true)
        delay(reconnectBackoff.nextDelayMillis())
        runCatching { grpcConnection.connect() }
            .onFailure { Logger.e("AgentRuntime: gRPC 通道重建失败，将继续重试", it) }
    }

    private fun isAuthenticationFailure(throwable: Throwable): Boolean =
        throwable.causeSequence().any { cause ->
            when (cause) {
                is StatusException -> cause.status.code == Status.Code.UNAUTHENTICATED
                is StatusRuntimeException -> cause.status.code == Status.Code.UNAUTHENTICATED
                else -> cause.message?.contains("UNAUTHENTICATED", ignoreCase = true) == true
            }
        }

    private fun isGenuineTlsFailure(throwable: Throwable): Boolean =
        throwable.causeSequence().any { cause ->
            cause is SSLException ||
                cause is CertificateException ||
                cause.message?.let(::containsTlsFailureMarker) == true
        }

    private fun containsTlsFailureMarker(message: String): Boolean {
        val normalized = message.lowercase()
        return TLS_FAILURE_MARKERS.any { it in normalized }
    }

    private fun Throwable.causeSequence(): Sequence<Throwable> =
        generateSequence(this) { it.cause }

    private fun getAppVersionName(): String = try {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getPackageInfo(
                appContext.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }
        packageInfo.versionName ?: "unknown"
    } catch (exception: Exception) {
        Logger.e("AgentRuntime: 读取应用版本号失败", exception)
        "unknown"
    }

    private sealed interface StreamTaskRequest {
        val task: Task
        val streamId: String

        data class Terminal(
            override val task: Task,
            override val streamId: String
        ) : StreamTaskRequest

        data class Nat(
            override val task: Task,
            override val streamId: String,
            val host: String
        ) : StreamTaskRequest

        data class FileManager(
            override val task: Task,
            override val streamId: String
        ) : StreamTaskRequest
    }

    private enum class LifecycleState {
        NEW,
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED
    }

    private companion object {
        const val SHORT_TASK_WORKER_COUNT = 8
        const val SHORT_TASK_QUEUE_CAPACITY = 64
        const val TASK_INPUT_BUFFER_CAPACITY = 16
        const val TASK_RESULT_BUFFER_CAPACITY = 8
        const val MAX_STREAM_SESSIONS = 36
        const val MAX_TERMINAL_SESSIONS = 2
        const val MAX_NAT_SESSIONS = 32
        const val MAX_FILE_MANAGER_SESSIONS = 2
        const val MAX_STREAM_ID_BYTES = 256
        const val MAX_NAT_HOST_BYTES = 1024
        const val KEEP_ALIVE_CLOSE_TIMEOUT_MILLIS = 750L
        val TLS_FAILURE_MARKERS = listOf(
            "ssl",
            "tls",
            "handshake",
            "certificate",
            "trust anchor",
            "peer unverified",
            "hostname",
            "not an ssl/tls record",
            "unsupported or unrecognized ssl message",
            "unable to find valid certification path"
        )
    }
}
