package com.nezhahq.agent.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nezhahq.agent.core.model.SimulatedDeviceConfig
import com.nezhahq.agent.MainViewModel
import com.nezhahq.agent.R
import com.nezhahq.agent.util.PermissionChecker
import com.nezhahq.agent.util.PermissionChecker.PermissionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 工具页与配置页，以及它们共用的权限行和系统跳转工具。

@Composable
fun ToolsScreenContent(
    vm: MainViewModel,
    contentPadding: PaddingValues = PaddingValues()
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    // 权限刷新要读盘（getAllPermissionStatus 内部会读配置存储），必须在 IO 上执行，
    // 故需要一个协程作用域给下面几处“事件驱动的刷新”使用。
    val scope = rememberCoroutineScope()

    // ── 权限状态列表（响应式，自动驱动 UI 重组）──
    // 初值给空列表 + 未加载标记：组合期不再同步读盘。原实现会在 composition 期调用
    // getAllPermissionStatus → configRepository.loadAutoStartState()，那是一次会阻塞主线程的存储读取。
    var permissionList by remember {
        mutableStateOf<List<PermissionChecker.PermissionItem>>(emptyList())
    }
    var permissionsLoaded by remember { mutableStateOf(false) }

    // 统一的权限刷新入口：始终在 IO 线程读盘、回主线程回填 state。
    // 四处刷新（首次加载 / 返回前台 / 短信授权回调 / 自启开关保存后）都走这里，避免任何一处漏挪回主线程。
    val refreshPermissions: () -> Unit = {
        scope.launch {
            val latest = withContext(Dispatchers.IO) {
                PermissionChecker.getAllPermissionStatus(context)
            }
            permissionList = latest
            permissionsLoaded = true
        }
    }

    // 首次进入时异步加载一次。key = Unit：只在进入组合时触发，不因重组反复重启。
    LaunchedEffect(Unit) { refreshPermissions() }

    // ── 生命周期感知：从系统设置页返回时自动刷新权限状态 ──
    // 说明：此处仍用 androidx.compose.ui.platform.LocalLifecycleOwner。理应迁到
    // androidx.lifecycle.compose.LocalLifecycleOwner，但该符号需要 lifecycle-runtime-compose 2.8.0+，
    // 本项目锁定 lifecycle 2.7.0 且未依赖该构件，符号不存在，迁移会编译失败——故保留并 @Suppress。
    // （升级依赖属构建改动，超出本次 UI 修复范围。）
    @Suppress("DEPRECATION")
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── SMS 运行时权限请求器（唯一需要弹窗授权的权限）──
    val smsPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 刷新全部权限状态（同样在 IO 上执行）
        refreshPermissions()
        if (granted) {
            Toast.makeText(context, "短信权限已授予，可在终端中使用 @agent sms", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "短信权限被拒绝", Toast.LENGTH_SHORT).show()
        }
    }

    // ── B11：SMS 授权前的隐私披露弹窗 ──
    // @agent sms 会返回最近短信的发件人与正文（OTP 级数据），面板侧却只表现为“允许远程执行命令”一个开关。
    // 授权前先弹窗明确告知，用户确认后才真正发起系统权限请求。本次仅告知、不改行为。文案取自 strings.xml。
    var showSmsDisclosure by remember { mutableStateOf(false) }
    if (showSmsDisclosure) {
        AlertDialog(
            onDismissRequest = { showSmsDisclosure = false },
            title = { Text(stringResource(R.string.sms_permission_disclosure_title)) },
            text = { Text(stringResource(R.string.sms_permission_disclosure_message)) },
            confirmButton = {
                GlassButtonPrimary(onClick = {
                    showSmsDisclosure = false
                    smsPermLauncher.launch(Manifest.permission.READ_SMS)
                }) { Text(stringResource(R.string.sms_permission_disclosure_confirm)) }
            },
            dismissButton = {
                GlassButtonSecondary(onClick = { showSmsDisclosure = false }) {
                    Text(stringResource(R.string.sms_permission_disclosure_cancel))
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // See ConfigScreenContent: padding after verticalScroll keeps it inside the scroll.
            .verticalScroll(scrollState)
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = contentPadding.calculateTopPadding() + 16.dp,
                bottom = 16.dp
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("工具与设置", style = MaterialTheme.typography.headlineMedium)

        // ══════════════════════════════════════════════════════════════════
        // 娱乐模拟设备
        // ══════════════════════════════════════════════════════════════════
        EtherCard {
            Text("娱乐模拟设备", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "每个线程每秒上报一台随机设备，收到状态回执后立即断开。",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(12.dp))

            EtherTextField(
                value = vm.simulatorServer,
                onValueChange = { vm.simulatorServer = it },
                label = "模拟器服务端 IP 或域名",
                modifier = Modifier.fillMaxWidth(),
                enabled = !vm.isConfigWriteInProgress
            )
            Spacer(modifier = Modifier.height(12.dp))
            EtherTextField(
                value = vm.simulatorPort,
                onValueChange = { vm.simulatorPort = it },
                label = "模拟器 gRPC 端口",
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !vm.isConfigWriteInProgress
            )
            Spacer(modifier = Modifier.height(12.dp))
            EtherTextField(
                value = vm.simulatorSecret,
                onValueChange = { vm.simulatorSecret = it },
                label = "模拟器客户端密钥 (Secret)",
                modifier = Modifier.fillMaxWidth(),
                enabled = !vm.isConfigWriteInProgress
            )
            Spacer(modifier = Modifier.height(12.dp))
            EtherTextField(
                value = vm.simulatorThreadCount,
                onValueChange = { vm.simulatorThreadCount = it },
                label = "模拟器并发线程数 (1-${SimulatedDeviceConfig.MAX_THREAD_COUNT})",
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !vm.isConfigWriteInProgress
            )
            Spacer(modifier = Modifier.height(8.dp))
            EtherToggleRow(
                checked = vm.simulatorUseTls,
                onCheckedChange = { vm.simulatorUseTls = it },
                title = "使用 TLS 加密连接",
                description = "仅影响娱乐模拟设备，不会修改真实探针连接配置",
                highlightWhenChecked = false,
                enabled = !vm.isConfigWriteInProgress
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(SmoothCornerShape(16.dp))
                    .background(
                        if (vm.simulatorRunning) LgSuccess.copy(alpha = 0.08f)
                        else LgSurfaceContainerLow.copy(alpha = 0.7f)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                val displayedThreadCount = if (vm.simulatorRunning && vm.simulatorActiveThreadCount > 0) {
                    vm.simulatorActiveThreadCount
                } else {
                    vm.simulatorThreadCount
                        .trim()
                        .toIntOrNull()
                        ?.coerceIn(1, SimulatedDeviceConfig.MAX_THREAD_COUNT)
                        ?: SimulatedDeviceConfig.DEFAULT_THREAD_COUNT
                }
                Text(
                    text = if (vm.simulatorRunning) {
                        "运行中 · 并发 $displayedThreadCount · " +
                                "成功 ${vm.simulatorSuccessCount} 台 · " +
                                "失败 ${vm.simulatorFailureCount} 台"
                    } else {
                        "未运行 · 配置并发 $displayedThreadCount · " +
                                "成功 ${vm.simulatorSuccessCount} 台 · " +
                                "失败 ${vm.simulatorFailureCount} 台"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (vm.simulatorRunning) LgSuccess else LgOnSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = vm.simulatorLastStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = LgOnSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GlassButtonPrimary(
                    onClick = { vm.startSimulator() },
                    modifier = Modifier.weight(1f),
                    enabled = !vm.simulatorRunning && vm.canEditConfig
                ) { Text("开启", fontWeight = FontWeight.Bold) }
                GlassButtonSecondary(
                    onClick = { vm.stopSimulator() },
                    modifier = Modifier.weight(1f)
                ) { Text("关闭", fontWeight = FontWeight.Bold) }
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // 权限状态总览卡片
        // ══════════════════════════════════════════════════════════════════
        EtherCard {
            Text("权限状态总览", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "以下列出探针运行所需的各项权限状态.未授予权限可能导致部分功能不可用.请根据需要自行授予或拒绝.拒绝不影响基础功能.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (!permissionsLoaded) {
                // 权限状态尚在 IO 线程加载中，先显示加载态；组合期不再阻塞读盘。
                Row(
                    modifier = Modifier.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = LgPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "正在检测权限状态…",
                        style = MaterialTheme.typography.bodySmall,
                        color = LgOnSurfaceVariant
                    )
                }
            }

            permissionList.forEach { item ->
                Spacer(modifier = Modifier.height(4.dp))
                PermissionStatusRow(
                    item = item,
                    // 仅“应用内本地开关”（开机自启）受配置可写状态约束；其余跳系统页/弹授权的项不受限。
                    actionEnabled = !item.key.isLocalToggle || vm.canEditConfig,
                    onAction = {
                        // 根据权限类型执行不同的授权动作。
                        // when 对 PermissionKey 穷举、无 else：日后新增权限项若漏了分支会编译失败，
                        // 而不是像旧的字面量 when 那样静默变成“按钮点了没反应”。
                        when (item.key) {
                            // B11：短信不直接拉起系统授权，先弹隐私披露，用户确认后再请求。
                            PermissionKey.SMS -> showSmsDisclosure = true
                            PermissionKey.USAGE_STATS -> {
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
                            PermissionKey.ACCESSIBILITY -> safeStartActivity(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            PermissionKey.OVERLAY -> safeStartActivity(context, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            })
                            PermissionKey.BATTERY -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    safeStartActivity(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    })
                                }
                            }
                            PermissionKey.NOTIFICATION -> {
                                safeStartActivity(context, Intent().apply {
                                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                })
                            }
                            PermissionKey.AUTO_START -> {
                                vm.toggleAutoStart(!item.granted) {
                                    // 保存成功回调在主线程，刷新仍走统一的 IO 入口。
                                    refreshPermissions()
                                }
                            }
                            PermissionKey.STORAGE -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    safeStartActivity(context, Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    ))
                                } else {
                                    safeStartActivity(context, Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}")
                                    ))
                                }
                            }
                        }
                    }
                )
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // 系统设置快捷入口
        // ══════════════════════════════════════════════════════════════════
        EtherCard {
            Text("系统设置快捷入口", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            GlassButtonSecondary(
                onClick = {
                    safeStartActivity(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("打开开发者选项") }
        }

        // ══════════════════════════════════════════════════════════════════
        // 保活增强
        // ══════════════════════════════════════════════════════════════════
        EtherCard {
            Text("保活增强", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            // 后台音频
            EtherToggleRow(
                checked = vm.enableKeepAliveAudio,
                onCheckedChange = vm::setKeepAliveAudio,
                title = "允许后台播放微弱音频",
                description = "发送极其微弱的次声波骗过部分系统的静音检测，防止杀后台（需重启服务生效）",
                enabled = vm.canEditConfig
            )

            // 悬浮窗
            EtherToggleRow(
                checked = vm.enableFloatWindow,
                onCheckedChange = vm::setFloatWindow,
                title = "开启像素级透明悬浮窗",
                description = "创建一个1x1不可见的悬浮窗来拉高进程优先级（需授予悬浮窗权限并重启服务生效）",
                enabled = vm.canEditConfig
            )

            // 开机自启动
            EtherToggleRow(
                checked = vm.enableAutoStart,
                onCheckedChange = { newValue ->
                    vm.toggleAutoStart(newValue)
                },
                title = "开机自启动",
                description = "设备重启后自动恢复探针后台服务，建议开启以防失联",
                enabled = vm.canEditConfig
            )
        }

        if (vm.isVpnTrafficCompatibilityAvailable) {
            // 复用外层已取得的 context，不再重复调用 LocalContext.current。
            val vpnAuthLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == ComponentActivity.RESULT_OK) {
                    vm.setVpnTraffic(true)
                } else {
                    Toast.makeText(context, "VPN 授权被拒绝", Toast.LENGTH_SHORT).show()
                }
            }

            EtherCard {
                Text("数据采集增强", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.Top) {
                    EtherSwitch(
                        checked = vm.enableVpnTraffic,
                        enabled = vm.canEditConfig,
                        onCheckedChange = { newValue ->
                            if (newValue) {
                                val prepareIntent = VpnService.prepare(context)
                                if (prepareIntent != null) {
                                    vpnAuthLauncher.launch(prepareIntent)
                                } else {
                                    vm.setVpnTraffic(true)
                                }
                            } else {
                                vm.setVpnTraffic(false)
                            }
                        }
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text("VPN 流量兼容模式", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "部分 ROM 在无 Root/Shizuku 时可能需要占位 VPN 才能取得系统流量（需重启服务生效）",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "⚠️ 该模式不代理数据包，但会占用系统 VPN 槽位，无法与其他 VPN 同时使用",
                            style = MaterialTheme.typography.bodySmall,
                            color = LgWarning
                        )
                    }
                }
            }
        }

        // 底部留白：为浮动 Pill 导航栏 + 系统导航栏留出足够滚动空间
        Spacer(modifier = Modifier.height(100.dp))
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 开关行组件（保活 / 数据增强通用）
// ══════════════════════════════════════════════════════════════════════════════

@Composable
internal fun EtherToggleRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    description: String,
    highlightWhenChecked: Boolean = true,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SmoothCornerShape(16.dp))
            .background(
                if (checked && highlightWhenChecked) LgCyan400.copy(alpha = 0.06f)
                else Color.Transparent
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        EtherSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 权限状态行组件
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 单行权限状态展示组件 — Liquid Glass 风格。
 * 显示权限名称、授权状态图标（✅ / ⚠️），以及未授权时的「去授权」按钮。
 */
@Composable
internal fun PermissionStatusRow(
    item: PermissionChecker.PermissionItem,
    actionEnabled: Boolean = true,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SmoothCornerShape(12.dp))
            .background(
                if (item.granted) LgSuccess.copy(alpha = 0.08f)
                else LgWarning.copy(alpha = 0.08f)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (item.granted) "✅" else "⚠️",
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium
            )
            // B11：对隐私敏感权限（当前仅短信）显示二级披露文案，说明授权后面板能读什么。
            item.description?.let { desc ->
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = LgOnSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (!item.granted) {
            TextButton(
                onClick = onAction,
                enabled = actionEnabled,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = LgCyan600)
            ) {
                Text(
                    // 本地开关（开机自启）文案为“启用”，其余跳系统页/弹授权的项为“去授权”。
                    text = if (item.key.isLocalToggle) "启用" else "去授权",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else {
            Text(
                text = "已授予",
                style = MaterialTheme.typography.bodySmall,
                color = LgSuccess
            )
        }
    }
}

/**
 * 安全启动系统设置 Activity 的辅助方法。
 * 自动添加 FLAG_ACTIVITY_NEW_TASK 标志，并 try-catch 兜底防止崩溃。
 */
internal fun safeStartActivity(context: Context, intent: Intent) {
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
internal fun safeStartActivityWithFallback(
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
