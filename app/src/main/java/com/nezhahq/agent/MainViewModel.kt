package com.nezhahq.agent

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nezhahq.agent.collector.GpuCollector
import com.nezhahq.agent.collector.SystemInfoCollector
import com.nezhahq.agent.collector.SystemStateCollector
import com.nezhahq.agent.core.config.ConfigRepository
import com.nezhahq.agent.core.config.ConfigurationSnapshot
import com.nezhahq.agent.core.config.StorageStatus
import com.nezhahq.agent.core.config.loadConfigurationSnapshot
import com.nezhahq.agent.core.config.resolveEditedValue
import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.ConnectionDraft
import com.nezhahq.agent.core.model.KeepAliveSettings
import com.nezhahq.agent.core.model.RemoteCapability
import com.nezhahq.agent.core.model.SimulatedDeviceConfig
import com.nezhahq.agent.core.model.SimulatorDraft
import com.nezhahq.agent.core.platform.VpnTrafficCompatibility
import com.nezhahq.agent.grpc.GrpcConnectionState
import com.nezhahq.agent.service.AgentService
import com.nezhahq.agent.simulator.GrpcSimulatedDeviceReporter
import com.nezhahq.agent.simulator.SimulatedDeviceLoop
import com.nezhahq.agent.ui.ClipboardConfigParser
import com.nezhahq.agent.ui.ParsedUuid
import com.nezhahq.agent.ui.UiEvent
import com.nezhahq.agent.ui.UiEvents
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * 主界面 ViewModel（MVVM 架构核心）。
 *
 * ## 设计目标
 * 将 MainActivity 中原先 800+ 行的业务逻辑全部剥离到此 ViewModel：
 * - 配置字段的读写与持久化
 * - Shizuku 权限检测与请求
 * - gRPC 连接状态收集
 * - 即时测试采集逻辑
 * - 服务启停控制
 *
 * ## 旋转安全
 * ViewModel 绑定到 Activity 的 ViewModelStore，配置变更（屏幕旋转）时状态不丢失。
 *
 * ## 线程安全
 * 所有 IO/CPU 操作在 viewModelScope 中通过协程调度执行，UI 层仅观察 State。
 */
