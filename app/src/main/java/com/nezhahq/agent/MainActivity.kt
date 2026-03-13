package com.nezhahq.agent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.nezhahq.agent.service.AgentService
import com.nezhahq.agent.util.ConfigStore
import rikka.shizuku.Shizuku
import java.util.*

/**
 * Android 平台哪吒监控探针主界面 (采用 Material 3 标准 Compose 构建)
 *
 * ## Shizuku 集成说明
 * Activity 生命周期中管理 Shizuku 权限回调监听器的注册/注销：
 * - onCreate: 注册 [Shizuku.OnRequestPermissionResultListener]
 * - onDestroy: 注销监听器，避免内存泄漏
 */
class MainActivity : ComponentActivity() {

    /** Shizuku 权限请求码（任意唯一整数）。 */
    private val SHIZUKU_REQUEST_CODE = 19527

    /**
     * Shizuku 权限回调监听器的实例引用，用于在 onDestroy 中精确移除。
     *
     * 使用可空 var + lateinit 模式而非 lazy，因为需要引用 Compose 状态回调，
     * 但监听器注册发生在 onCreate 时，此时 Compose 尚未初始化。
     * 因此采用先注册一个"桥接"监听器，在回调内通过 volatile 变量通知 Compose 侧。
     */
    @Volatile
    private var shizukuPermissionGranted: Boolean = false

    @Volatile
    private var shizukuPermissionCallback: ((Boolean) -> Unit)? = null

    /** Shizuku 权限回调：将结果转发给 Compose 侧的回调函数。 */
    private val shizukuPermResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                shizukuPermissionGranted = granted
                // 转发给 Compose 侧注册的回调
                shizukuPermissionCallback?.invoke(granted)
            }
        }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 注册 Shizuku 权限回调监听器（必须在 Activity 生命周期内注册）
        Shizuku.addRequestPermissionResultListener(shizukuPermResultListener)

        setContent {
            MaterialTheme(
                // 适配暗色沉浸主题
                colorScheme = darkColorScheme(
                    primary = Color(0xFF03A9F4),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        shizukuRequestCode = SHIZUKU_REQUEST_CODE,
                        onShizukuCallbackRegistered = { callback ->
                            // 将 Compose 侧的回调注册到 Activity 属性，
                            // 这样 Shizuku 的原生回调可以桥接到 Compose 状态
                            shizukuPermissionCallback = callback
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注销 Shizuku 权限回调监听器，防止内存泄漏
        Shizuku.removeRequestPermissionResultListener(shizukuPermResultListener)
        shizukuPermissionCallback = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    shizukuRequestCode: Int = 19527,
    onShizukuCallbackRegistered: ((callback: (Boolean) -> Unit) -> Unit)? = null
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                windowInsets = WindowInsets.navigationBars
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("配置") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Build, contentDescription = "Tools") },
                    label = { Text("工具") }
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars
    ) { innerPadding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        ) {
            // Render both screens to keep state and avoid heavy recomposition when switching
            if (selectedTab == 0) {
                ConfigScreenContent(shizukuRequestCode, onShizukuCallbackRegistered)
            }
            if (selectedTab == 1) {
                ToolsScreenContent()
            }
        }
    }
}

