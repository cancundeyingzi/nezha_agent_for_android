package com.nezhahq.agent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nezhahq.agent.grpc.GrpcConnectionState
import rikka.shizuku.Shizuku

/**
 * Android 平台哪吒监控探针主界面（MVVM 架构，Material 3 Compose）。
 *
 * ## 架构说明
 * Activity 仅负责：
 * 1. 管理 Shizuku 权限回调监听器的生命周期（注册/注销）
 * 2. 将 Compose UI 树挂载到 setContent
 *
 * 所有业务逻辑和 UI 状态由 [MainViewModel] 管理，
 * 配置变更（屏幕旋转）时状态不丢失。
 */
class MainActivity : ComponentActivity() {

    /** Shizuku 权限请求码（任意唯一整数）。 */
    private val SHIZUKU_REQUEST_CODE = 19527

    /**
     * Compose 侧注册的 ViewModel 引用回调。
     * 当 Shizuku 权限结果回来时，转发给 ViewModel。
     */
    @Volatile
    private var viewModelCallback: ((Boolean) -> Unit)? = null

    /** Shizuku 权限回调：将结果转发给 ViewModel。 */
    private val shizukuPermResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                viewModelCallback?.invoke(granted)
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
                    // 获取 ViewModel（绑定到 Activity 的 ViewModelStore，旋转安全）
                    val vm: MainViewModel = viewModel()

                    // 注册 ViewModel 的 Shizuku 回调桥接
                    LaunchedEffect(Unit) {
                        viewModelCallback = { granted ->
                            vm.onShizukuPermissionResult(granted)
                        }
                    }