class MainViewModel(
    application: Application,
    private val repository: ConfigRepository
) : AndroidViewModel(application) {

    /**
     * 构造期显示的值：全部来自 [ConfigurationSnapshot.DEFAULTS]，一次存储读取都不做。
     *
     * ViewModel 由 `by viewModels()` 在主线程构造，而此刻后台线程很可能正握着存储锁做旧加密
     * 配置的迁移；从前这里连着 6 次同步读取，等于把整段迁移时长搬到了主线程上。真正的值由
     * [loadConfigurationOnce] 在就绪后异步填入。
     *
     * 这份默认值同时是「用户还没改过这个字段」的判据，见 [applyConfigurationSnapshot]。
     */
    private val defaults = ConfigurationSnapshot.DEFAULTS

    private val configurationReadiness = application.appContainer.configurationReadiness

    /**
     * 首次配置加载是否仍在进行。
     *
     * 加载结束后永久为 false —— 无论存储是可用、降级还是彻底不可用，都算「结束」。界面用它
     * 禁用输入控件与「启动服务」，ViewModel 自身也用它挡住每一个会在主线程触碰存储的入口：
     * 那些调用会阻塞在正在做 Keystore 迁移的存储锁上。
     */
    var isConfigLoading by mutableStateOf(true)
        private set

    /**
     * 加载期间的暂定值，首个快照到达时被真实状态替换。
     *
     * 不能先摆成 UNAVAILABLE：那会让冷启动闪现一条「配置存储不可用」的红色横幅，而此时根本
     * 还没有任何一次读取失败过。乐观取值的风险由 [isConfigLoading] 兜住 —— 所有依据本字段做
     * 出实际动作的入口在加载期一律拒绝执行。
     */
    var storageStatus by mutableStateOf(StorageStatus.READY)
        private set

    val isConfigStorageAvailable: Boolean
        get() = storageStatus.isUsable

    var isResettingConfigStorage by mutableStateOf(false)
        private set

    /** 加载完成前一律为 null：还没读过存储，就没有失败可以报告。 */
    var storageErrorMessage by mutableStateOf<String?>(null)
        private set

    // ══════════════════════════════════════════════════════════════════════════
    // 配置字段状态（Compose State，驱动 UI 重组）
    // ══════════════════════════════════════════════════════════════════════════

    /** 服务端 IP 或域名 */
    var server by mutableStateOf(defaults.connection.server)
    /** gRPC 端口 */
    var port by mutableStateOf(defaults.connection.port.toString())
    /** 客户端密钥 */
    var secret by mutableStateOf(defaults.connection.secret)
    /** 客户端 UUID */
    var uuid by mutableStateOf(defaults.connection.uuid)
    /** gRPC 传输层安全开关；默认开启，只有用户显式关闭时才允许明文。 */
    var useTls by mutableStateOf(defaults.connection.useTls)
    /** Root/Shizuku 高权限模式 */
    var rootMode by mutableStateOf(defaults.connection.rootMode)
    // ── 工具页设置 ──
    /** 后台音频保活 */
    var enableKeepAliveAudio by mutableStateOf(defaults.tools.audio)
        private set
    /** 像素级透明悬浮窗 */
    var enableFloatWindow by mutableStateOf(defaults.tools.overlay)
        private set
    /** 开机自启动 */
    var enableAutoStart by mutableStateOf(defaults.autoStart.enabled)
        private set
    /** VPN 流量兼容模式（部分无 Root/Shizuku 且 Android < 12 ROM 的兜底方案） */
    var enableVpnTraffic by mutableStateOf(defaults.tools.vpn)
        private set

    val isVpnTrafficCompatibilityAvailable: Boolean
        get() = VpnTrafficCompatibility.isSupported(Build.VERSION.SDK_INT)
    /** 远程 Shell 开关：同时控制 TaskType 4 命令与新建交互终端。 */
    var enableRemoteCommand by mutableStateOf(defaults.capabilities.shellEnabled)
        private set
    /** 远程文件管理开关：控制 TaskType 11 的浏览、下载与上传。 */
    var enableRemoteFileManager by mutableStateOf(defaults.capabilities.fileManagerEnabled)
        private set
    /** 内网穿透开关：控制 TaskType 9 的 TCP 转发。 */
    var enableRemoteNat by mutableStateOf(defaults.capabilities.natEnabled)
        private set

    var isConfigWriteInProgress by mutableStateOf(false)
        private set

    // ── 娱乐模拟设备上报 ──
    var simulatorServer by mutableStateOf(defaults.simulator.server)
    var simulatorPort by mutableStateOf(defaults.simulator.port.toString())
    var simulatorSecret by mutableStateOf(defaults.simulator.secret)
    var simulatorUseTls by mutableStateOf(defaults.simulator.useTls)
    var simulatorThreadCount by mutableStateOf(defaults.simulator.threadCount.toString())

    var simulatorRunning by mutableStateOf(false)
        private set
    var simulatorSuccessCount by mutableStateOf(0)
        private set
    var simulatorFailureCount by mutableStateOf(0)
        private set
    var simulatorActiveThreadCount by mutableStateOf(0)
        private set
    var simulatorLastStatus by mutableStateOf(SIMULATOR_IDLE_STATUS)
        private set

    private var simulatorJob: Job? = null
    private val simulatorLoop = SimulatedDeviceLoop(GrpcSimulatedDeviceReporter())

    /** 首次启动自启动授权弹窗 */
    var showAutoStartPrompt by mutableStateOf(false)
        private set


    // ══════════════════════════════════════════════════════════════════════════
    // Shizuku 权限状态
    // ══════════════════════════════════════════════════════════════════════════

    /** Shizuku 状态文本，驱动 UI 显示 */
    var shizukuStatusText by mutableStateOf("")

    // ══════════════════════════════════════════════════════════════════════════
    // gRPC 连接状态（来自 GrpcManager 的 StateFlow）
    // ══════════════════════════════════════════════════════════════════════════

    /** gRPC 连接状态 StateFlow，供 UI 层 collectAsState */
    val grpcConnectionState: StateFlow<GrpcConnectionState> =
        application.appContainer.connectionState.connectionState

    // ══════════════════════════════════════════════════════════════════════════
    // 即时测试采集
    // ══════════════════════════════════════════════════════════════════════════

    /** 即时测试结果文本（null = 隐藏弹窗） */
    var instantTestResult by mutableStateOf<String?>(null)
        private set

    /** 即时测试是否正在执行 */
    var isTestRunning by mutableStateOf(false)
        private set

    // ══════════════════════════════════════════════════════════════════════════
    // 一次性 UI 事件
    // ══════════════════════════════════════════════════════════════════════════

    private val uiEvents = UiEvents()

    /** Transient messages for the presenter to show; see [UiEvent]. */
    val events: Flow<UiEvent> = uiEvents.flow

    /**
     * Whether configuration may be edited right now.
     *
     * Collapses the still-loading, storage-usable and no-write-in-flight triple that the screens
     * would otherwise recombine at every control, where forgetting one part silently enables a dead
     * toggle — or, for the loading part, lets the user type into a form that is about to be filled
     * in underneath them.
     */
    val canEditConfig: Boolean
        get() = !isConfigLoading && isConfigStorageAvailable && !isConfigWriteInProgress

    // Last on purpose. viewModelScope runs on Dispatchers.Main.immediate, so this launch executes
    // inline up to its first real suspension; declared any earlier it could read state properties
    // that have not been initialized yet.
    init {
        loadConfigurationOnce()
    }

    private fun notify(text: String) = uiEvents.send(text)

    private fun notifyLong(text: String) = uiEvents.sendLong(text)

    // ══════════════════════════════════════════════════════════════════════════
    // 业务方法
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 智能解析面板安装脚本中的配置信息。
     *
     * 支持两种格式：
     * 1. 传统 flag 模式：`-s host:port -p secret --tls`
     * 2. 环境变量模式：`NZ_SERVER=host:port NZ_CLIENT_SECRET=xxx NZ_UUID=yyy NZ_TLS=true`
     */
    fun parseClipboardConfig() {
        if (isConfigWriteInProgress || isConfigLoading) return
        val context = getApplication<Application>()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val input = clipboard
            ?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            // 先截断再 toString()：解析要对整段输入连跑 8 个正则，剪贴板里若是几 MB 的日志或
            // 一整份 diff，光正则扫描就足以卡死界面，而复制一份同样大小的字符串也是白花。
            // 上限取 64 Ki 个字符：真实的面板安装命令是几百字节，整脚本也就几 KiB，这里留了
            // 三个数量级的余量，超出的部分不可能还属于同一条命令。
            ?.take(MAX_CLIPBOARD_CONFIG_CHARS)
            ?.toString()
            .orEmpty()

        if (input.isBlank()) {
            notify("剪贴板中没有可解析的安装脚本")
            return
        }

        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO) { ClipboardConfigParser.parse(input) }
            parsed.server?.let { server = it }
            parsed.port?.let { port = it }
            parsed.secret?.let { secret = it }
            parsed.useTls?.let { useTls = it }
            uuid = resolveUuid(parsed.uuid)

            notify("配置已解析完成")
        }
    }

    /**
     * Settles on the UUID to use after a paste.
     *
     * A script that named one wins. A script that declared an empty one is asking for a fresh UUID.
     * A script that said nothing leaves the configured UUID alone — unless there is nothing usable
     * there either, in which case one is generated so the user never has to supply it by hand.
     */
    private fun resolveUuid(parsed: ParsedUuid): String = when (parsed) {
        is ParsedUuid.Found -> parsed.value
        ParsedUuid.Placeholder -> newUuid()
        ParsedUuid.Absent ->
            if (uuid.isBlank() || uuid == "''" || uuid == "\"\"") newUuid() else uuid
    }

    fun setKeepAliveAudio(enabled: Boolean) {
        persistToolSettings(
            enableKeepAliveAudio = enabled,
            enableFloatWindow = enableFloatWindow,
            enableVpnTraffic = enableVpnTraffic,
            action = "保存后台音频设置",
            failureMessage = "后台音频设置保存失败"
        )
    }

    fun setFloatWindow(enabled: Boolean) {
        persistToolSettings(
            enableKeepAliveAudio = enableKeepAliveAudio,
            enableFloatWindow = enabled,
            enableVpnTraffic = enableVpnTraffic,
            action = "保存悬浮窗设置",
            failureMessage = "悬浮窗设置保存失败"
        )
    }

    fun setVpnTraffic(enabled: Boolean) {
        persistToolSettings(
            enableKeepAliveAudio = enableKeepAliveAudio,
            enableFloatWindow = enableFloatWindow,
            enableVpnTraffic = enabled,
            action = "保存 VPN 流量设置",
            failureMessage = "VPN 流量设置保存失败"
        )
    }

    /**
     * 保存配置并启动探针服务。
     *
     * 内部处理：
     * 1. UUID 清洗与持久化
     * 2. 电池优化白名单检测
     * 3. 前台服务启动
     *
     * @param notificationPermGranted 通知权限是否已授予（Android 13+ 专用）
     * @param requestNotificationPerm 请求通知权限的回调（若需要）
     */
    fun startAgent(
        notificationPermGranted: Boolean,
        requestNotificationPerm: (() -> Unit) -> Unit
    ) {
        val ctx = getApplication<Application>()
        if (!requireConfigStorage("启动探针")) return

        // Same rules the service validates with, so a saved configuration can always be started.
        AgentConfig.validationError(server = server, portText = port, secret = secret)?.let {
            notify(it)
            return
        }

        val cleanedUuid = uuid.trim().replace(Regex("^['\"]|['\"]$"), "")
        val uuidToSave = cleanedUuid.takeUnless { it.isBlank() || it == "\\" } ?: newUuid()
        val draftToSave = ConnectionDraft(
            server = server,
            port = port.trim().toInt(),
            secret = secret,
            uuid = uuidToSave,
            useTls = useTls,
            rootMode = rootMode
        )

        // 封装实际启动服务的 lambda
        val doLaunchService: () -> Unit = {
            // 电池优化白名单检测
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                val packageName = ctx.packageName
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    val batteryIntent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    ).apply { data = Uri.parse("package:$packageName") }
                    try {
                        batteryIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        ctx.startActivity(batteryIntent)
                        notifyLong("请在弹出的系统对话框中选择 '允许' 以保证后台保活")
                    } catch (e: Exception) {
                        try {
                            val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            fallback.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            ctx.startActivity(fallback)
                        } catch (_: Exception) { /* 兜底：忽略，不阻塞探针启动 */ }
                    }
                }
            }

            // 启动前台服务
            AgentService.startOrReload(ctx)
            notify("后台探针服务已启动")
            
            // 启动成功后，检查是否需要显示自启动授权弹窗
            checkAndShowAutoStartPrompt()
        }

        persistOnIo(
            action = "保存连接配置并启动探针",
            failureMessage = "连接配置保存失败，探针未启动",
            persistence = { repository.saveConnection(draftToSave) },
            onSuccess = {
                uuid = uuidToSave
                // Android 13+ 通知权限时序控制 only begins after durable persistence.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !notificationPermGranted
                ) {
                    requestNotificationPerm(doLaunchService)
                } else {
                    doLaunchService()
                }
            }
        )
    }

    /**
     * 停止探针服务。
     */
    fun stopAgent() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, AgentService::class.java)
        ctx.stopService(intent)
        notify("后台探针服务已停止")
    }

    fun startSimulator() {
        if (simulatorRunning) return

        if (!requireConfigStorage("启动模拟器")) return
        val trimmedServer = simulatorServer.trim()
        val trimmedSecret = simulatorSecret.trim()
        val validationError = SimulatedDeviceConfig.validationError(
            server = trimmedServer,
            portText = simulatorPort,
            secret = trimmedSecret,
            threadCountText = simulatorThreadCount
        )
        if (validationError != null) {
            notify(validationError)
            return
        }

        val parsedPort = simulatorPort.trim().toInt()
        val parsedThreadCount = simulatorThreadCount.trim().toInt()
        val simulatorTlsToSave = simulatorUseTls
        val config = SimulatedDeviceConfig(
            server = trimmedServer,
            port = parsedPort,
            secret = trimmedSecret,
            useTls = simulatorTlsToSave,
            threadCount = parsedThreadCount
        )

        persistOnIo(
            action = "保存模拟器配置并启动模拟器",
            failureMessage = "模拟器配置保存失败，模拟器未启动",
            persistence = {
                repository.saveSimulator(
                    SimulatorDraft(
                        server = trimmedServer,
                        port = parsedPort,
                        secret = trimmedSecret,
                        useTls = simulatorTlsToSave,
                        threadCount = parsedThreadCount
                    )
                )
            },
            onSuccess = {
                simulatorServer = trimmedServer
                simulatorPort = parsedPort.toString()
                simulatorSecret = trimmedSecret
                simulatorThreadCount = parsedThreadCount.toString()
                simulatorRunning = true
                simulatorSuccessCount = 0
                simulatorFailureCount = 0
                simulatorActiveThreadCount = parsedThreadCount
                simulatorLastStatus =
                    "模拟器已开启，$parsedThreadCount 个并发线程正在上报随机设备..."
                simulatorJob = viewModelScope.launch(Dispatchers.IO) {
                    try {
                        simulatorLoop.run(
                            config = config,
                            shouldContinue = { isActive },
                            onSuccess = {
                                withContext(Dispatchers.Main) {
                                    simulatorSuccessCount += 1
                                    simulatorLastStatus =
                                        "上次成功：第 $simulatorSuccessCount 台设备已收到状态回执"
                                }
                            },
                            onFailure = { throwable ->
                                withContext(Dispatchers.Main) {
                                    simulatorFailureCount += 1
                                    simulatorLastStatus =
                                        "上次失败：${throwable.toSimulatorMessage()}"
                                }
                            }
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (throwable: Throwable) {
                        withContext(Dispatchers.Main) {
                            simulatorRunning = false
                            simulatorLastStatus =
                                "模拟器异常停止：${throwable.toSimulatorMessage()}"
                        }
                    }
                }
                notify("娱乐模拟设备已开启")
            }
        )
    }

    fun stopSimulator() {
        if (!simulatorRunning && simulatorJob == null) {
            notify("娱乐模拟设备未开启")
            return
        }
        simulatorJob?.cancel()
        simulatorJob = null
        simulatorRunning = false
        simulatorActiveThreadCount = 0
        simulatorLastStatus =
            "模拟器已停止，本次会话成功 $simulatorSuccessCount 台，失败 $simulatorFailureCount 台"
        notify("娱乐模拟设备已关闭")
    }

    fun onUseTlsChanged(enabled: Boolean) {
        if (isConfigWriteInProgress) return
        useTls = enabled
    }

    /**
     * 检查是否需要显示首次开机自启动授权弹窗。
     *
     * 读取放在 IO 上：这条路径由「启动探针」触发，此时存储早已就绪，但一次 SharedPreferences
     * 提交仍会短暂持锁，没有理由让主线程去等它。
     */
    private fun checkAndShowAutoStartPrompt() {
        if (!isConfigStorageAvailable) return
        viewModelScope.launch {
            val (hasShownAutoStartPrompt, status) = withContext(Dispatchers.IO) {
                repository.loadAutoStartState().promptShown to repository.storageStatus()
            }
            storageStatus = status
            if (!isConfigStorageAvailable) {
                reportStorageProblem(CONFIG_STORAGE_UNAVAILABLE_MESSAGE)
                showAutoStartPrompt = false
                return@launch
            }
            if (!hasShownAutoStartPrompt) {
                showAutoStartPrompt = true
            }
        }
    }

    /**
     * 处理自启动授权弹窗结果
     */
    fun onAutoStartPromptResult(accepted: Boolean) {
        persistOnIo(
            action = "保存开机自启动设置",
            failureMessage = "开机自启动设置保存失败",
            persistence = { repository.saveAutoStartPromptResult(accepted) },
            onSuccess = {
                enableAutoStart = accepted
                showAutoStartPrompt = false
            },
            onFailure = { showAutoStartPrompt = false }
        )
    }

    /**
     * 工具页手动切换自启动开关
     */
    fun toggleAutoStart(enabled: Boolean, onSaved: () -> Unit = {}) {
        persistOnIo(
            action = "保存开机自启动设置",
            failureMessage = "开机自启动设置保存失败",
            persistence = { repository.saveAutoStart(enabled) },
            onSuccess = {
                enableAutoStart = enabled
                onSaved()
            }
        )
    }

    /**
     * 切换远程 Shell 执行开关。
     *
     * 此开关与 Root/Shizuku 模式完全独立，同时控制静默命令和交互终端；
     * Root 模式只决定已授权 Shell 能否提权。
     *
     * 持久化成功后立即通知运行中的服务重读授权，兑现界面上「关闭后新请求立即生效」的承诺。
     */
    fun toggleRemoteCommand(enabled: Boolean) {
        persistOnIo(
            action = "保存远程命令设置",
            failureMessage = "远程命令设置保存失败",
            persistence = { repository.saveRemoteCapability(RemoteCapability.SHELL, enabled) },
            onSuccess = {
                enableRemoteCommand = enabled
                AgentService.requestCapabilityRefreshIfRunning(getApplication())
            }
        )
    }

    /**
     * 切换远程文件管理开关（TaskType 11）。
     *
     * 本应用持有「所有文件访问」权限，开启后面板可读写设备上几乎全部文件。
     */
    fun toggleRemoteFileManager(enabled: Boolean) {
        persistOnIo(
            action = "保存文件管理设置",
            failureMessage = "文件管理设置保存失败",
            persistence = { repository.saveRemoteCapability(RemoteCapability.FILE_MANAGER, enabled) },
            onSuccess = {
                enableRemoteFileManager = enabled
                AgentService.requestCapabilityRefreshIfRunning(getApplication())
            }
        )
    }

    /** 切换内网穿透开关（TaskType 9）。 */
    fun toggleRemoteNat(enabled: Boolean) {
        persistOnIo(
            action = "保存内网穿透设置",
            failureMessage = "内网穿透设置保存失败",
            persistence = { repository.saveRemoteCapability(RemoteCapability.NAT, enabled) },
            onSuccess = {
                enableRemoteNat = enabled
                AgentService.requestCapabilityRefreshIfRunning(getApplication())
            }
        )
    }

    /** Clears all configuration on an IO thread and refreshes the UI with defaults. */
    fun resetConfigurationStorage() {
        if (isResettingConfigStorage) return
        // 加载期不重置：后台线程正握着存储锁做迁移，此刻还判断不出存储究竟坏没坏。这条分支
        // 实际到不了 —— 触发重置的错误卡片依赖 storageStatus，加载期它不会是 UNAVAILABLE。
        if (isConfigLoading) return
        val ctx = getApplication<Application>()
        isResettingConfigStorage = true
        simulatorJob?.cancel()
        simulatorJob = null
        simulatorRunning = false
        ctx.stopService(Intent(ctx, AgentService::class.java))
        viewModelScope.launch {
            // 重置与随后的状态读取合并在同一段 IO 里，免得回到主线程后再去抢一次存储锁。
            val (resetSucceeded, postResetStatus) = withContext(Dispatchers.IO) {
                repository.resetStorage() to repository.storageStatus()
            }
            isResettingConfigStorage = false
            storageStatus = postResetStatus
            if (!resetSucceeded || !isConfigStorageAvailable) {
                reportStorageProblem("配置存储重置失败，请稍后重试")
                return@launch
            }
            val snapshot = withContext(Dispatchers.IO) { loadSnapshotOrDefaults() }
            // 重置的语义就是「存储说了算」，所以这里整份覆盖，不做用户输入保护。
            applyConfigurationSnapshot(snapshot, SnapshotApplyMode.RELOAD_AFTER_RESET)
            storageStatus = snapshot.status
            if (!isConfigStorageAvailable) {
                reportStorageProblem("配置存储重置后读取失败，请稍后重试")
                return@launch
            }
            storageErrorMessage = null
            notifyLong("配置已重置，请重新填写连接信息")
        }
    }

    /**
     * 处理 Root/Shizuku 模式切换。

     *
     * 开启时自动检测 Shizuku 可用性并按需请求权限。
     */
    fun onRootModeChanged(enabled: Boolean, shizukuRequestCode: Int) {
        if (isConfigWriteInProgress) return
        if (enabled) {
            rootMode = true
            tryRequestShizukuPermission(shizukuRequestCode)
        } else {
            rootMode = false
            shizukuStatusText = ""
        }
    }

    /**
     * Shizuku 权限回调处理（由 Activity 桥接调用）。
     */
    fun onShizukuPermissionResult(granted: Boolean) {
        if (granted) {
            shizukuStatusText = "✅ Shizuku 已授权"
            notify("Shizuku 权限已授予，ADB Shell 模式可用")
        } else {
            shizukuStatusText = "❌ Shizuku 授权被拒绝"
            rootMode = false
            notify("Shizuku 权限被拒绝，已关闭高权限模式")
        }
    }

    /**
     * 执行即时测试采集。
     *
     * 在后台协程中调用 SystemStateCollector，将结果格式化后
     * 通过 `instantTestResult` 驱动 AlertDialog 展示。
     */
    fun runInstantTest() {
        if (isTestRunning) return
        isTestRunning = true
        // 先显示"正在采样"提示
        instantTestResult = "⏳ 正在采样网速中..."
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val ctx = getApplication<Application>()
                    val gpuCollector = GpuCollector()
                    val collector = SystemStateCollector(ctx, gpuCollector, Dispatchers.IO)
                    val isPrivileged = RootShell.isAuthorized()

                    // ── 第 1 次采样：建立差值基准 ──────────────────────────────
                    // 网络速度和 CPU 使用率均基于差值法（两次采样的变化量 / 时间差），
                    // 首次调用时无历史基准，差值必然为 0，因此需先"预热"一次。
                    collector.getState(isPrivileged)

                    // ── 等待采样间隔 ──────────────────────────────────────────
                    // 1.5 秒足以让 /proc/stat 和网络流量产生可测量的变化量，
                    // 同时对用户体验的等待时间也在可接受范围内。
                    delay(1500L)

                    // ── 第 2 次采样：获取真实差值数据 ──────────────────────────
                    val state = collector.getState(isPrivileged)
                    val hostInfo = SystemInfoCollector.getHostInfo(
                        context = ctx,
                        appVersion = "test",
                        gpuCollector = gpuCollector,
                        isRootMode = isPrivileged
                    )
                    // 从 CPU 显示名称中提取真实核心数
                    // CPU 名称格式为 "{SoC名称} {核心数} {Physical/Virtual} Core"
                    val cpuDisplayName = hostInfo.cpuList.firstOrNull() ?: "N/A"
                    val actualCoreCount = Regex("(\\d+)\\s+(?:Physical|Virtual)\\s+Core")
                        .find(cpuDisplayName)?.groupValues?.get(1) ?: "N/A"
                    buildString {
                        appendLine("═══ 采集结果预览 ═══")
                        appendLine("▸ CPU 使用率: ${"%.1f".format(state.cpu)}%")
                        appendLine("▸ CPU 核心数: $actualCoreCount")
                        appendLine("▸ CPU 名称: $cpuDisplayName")
                        appendLine("▸ Load: ${"%.2f".format(state.load1)} / ${"%.2f".format(state.load5)} / ${"%.2f".format(state.load15)}")
                        appendLine("▸ 内存已用: ${state.memUsed / 1024 / 1024} MB")
                        appendLine("▸ Swap 已用: ${state.swapUsed / 1024 / 1024} MB")
                        appendLine("▸ 磁盘已用: ${state.diskUsed / 1024 / 1024} MB")
                        appendLine("▸ 网络速度: ↓${state.netInSpeed / 1024} KB/s  ↑${state.netOutSpeed / 1024} KB/s")
                        appendLine("▸ TCP 连接: ${state.tcpConnCount}")
                        appendLine("▸ UDP 连接: ${state.udpConnCount}")
                        appendLine("▸ 进程数: ${state.processCount}")
                        appendLine("▸ 温度: ${state.temperaturesList.firstOrNull()?.let { "${it.name} ${it.temperature}°C" } ?: "N/A"}")
                        // GPU 信息
                        val gpuName = gpuCollector.getGpuNames().firstOrNull()
                        appendLine("▸ GPU: ${gpuName ?: "N/A"}")
                        val gpuUsages = state.gpuList
                        appendLine("▸ GPU 使用率: ${
                            if (gpuUsages.isNotEmpty()) "${"%.1f".format(gpuUsages.first())}%"
                            else "N/A（需 Root/Shizuku）"
                        }")
                        appendLine()
                        appendLine("═══ 权限状态 ═══")
                        appendLine("▸ Shell 会话: ${RootShell.getSessionType() ?: "无（普通模式）"}")
                        appendLine("▸ Shell 存活: ${if (RootShell.isAlive()) "✅" else "❌"}")
                    }
                }
                instantTestResult = result
            } catch (e: CancellationException) {
                // 取消不是采集失败：ViewModel 已销毁或本次采集被替换掉了，弹窗不该改写成错误。
                // 吞掉它还会让协程在被取消后继续往下跑。
                throw e
            } catch (e: Exception) {
                instantTestResult = "❌ 采集失败: ${e.message}"
            } finally {
                isTestRunning = false
            }
        }
    }

    /**
     * 关闭即时测试结果弹窗。
     */
    fun dismissTestResult() {
        instantTestResult = null
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 私有方法
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 尝试请求 Shizuku 权限。
     *
     * ## 逻辑流程
     * 1. 检查 Shizuku 服务是否存活
     * 2. 检查 Shizuku 版本
     * 3. 检查并申请权限
     */
    private fun tryRequestShizukuPermission(requestCode: Int) {
        try {
            if (!Shizuku.pingBinder()) {
                shizukuStatusText = "⚠️ Shizuku 未运行（可使用 Root 则忽略此提示）"
                notifyLong("Shizuku 未运行。如设备已 Root 可忽略此提示；\n否则请先安装并启动 Shizuku 应用。")
                return
            }

            if (Shizuku.isPreV11()) {
                shizukuStatusText = "⚠️ Shizuku 版本过低，不受支持"
                notifyLong("当前 Shizuku 版本过低，请升级到 v11 以上")
                return
            }

            when {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                    shizukuStatusText = "✅ Shizuku 已授权（UID=${Shizuku.getUid()}）"
                }
                Shizuku.shouldShowRequestPermissionRationale() -> {
                    shizukuStatusText = "❌ Shizuku 授权被永久拒绝，请在 Shizuku 应用中手动授权"
                    notifyLong("Shizuku 权限已被永久拒绝，请打开 Shizuku 应用，在「授权管理」中手动为本应用授权。")
                }
                else -> {
                    shizukuStatusText = "⏳ 等待 Shizuku 授权..."
                    Shizuku.requestPermission(requestCode)
                }
            }
        } catch (e: Exception) {
            shizukuStatusText = "⚠️ Shizuku 检测异常"
            notifyLong("Shizuku 检测失败：${e.message}\n如设备已 Root 可忽略此提示。")
        }
    }

    /**
     * The one main-thread storage touch left, and the gate that keeps it cheap.
     *
     * Refusing while [isConfigLoading] is what stops this call from taking the coordinator lock
     * during the legacy-store migration, which would block the main thread for its full duration.
     * Once the load has landed the lock is only ever held for a single SharedPreferences commit, so
     * asking synchronously here costs microseconds and keeps callers able to fail fast.
     */
    private fun requireConfigStorage(action: String): Boolean {
        if (isConfigLoading) {
            notify("$action 已推迟：配置仍在加载，请稍候")
            return false
        }
        storageStatus = repository.storageStatus()
        if (isConfigStorageAvailable) return true
        reportStorageProblem("$action 已拒绝：$CONFIG_STORAGE_UNAVAILABLE_MESSAGE")
        return false
    }

    private fun persistOnIo(
        action: String,
        failureMessage: String,
        persistence: () -> Result<Unit>,
        onSuccess: () -> Unit,
        onFailure: () -> Unit = {}
    ) {
        if (isConfigWriteInProgress) {
            onFailure()
            notify("配置正在保存，请稍候")
            return
        }
        if (!requireConfigStorage(action)) {
            onFailure()
            return
        }

        isConfigWriteInProgress = true
        viewModelScope.launch {
            val persisted = withContext(Dispatchers.IO) {
                persistence().isSuccess
            }
            isConfigWriteInProgress = false
            if (persisted) {
                storageStatus = repository.storageStatus()
                storageErrorMessage = null
                onSuccess()
            } else {
                onFailure()
                reportStorageWriteFailure(failureMessage)
            }
        }
    }

    private fun persistToolSettings(
        enableKeepAliveAudio: Boolean,
        enableFloatWindow: Boolean,
        enableVpnTraffic: Boolean,
        action: String,
        failureMessage: String
    ) {
        // normalize() 已经把不受支持的设备压成 false，落到 UI 状态的动作统一留给 onSuccess：
        // 与本函数其余字段一致，持久化成功之前不改动任何界面状态。
        val normalizedVpnSetting = VpnTrafficCompatibility.normalize(
            enabled = enableVpnTraffic,
            sdkInt = Build.VERSION.SDK_INT
        )

        persistOnIo(
            action = action,
            failureMessage = failureMessage,
            persistence = {
                repository.saveToolSettings(
                    KeepAliveSettings(
                        audio = enableKeepAliveAudio,
                        overlay = enableFloatWindow,
                        vpn = normalizedVpnSetting
                    )
                )
            },
            onSuccess = {
                this.enableKeepAliveAudio = enableKeepAliveAudio
                this.enableFloatWindow = enableFloatWindow
                this.enableVpnTraffic = normalizedVpnSetting
                showToolSettingSaved()
            }
        )
    }

    private fun showToolSettingSaved() {
        notifyLong("配置已保存，请在主页停止并重新启动探针以生效")
    }

    private fun reportStorageWriteFailure(message: String) {
        storageStatus = repository.storageStatus()
        reportStorageProblem("$message；配置存储不可用，请重置配置存储")
    }

    /**
     * The only way [storageErrorMessage] becomes non-null.
     *
     * Setting the field and telling the user were separate steps before, so the banner could show a
     * problem the user was never notified about, or outlive the condition that caused it.
     */
    private fun reportStorageProblem(message: String) {
        storageErrorMessage = message
        notifyLong(message)
    }

    /**
     * 首次加载：等后台初始化发出就绪信号，再把配置字段一次性填上。
     *
     * 全程不在主线程碰存储：等待只是挂起，读取跑在 IO 上，回到主线程时只剩赋值。
     *
     * 除了协程被取消（意味着 ViewModel 已销毁，没人再看这些状态），没有任何一条路径能绕过
     * 末尾的 `isConfigLoading = false`：等待与读取都不会抛出异常，读取失败由
     * [loadSnapshotOrDefaults] 降级成 [ConfigurationSnapshot.DEFAULTS]，其 status 为
     * UNAVAILABLE，于是界面得到的是「存储不可用」这个可操作的结论，而不是一个永远转圈的
     * 加载态。
     */
    private fun loadConfigurationOnce() {
        viewModelScope.launch {
            if (configurationReadiness.awaitWithin(CONFIG_READY_WARNING_TIMEOUT_MS) == null) {
                // 超时不等于失败：某些设备上构建 Keystore 主密钥本来就很慢。只提示一次，然后
                // 接着等 —— 提前放行会把主线程重新放回那把仍被握着的锁前面，比多等一会儿糟。
                notifyLong(CONFIG_STORAGE_SLOW_MESSAGE)
                configurationReadiness.await()
            }

            val snapshot = withContext(Dispatchers.IO) { loadSnapshotOrDefaults() }

            applyConfigurationSnapshot(snapshot, SnapshotApplyMode.INITIAL_LOAD)
            storageStatus = snapshot.status
            isConfigLoading = false
            if (isConfigStorageAvailable) {
                storageErrorMessage = null
            } else {
                reportStorageProblem(CONFIG_STORAGE_UNAVAILABLE_MESSAGE)
            }
        }
    }

    /** 读取失败一律降级成默认快照：它的 status 就是 UNAVAILABLE，正是界面该显示的结论。 */
    private fun loadSnapshotOrDefaults(): ConfigurationSnapshot = try {
        repository.loadConfigurationSnapshot()
    } catch (failure: Throwable) {
        Logger.e("MainViewModel: 读取配置快照失败", failure)
        ConfigurationSnapshot.DEFAULTS
    }

    private enum class SnapshotApplyMode {
        /**
         * 首次加载：只填用户还没碰过的字段。
         *
         * 判据是「当前值仍等于 [ConfigurationSnapshot.DEFAULTS] 里的对应值」——界面本来就是
         * 用这份默认值渲染的，任何偏离都只可能来自用户输入。它看不出「改了又改回默认值」这
         * 一种情况，所以只是第二道防线：加载期 [canEditConfig] 为 false，输入控件本身就是
         * 禁用的。会话状态（模拟器计数、首启弹窗）与存储无关，此模式下原样保留。
         */
        INITIAL_LOAD,

        /** 重置之后重新载入：存储是唯一事实，连会话状态也一并归零。 */
        RELOAD_AFTER_RESET
    }

    /**
     * 把一份存储快照铺到界面字段上。
     *
     * 首次加载与重置后重载共用这里，是为了让「新增一个配置项」只需要改一处；从前重置走的是
     * 自己的一份填充代码，漏改一处就是一个只在重置后才复现的 bug。
     */
    private fun applyConfigurationSnapshot(
        snapshot: ConfigurationSnapshot,
        mode: SnapshotApplyMode
    ) {
        fun <T> pick(current: T, loaded: T, untouched: T): T =
            if (mode == SnapshotApplyMode.INITIAL_LOAD) {
                resolveEditedValue(current = current, loaded = loaded, untouched = untouched)
            } else {
                loaded
            }

        val connection = snapshot.connection
        val defaultConnection = defaults.connection
        server = pick(server, connection.server, defaultConnection.server)
        port = pick(port, connection.port.toString(), defaultConnection.port.toString())
        secret = pick(secret, connection.secret, defaultConnection.secret)
        uuid = pick(uuid, connection.uuid, defaultConnection.uuid)
        useTls = pick(useTls, connection.useTls, defaultConnection.useTls)
        rootMode = pick(rootMode, connection.rootMode, defaultConnection.rootMode)

        val tools = snapshot.tools
        val defaultTools = defaults.tools
        enableKeepAliveAudio = pick(enableKeepAliveAudio, tools.audio, defaultTools.audio)
        enableFloatWindow = pick(enableFloatWindow, tools.overlay, defaultTools.overlay)
        enableVpnTraffic = pick(enableVpnTraffic, tools.vpn, defaultTools.vpn)
        enableAutoStart = pick(
            enableAutoStart,
            snapshot.autoStart.enabled,
            defaults.autoStart.enabled
        )

        val capabilities = snapshot.capabilities
        val defaultCapabilities = defaults.capabilities
        enableRemoteCommand = pick(
            enableRemoteCommand,
            capabilities.shellEnabled,
            defaultCapabilities.shellEnabled
        )
        enableRemoteFileManager = pick(
            enableRemoteFileManager,
            capabilities.fileManagerEnabled,
            defaultCapabilities.fileManagerEnabled
        )
        enableRemoteNat = pick(
            enableRemoteNat,
            capabilities.natEnabled,
            defaultCapabilities.natEnabled
        )

        val simulator = snapshot.simulator
        val defaultSimulator = defaults.simulator
        simulatorServer = pick(simulatorServer, simulator.server, defaultSimulator.server)
        simulatorPort = pick(
            simulatorPort,
            simulator.port.toString(),
            defaultSimulator.port.toString()
        )
        simulatorSecret = pick(simulatorSecret, simulator.secret, defaultSimulator.secret)
        simulatorUseTls = pick(simulatorUseTls, simulator.useTls, defaultSimulator.useTls)
        simulatorThreadCount = pick(
            simulatorThreadCount,
            simulator.threadCount.toString(),
            defaultSimulator.threadCount.toString()
        )

        if (mode == SnapshotApplyMode.RELOAD_AFTER_RESET) {
            simulatorSuccessCount = 0
            simulatorFailureCount = 0
            simulatorActiveThreadCount = 0
            simulatorLastStatus = SIMULATOR_IDLE_STATUS
            showAutoStartPrompt = false
        }
    }

    override fun onCleared() {
        simulatorJob?.cancel()
        simulatorJob = null
        super.onCleared()
    }

    companion object {
        /** Supplies the storage-backed repository; the UI never constructs storage itself. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = checkNotNull(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                )
                MainViewModel(application, application.appContainer.configRepository)
            }
        }
    }
}

private const val CONFIG_STORAGE_UNAVAILABLE_MESSAGE =
    "配置存储不可用，连接信息不会写入；请重置配置存储"

private const val CONFIG_STORAGE_SLOW_MESSAGE =
    "配置存储打开较慢（可能正在迁移旧的加密配置），仍在等待，请稍候…"

/**
 * 等多久才提醒用户「加载得有点久」。
 *
 * 只是一次提示，不是放弃：初始化真正卡住时提前解除加载态，只会把主线程重新送回那把锁前面。
 * 取 15 秒 —— 长到足以覆盖低端设备上构建 Keystore 主密钥的正常耗时（秒级），短到用户还没
 * 开始怀疑应用已经假死。
 */
private const val CONFIG_READY_WARNING_TIMEOUT_MS = 15_000L

/** 见 [MainViewModel.parseClipboardConfig] 里的取值理由。 */
private const val MAX_CLIPBOARD_CONFIG_CHARS = 64 * 1024

private const val SIMULATOR_IDLE_STATUS = "未启动"

private fun newUuid(): String = java.util.UUID.randomUUID().toString()

private fun Throwable.toSimulatorMessage(): String {
    val message = generateSequence(this) { it.cause }
        .mapNotNull { it.message?.takeIf(String::isNotBlank) }
        .firstOrNull()
        ?: this::class.java.simpleName
    return message.replace('\n', ' ').take(120)
}