@Composable
fun ToolsScreenContent() {
    val context = LocalContext.current
    var enableKeepAliveAudio by remember { mutableStateOf(ConfigStore.getEnableKeepAliveAudio(context)) }
    var enableFloatWindow by remember { mutableStateOf(ConfigStore.getEnableFloatWindow(context)) }

    val scrollState = rememberScrollState()

    // 辅助保存设置的函数
    val saveCurrentSettings = { audio: Boolean, floatWin: Boolean ->
        ConfigStore.saveConfig(
            context,
            ConfigStore.getServer(context),
            ConfigStore.getPort(context),
            ConfigStore.getSecret(context),
            ConfigStore.getUseTls(context),
            ConfigStore.getUuid(context),
            ConfigStore.getRootMode(context),
            audio,
            floatWin
        )
        Toast.makeText(context, "配置已保存，请在主页停止并重新启动探针以生效", Toast.LENGTH_LONG).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("实用工具与高级设置", style = MaterialTheme.typography.headlineMedium)

        Card(shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("系统权限快捷跳转", style = MaterialTheme.typography.titleMedium)
                
                Button(
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        } catch (e: Exception) {
                            Toast.makeText(context, "无法打开开发者选项", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("打开开发者选项")
                }
                
                Button(
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        } catch (e: Exception) {
                            Toast.makeText(context, "无法打开使用情况访问权限", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("打开使用情况访问权限")
                }

                Button(
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            })
                        } catch (e: Exception) {
                            Toast.makeText(context, "无法打开无障碍权限", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("打开无障碍权限")
                }

                Button(
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                data = Uri.parse("package:${context.packageName}")
                            })
                        } catch (e: Exception) {
                            Toast.makeText(context, "无法打开悬浮窗权限", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("打开悬浮窗权限")
                }
            }
        }

        Card(shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("保活增强", style = MaterialTheme.typography.titleMedium)
                Row {
                    Switch(
                        checked = enableKeepAliveAudio,
                        onCheckedChange = { newValue ->
                            enableKeepAliveAudio = newValue
                            saveCurrentSettings(newValue, enableFloatWindow)
                        }
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text("允许后台播放微弱音频")
                        Text(
                            "发送极其微弱的次声波骗过部分系统的静音检测，防止杀后台（需重启服务生效）",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    Switch(
                        checked = enableFloatWindow,
                        onCheckedChange = { newValue ->
                            enableFloatWindow = newValue
                            saveCurrentSettings(enableKeepAliveAudio, newValue)
                        }
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text("开启像素级透明悬浮窗")
                        Text(
                            "创建一个1x1不可见的悬浮窗来拉高进程优先级（需授予悬浮窗权限并重启服务生效）",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreenContent(
    shizukuRequestCode: Int = 19527,
    onShizukuCallbackRegistered: ((callback: (Boolean) -> Unit) -> Unit)? = null
) {
    val context = LocalContext.current
    var server by remember { mutableStateOf(ConfigStore.getServer(context)) }
    var port by remember { mutableStateOf(ConfigStore.getPort(context).toString()) }
    var secret by remember { mutableStateOf(ConfigStore.getSecret(context)) }
    var uuid by remember { mutableStateOf(ConfigStore.getUuid(context)) }
    var useTls by remember { mutableStateOf(ConfigStore.getUseTls(context)) }
    var rootMode by remember { mutableStateOf(ConfigStore.getRootMode(context)) }
    var clipboardInput by remember { mutableStateOf("") }

    val scrollState = rememberScrollState()

    // ── Shizuku 状态文本（仅用于 UI 展示）────────────────────────────────
    var shizukuStatusText by remember { mutableStateOf("") }

    // ── 注册 Shizuku 权限回调的 Compose 端处理器 ─────────────────────────
    // 当 Shizuku 权限结果返回时，更新 UI 状态
    LaunchedEffect(Unit) {
        onShizukuCallbackRegistered?.invoke { granted ->
            if (granted) {
                shizukuStatusText = "✅ Shizuku 已授权"
                Toast.makeText(context, "Shizuku 权限已授予，ADB Shell 模式可用", Toast.LENGTH_SHORT).show()
            } else {
                shizukuStatusText = "❌ Shizuku 授权被拒绝"
                rootMode = false  // 权限被拒绝，关闭开关
                Toast.makeText(context, "Shizuku 权限被拒绝，已关闭高权限模式", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Android 13 (TIRAMISU, API 33)+ 通知权限运行时申请 Launcher ─────────────
    // Manifest 中已声明 POST_NOTIFICATIONS，但 Android 13+ 还需要运行时动态申请才生效。
    // 需在 Composable 作用域顶层创建（不能在 onClick 内部创建）。
    //
    // 【修复：通知权限异步时序陷阱】
    // 权限申请（notificationPermLauncher.launch）是 **异步** 的。
    // 若在 launch() 后立即调用 startForegroundService()，系统在用户尚未点击"允许"时
    // 就收到前台服务启动请求，Android 13+ 会认为该前台服务"隐身"（无状态栏通知），
    // 大幅降低保活优先级，甚至可能抛出 ForegroundServiceStartNotAllowedException。
    //
    // 修复方案：将 startForegroundService() 封装为 doLaunchService lambda：
    //   - 权限已授予（granted == true）：在 onClick 内直接调用 doLaunchService()；
    //   - 权限未授予（granted == false）：将 lambda 存入 pendingServiceLaunch，
    //     等待权限回调完成（无论用户是否允许），再从回调内调用，确保时序正确。
    //
    // 使用 remember + mutableStateOf 暂存 pending lambda，
    // 避免直接在 Composable 闭包中捕获可变外部状态。
    var pendingServiceLaunch by remember { mutableStateOf<(() -> Unit)?>(null) }

    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            // 用户拒绝通知权限：提示保活级别降低，但服务仍可以运行
            Toast.makeText(
                context,
                "通知权限被拒绝！状态栏保活通知无法显示，系统可能降低探针保活优先级。",
                Toast.LENGTH_LONG
            ).show()
        }
        // 无论用户是否授权，权限流程已完成，此时可安全调用 startForegroundService。
        // 即使没有通知权限，服务本身仍可运行，只是保活优先级会降低。
        pendingServiceLaunch?.invoke()
        pendingServiceLaunch = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("哪吒探针", style = MaterialTheme.typography.headlineMedium)

        // 一键解析面板的安装指令
        Card(shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("智能解析一键配置", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = clipboardInput,
                    onValueChange = { clipboardInput = it },
                    label = { Text("请粘贴面板上的 curl 安装脚本/命令") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
                Button(
                    onClick = {
                        // Legacy flag based params
                        val sMatch = Regex("-s\\s+([^:\\s]+):(\\d+)").find(clipboardInput)
                        if (sMatch != null) {
                            server = sMatch.groupValues[1]
                            port = sMatch.groupValues[2]
                        } else {
                            val sMatch2 = Regex("-s\\s+([^\\s:]+)").find(clipboardInput)
                            if (sMatch2 != null) server = sMatch2.groupValues[1]
                        }

                        val pMatch = Regex("-p\\s+([^\\s]+)").find(clipboardInput)
                        if (pMatch != null) secret = pMatch.groupValues[1]

                        val tlsMatch = Regex("--tls").containsMatchIn(clipboardInput)
                        if (tlsMatch) useTls = true

                        // New Dashboard Script logic (Environment variable based mapping)
                        val envServerMatch = Regex("NZ_SERVER=([^:\\s]+):(\\d+)").find(clipboardInput)
                        if (envServerMatch != null) {
                            server = envServerMatch.groupValues[1]
                            port = envServerMatch.groupValues[2]
                        }
                        val envSecretMatch = Regex("NZ_CLIENT_SECRET=([^\\s]+)").find(clipboardInput)
                        if (envSecretMatch != null) secret = envSecretMatch.groupValues[1]

                        val envUuidMatch = Regex("NZ_UUID=([^\\s]+)").find(clipboardInput)
                        if (envUuidMatch != null) {
                            val parsedUuid = envUuidMatch.groupValues[1].replace(Regex("^['\"]|['\"]$"), "")
                            if (parsedUuid.isNotBlank() && parsedUuid != "\\") {
                                uuid = parsedUuid
                            } else {
                                uuid = java.util.UUID.randomUUID().toString()
                            }
                        } else if (uuid.isBlank() || uuid == "''" || uuid == "\"\"") {
                            uuid = java.util.UUID.randomUUID().toString()
                        }

                        val envTlsMatch = Regex("NZ_TLS=true").containsMatchIn(clipboardInput)
                        if (envTlsMatch) useTls = true

                        Toast.makeText(context, "配置已解析完成", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("开始智能解析 (Smart Parse)")
                }
            }
        }

        // 常规连接设置
        Card(shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("连接设置", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = server, onValueChange = { server = it },
                    label = { Text("服务端 IP 或域名") }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it },
                    label = { Text("gRPC 端口 (例如 5555)") }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = secret, onValueChange = { secret = it },
                    label = { Text("客户端密钥 (Secret)") }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = uuid, onValueChange = { uuid = it },
                    label = { Text("客户端标识 (UUID)") }, modifier = Modifier.fillMaxWidth()
                )
                Row {
                    Checkbox(checked = useTls, onCheckedChange = { useTls = it })
                    Text("启用 TLS / SSL 加密传输", modifier = Modifier.padding(top = 12.dp))
                }
            }
        }

        // 高级权限特性（Root / Shizuku 模式）
        Card(shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("高级特性", style = MaterialTheme.typography.titleMedium)
                Row {
                    Switch(
                        checked = rootMode,
                        onCheckedChange = { newValue ->
                            if (newValue) {
                                // ── 用户开启高权限模式：检测 Shizuku 可用性并按需请求权限 ──
                                // 策略：
                                // 1. 无论是否有 Root，先开启开关（Root 在 RootShell 里自动回退）
                                // 2. 如果 Shizuku 服务在运行但未授权，主动弹出授权弹窗
                                //    → 方便用户在没有 Root 的设备上也能使用高权限功能
                                // 3. 如果 Shizuku 未运行，仍然允许开启（RootShell 会尝试 su）
                                rootMode = true
                                tryRequestShizukuPermission(
                                    context = context,
                                    requestCode = shizukuRequestCode,
                                    onStatusUpdate = { status -> shizukuStatusText = status }
                                )
                            } else {
                                rootMode = false
                                shizukuStatusText = ""
                            }
                        }
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text("Root / Shizuku 模式")
                        Text(
                            "仅限高级设备.请确保你完全了解 Root / Shizuku 的使用...",
                            style = MaterialTheme.typography.bodySmall
                        )
                        // 显示 Shizuku 状态反馈
                        if (shizukuStatusText.isNotEmpty()) {
                            Text(
                                shizukuStatusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (shizukuStatusText.startsWith("✅"))
                                    Color(0xFF4CAF50) else Color(0xFFFF9800)
                            )
                        }
                    }
                }
            }
        }

        // 守护进程启停控制
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val p = port.toIntOrNull() ?: 5555
                    
                    // Clean up potential invalid UUIDs from user input or bad smart parse
                    uuid = uuid.trim().replace(Regex("^['\"]|['\"]$"), "")
                    if (uuid.isBlank() || uuid == "\\") {
                        uuid = java.util.UUID.randomUUID().toString()
                    }
                    
                    // 持久化加密存储密钥以及特权设置
                    val currentAudioSetting = ConfigStore.getEnableKeepAliveAudio(context)
                    ConfigStore.saveConfig(context, server, p, secret, useTls, uuid, rootMode, currentAudioSetting)

                    // ── 将"实际启动服务"封装为 lambda，待权限确认后安全调用 ──────────
                    // 【修复：通知权限异步时序陷阱】
                    // 使用 doLaunchService lambda 封装所有启动逻辑：
                    //   ① 电池优化白名单检测（需在权限确认后执行）
                    //   ② startForegroundService / startService 调用
                    // 这样无论权限已授/未授，真正的服务启动都发生在权限流程完成之后。
                    val doLaunchService: () -> Unit = {
                        // Android 6.0 (M) 以上才有 Doze 模式与电池优化白名单机制
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val powerManager =
                                context.getSystemService(Context.POWER_SERVICE) as PowerManager
                            val packageName = context.packageName
                            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                                // 应用未在白名单中：跳转系统设置，引导用户手动授权
                                val batteryIntent = Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                ).apply { data = Uri.parse("package:$packageName") }
                                try {
                                    context.startActivity(batteryIntent)
                                    Toast.makeText(
                                        context,
                                        "请在弹出的系统对话框中选择 '允许' 以保证后台保活",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } catch (e: Exception) {
                                    // 少数定制 ROM 可能不支持此 Intent，回退到电池设置页
                                    try {
                                        context.startActivity(
                                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        )
                                    } catch (_: Exception) {
                                        // 兜底：忽略，不阻塞探针启动
                                    }
                                }
                            }
                        }

                        // 适配 Android O 以上严格的前台服务启动规则
                        val intent = Intent(context, AgentService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                        Toast.makeText(context, "后台探针服务已启动", Toast.LENGTH_SHORT).show()
                    }

                    // ── Android 13 (API 33)+ 通知权限检测与时序控制 ───────────────────
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED

                        if (granted) {
                            // 已授权：可以安全地立即调用 startForegroundService
                            doLaunchService()
                        } else {
                            // 未授权：将启动逻辑存入 pendingServiceLaunch，
                            // 等权限回调（notificationPermLauncher）完成后再触发
                            pendingServiceLaunch = doLaunchService
                            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    } else {
                        // Android 12 及以下无需 POST_NOTIFICATIONS 权限，直接启动
                        doLaunchService()
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("启动探针") }

            OutlinedButton(
                onClick = {
                    val intent = Intent(context, AgentService::class.java)
                    context.stopService(intent)
                    Toast.makeText(context, "后台探针服务已停止", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) { Text("停止探针") }
        }

        // 日志实时预览窗
        val logs by com.nezhahq.agent.util.Logger.logs.collectAsState()

        Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("底层调试日志 (LogViewer)", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText(
                            "logs",
                            com.nezhahq.agent.util.Logger.getLogString()
                        )
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("复制日志")
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .background(Color.Black)
                        .padding(8.dp)
                ) {
                    val logScrollState = rememberScrollState()
                    LaunchedEffect(logs.size) {
                        logScrollState.animateScrollTo(logScrollState.maxValue)
                    }
                    Text(
                        logs.joinToString("\n"),
                        color = Color.Green,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.verticalScroll(logScrollState)
                    )
                }
            }
        }
    }
}

/**
 * 尝试请求 Shizuku 权限。
 *
 * ## 逻辑流程
 * 1. 检查 Shizuku 服务是否存活（`Shizuku.pingBinder()`）
 *    - 未运行 → 提示用户安装/启动 Shizuku，但不阻断开关操作（可能有 Root）
 * 2. 检查是否为旧版 Shizuku（`Shizuku.isPreV11()`）
 *    - 旧版 → 提示不支持
 * 3. 检查当前权限状态（`Shizuku.checkSelfPermission()`）
 *    - 已授权 → 直接显示状态
 *    - 未授权但可弹窗 → 调用 `Shizuku.requestPermission()`
 *    - "不再询问" → 提示手动授权
 *
 * @param context Android Context
 * @param requestCode Shizuku 权限请求码
 * @param onStatusUpdate Compose 状态更新回调
 */
private fun tryRequestShizukuPermission(
    context: Context,
    requestCode: Int,
    onStatusUpdate: (String) -> Unit
) {
    try {
        // Step 1: 检查 Shizuku 服务是否在运行
        if (!Shizuku.pingBinder()) {
            onStatusUpdate("⚠️ Shizuku 未运行（可使用 Root 则忽略此提示）")
            Toast.makeText(
                context,
                "Shizuku 未运行。如设备已 Root 可忽略此提示；\n否则请先安装并启动 Shizuku 应用。",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Step 2: 检查 Shizuku 版本
        if (Shizuku.isPreV11()) {
            onStatusUpdate("⚠️ Shizuku 版本过低，不受支持")
            Toast.makeText(context, "当前 Shizuku 版本过低，请升级到 v11 以上", Toast.LENGTH_LONG).show()
            return
        }

        // Step 3: 检查并申请权限
        when {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> {
                // 已有权限，无需请求
                onStatusUpdate("✅ Shizuku 已授权（UID=${Shizuku.getUid()}）")
            }
            Shizuku.shouldShowRequestPermissionRationale() -> {
                // 用户之前选择了"拒绝且不再询问"
                onStatusUpdate("❌ Shizuku 授权被永久拒绝，请在 Shizuku 应用中手动授权")
                Toast.makeText(
                    context,
                    "Shizuku 权限已被永久拒绝，请打开 Shizuku 应用，在「授权管理」中手动为本应用授权。",
                    Toast.LENGTH_LONG
                ).show()
            }
            else -> {
                // 弹出 Shizuku 权限授权弹窗
                onStatusUpdate("⏳ 等待 Shizuku 授权...")
                Shizuku.requestPermission(requestCode)
            }
        }
    } catch (e: Exception) {
        // Shizuku 未安装时可能抛出异常
        onStatusUpdate("⚠️ Shizuku 检测异常")
        Toast.makeText(
            context,
            "Shizuku 检测失败：${e.message}\n如设备已 Root 可忽略此提示。",
            Toast.LENGTH_LONG
        ).show()
    }
}