                    MainScreen(
                        vm = vm,
                        shizukuRequestCode = SHIZUKU_REQUEST_CODE
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermResultListener)
        viewModelCallback = null
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 主框架布局（底部导航 + 页面切换）
// ══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: MainViewModel,
    shizukuRequestCode: Int = 19527
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            // ── 自定义紧凑底部导航栏 ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CompactNavItem(
                            icon = Icons.Default.Home,
                            label = "配置",
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 }
                        )
                        CompactNavItem(
                            icon = Icons.Default.Build,
                            label = "工具",
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 }
                        )
                    }
                    Spacer(
                        modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets.statusBars
    ) { innerPadding ->
        Box(modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
        ) {
            // 两个页面始终保留在 Compose 树中，切换时状态不销毁
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (selectedTab == 0) 1f else 0f)
                    .alpha(if (selectedTab == 0) 1f else 0f)
            ) {
                ConfigScreenContent(vm, shizukuRequestCode)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (selectedTab == 1) 1f else 0f)
                    .alpha(if (selectedTab == 1) 1f else 0f)
            ) {
                ToolsScreenContent(vm)
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 紧凑底部导航按钮
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 极紧凑型底部导航按钮组件。
 * 图标(18dp)和文字(11sp)水平排列，整体高度约 26dp。
 */
@Composable
private fun CompactNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (selected)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    else
        Color.Transparent
    val contentColor = if (selected)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(18.dp),
            tint = contentColor
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = contentColor
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 工具页面
// ══════════════════════════════════════════════════════════════════════════════

@Composable
fun ToolsScreenContent(vm: MainViewModel) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // ── 权限状态列表（响应式，自动驱动 UI 重组）──
    var permissionList by remember {
        mutableStateOf(com.nezhahq.agent.util.PermissionChecker.getAllPermissionStatus(context))
    }

    // ── 生命周期感知：从系统设置页返回时自动刷新权限状态 ──
    @Suppress("DEPRECATION")
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionList = com.nezhahq.agent.util.PermissionChecker.getAllPermissionStatus(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── SMS 运行时权限请求器（唯一需要弹窗授权的权限）──
    val smsPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 刷新全部权限状态
        permissionList = com.nezhahq.agent.util.PermissionChecker.getAllPermissionStatus(context)
        if (granted) {
            Toast.makeText(context, "短信权限已授予，可在终端中使用 @agent sms", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "短信权限被拒绝", Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("实用工具与高级设置", style = MaterialTheme.typography.headlineMedium)

        // ══════════════════════════════════════════════════════════════════
        // 权限状态总览卡片
        // ══════════════════════════════════════════════════════════════════
        Card(shape = RoundedCornerShape(12.dp)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("权限状态总览", style = MaterialTheme.typography.titleMedium)
                Text(
                    "以下列出探针运行所需的各项权限状态。未授予的权限可能导致部分功能不可用。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                permissionList.forEach { item ->
                    PermissionStatusRow(
                        item = item,
                        onAction = {
                            // 根据权限类型执行不同的授权动作
                            when (item.key) {
                                "sms" -> smsPermLauncher.launch(Manifest.permission.READ_SMS)
                                "usage_stats" -> {
                                    // 优先尝试直接跳转到本应用的使用情况详情页
                                    // 部分设备/Android 版本支持 package data URI 直达
                                    val specificIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    if (!safeStartActivityWithFallback(
                                            context,
                                            specificIntent,
                                            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                        )) {
                                        Toast.makeText(context, "无法打开使用情况访问设置", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                "accessibility" -> safeStartActivity(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                "overlay" -> safeStartActivity(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                })
                                "battery" -> {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                        safeStartActivity(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        })
                                    }
                                }
                                "notification" -> {
                                    // 使用 ACTION_APP_NOTIFICATION_SETTINGS 直接跳转到本应用通知设置详情页
                                    safeStartActivity(context, Intent().apply {
                                        action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    })
                                }
                                "auto_start" -> {
                                    // 开机自启动是应用内开关，直接切换
                                    vm.toggleAutoStart(!item.granted)
                                    permissionList = com.nezhahq.agent.util.PermissionChecker.getAllPermissionStatus(context)
                                }
                            }
                        }
                    )
                }
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // 系统设置快捷入口（保留开发者选项等不属于权限检测的跳转）
        // ══════════════════════════════════════════════════════════════════
        Card(shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("系统设置快捷入口", style = MaterialTheme.typography.titleMedium)

                Button(
                    onClick = {
                        safeStartActivity(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("打开开发者选项") }
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // 保活增强
        // ══════════════════════════════════════════════════════════════════
        Card(shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("保活增强", style = MaterialTheme.typography.titleMedium)
                Row {
                    Switch(
                        checked = vm.enableKeepAliveAudio,
                        onCheckedChange = { newValue ->
                            vm.enableKeepAliveAudio = newValue
                            vm.saveToolSettings()
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
                        checked = vm.enableFloatWindow,
                        onCheckedChange = { newValue ->
                            vm.enableFloatWindow = newValue
                            vm.saveToolSettings()
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
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    Switch(
                        checked = vm.enableAutoStart,
                        onCheckedChange = { newValue ->
                            vm.toggleAutoStart(newValue)
                        }
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text("开机自启动")
                        Text(
                            "设备重启后自动恢复探针后台服务，建议开启以防失联",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 权限状态行组件
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 单行权限状态展示组件。
 *
 * 显示权限名称、授权状态图标（✅ / ⚠️），以及未授权时的「去授权」按钮。
 * 保持紧凑布局，与整体工具页风格一致。
 */
@Composable
private fun PermissionStatusRow(
    item: com.nezhahq.agent.util.PermissionChecker.PermissionItem,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (item.granted) Color(0xFF4CAF50).copy(alpha = 0.08f)
                else Color(0xFFFF9800).copy(alpha = 0.08f)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (item.granted) "✅" else "⚠️",
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (!item.granted) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(
                    text = if (item.key == "auto_start") "启用" else "去授权",
                    fontSize = 12.sp
                )
            }
        } else {
            Text(
                text = "已授予",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF4CAF50)
            )
        }
    }
}

/**
 * 安全启动系统设置 Activity 的辅助方法。
 * 自动添加 FLAG_ACTIVITY_NEW_TASK 标志，并 try-catch 兜底防止崩溃。
 */
private fun safeStartActivity(context: Context, intent: Intent) {
    try {
        intent.flags = intent.flags or Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "无法打开系统设置", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 带降级的安全跳转：优先尝试 [primary]（通常是应用级详情页），
 * 若设备不支持则自动降级到 [fallback]（通常是上级列表页）。
 *
 * @return true 表示至少有一个 Intent 成功启动
 */
private fun safeStartActivityWithFallback(
    context: Context,
    primary: Intent,
    fallback: Intent
): Boolean {
    return try {
        primary.flags = primary.flags or Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(primary)
        true
    } catch (_: Exception) {
        try {
            fallback.flags = fallback.flags or Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(fallback)
            true
        } catch (_: Exception) {
            false
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 配置页面
// ══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreenContent(
    vm: MainViewModel,
    shizukuRequestCode: Int = 19527
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // ── gRPC 连接状态收集 ──
    val grpcState by vm.grpcConnectionState.collectAsState()

    // ── 通知权限 Launcher（异步时序安全） ──
    var pendingServiceLaunch by remember { mutableStateOf<(() -> Unit)?>(null) }

    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(
                context,
                "通知权限被拒绝！状态栏保活通知无法显示，系统可能降低探针保活优先级。",
                Toast.LENGTH_LONG
            ).show()
        }
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

        // ── 首次启动自启动授权弹窗 ──
        if (vm.showAutoStartPrompt) {
            AlertDialog(
                onDismissRequest = { /* 强迫用户做出选择，不响应点击外部取消 */ },
                title = { Text("启用开机自启动？") },
                text = { Text("为了保证设备重启后探针不会离线，强烈建议您开启「开机自启动」功能。您稍后随时可以在「工具」页面修改此选项。") },
                confirmButton = {
                    Button(onClick = { vm.onAutoStartPromptResult(true) }) {
                        Text("启用")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { vm.onAutoStartPromptResult(false) }) {
                        Text("暂不启用")
                    }
                }
            )
        }

        // ── gRPC 连接状态指示器 ──
        GrpcStatusIndicator(grpcState)

        // ── 智能解析面板 ──
        Card(shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("智能解析一键配置", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = vm.clipboardInput,
                    onValueChange = { vm.clipboardInput = it },
                    label = { Text("请粘贴面板上的 curl 安装脚本/命令") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
                Button(
                    onClick = { vm.parseClipboardConfig() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("开始智能解析 (Smart Parse)") }
            }
        }

        // ── 连接设置 ──
        Card(shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("连接设置", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = vm.server, onValueChange = { vm.server = it },
                    label = { Text("服务端 IP 或域名") }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = vm.port, onValueChange = { vm.port = it },
                    label = { Text("gRPC 端口 (例如 5555)") }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = vm.secret, onValueChange = { vm.secret = it },
                    label = { Text("客户端密钥 (Secret)") }, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = vm.uuid, onValueChange = { vm.uuid = it },
                    label = { Text("客户端标识 (UUID)") }, modifier = Modifier.fillMaxWidth()
                )
                Row {
                    Checkbox(checked = vm.useTls, onCheckedChange = { vm.useTls = it })
                    Text("启用 TLS / SSL 加密传输", modifier = Modifier.padding(top = 12.dp))
                }
            }
        }

        // ── 高级特性（Root / Shizuku） ──
        Card(shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("高级特性", style = MaterialTheme.typography.titleMedium)
                Row {
                    Switch(
                        checked = vm.rootMode,
                        onCheckedChange = { newValue ->
                            vm.onRootModeChanged(newValue, shizukuRequestCode)
                        }
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text("Root / Shizuku 模式")
                        Text(
                            "仅限高级设备.请确保你完全了解 Root / Shizuku 的使用...",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (vm.shizukuStatusText.isNotEmpty()) {
                            Text(
                                vm.shizukuStatusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (vm.shizukuStatusText.startsWith("✅"))
                                    Color(0xFF4CAF50) else Color(0xFFFF9800)
                            )
                        }
                    }
                }
            }
        }

        // ── 即时测试按钮 ──
        Card(shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("数据采集测试", style = MaterialTheme.typography.titleMedium)
                Text(
                    "立即执行一次系统数据采集，验证当前权限模式是否生效。",
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = { vm.runInstantTest() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !vm.isTestRunning
                ) {
                    if (vm.isTestRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("采集中...")
                    } else {
                        Text("⚡ 即时测试")
                    }
                }
            }
        }

        // ── 即时测试结果弹窗 ──
        vm.instantTestResult?.let { result ->
            AlertDialog(
                onDismissRequest = { vm.dismissTestResult() },
                confirmButton = {
                    TextButton(onClick = { vm.dismissTestResult() }) {
                        Text("关闭")
                    }
                },
                title = { Text("采集结果预览") },
                text = {
                    Text(
                        result,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            )
        }

        // ── 守护进程启停控制 ──
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val notifGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                    } else true

                    vm.startAgent(
                        notificationPermGranted = notifGranted,
                        requestNotificationPerm = { doLaunch ->
                            pendingServiceLaunch = doLaunch
                            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    )
                },
                modifier = Modifier.weight(1f)
            ) { Text("启动探针") }

            OutlinedButton(
                onClick = { vm.stopAgent() },
                modifier = Modifier.weight(1f)
            ) { Text("停止探针") }
        }

        // ── 日志实时预览窗 ──
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
                    }) { Text("复制日志") }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .background(Color.Black)
                        .padding(8.dp)
                ) {
                    // 使用 LazyColumn 局部刷新日志列表，避免全量字符串拼接和全量重绘。
                    // 仅可视区域内的条目参与布局/绘制，性能提升一个数量级。
                    val lazyListState = rememberLazyListState()
                    LaunchedEffect(logs.size) {
                        if (logs.isNotEmpty()) {
                            lazyListState.animateScrollToItem(logs.size - 1)
                        }
                    }
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 使用 itemsIndexed 以索引为 key，
                        // 避免 indexOf 在存在重复日志时返回错误索引的问题
                        itemsIndexed(
                            items = logs,
                            key = { index, _ -> index }
                        ) { _, logLine ->
                            Text(
                                text = logLine,
                                color = Color.Green,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                // 单条日志不换行截断，保持终端风格
                                maxLines = 3,
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// gRPC 连接状态指示器
// ══════════════════════════════════════════════════════════════════════════════

/**
 * gRPC 连接状态实时指示器。
 *
 * 以圆点 + 文本形式展示当前连接状态，颜色随状态变化：
 * - 灰色：未连接
 * - 蓝色：连接中
 * - 绿色：已连接
 * - 橙色：重连中
 * - 红色：认证失败
 */
@Composable
private fun GrpcStatusIndicator(state: GrpcConnectionState) {
    val (statusText, statusColor) = when (state) {
        GrpcConnectionState.IDLE -> "⚪ 未连接" to Color(0xFF9E9E9E)
        GrpcConnectionState.CONNECTING -> "🔵 连接中..." to Color(0xFF2196F3)
        GrpcConnectionState.CONNECTED -> "🟢 已连接" to Color(0xFF4CAF50)
        GrpcConnectionState.RECONNECTING -> "🟠 重连中..." to Color(0xFFFF9800)
        GrpcConnectionState.AUTH_FAILED -> "🔴 认证失败" to Color(0xFFF44336)
    }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = statusColor.copy(alpha = 0.12f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "gRPC 连接状态",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = statusColor
            )
        }
    }
}
