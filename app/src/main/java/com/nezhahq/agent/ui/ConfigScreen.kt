package com.nezhahq.agent.ui
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nezhahq.agent.grpc.GrpcConnectionState
import com.nezhahq.agent.core.config.StorageStatus
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreenContent(
    vm: MainViewModel,
    shizukuRequestCode: Int,
    contentPadding: PaddingValues = PaddingValues()
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // ── gRPC 连接状态收集 ──
    val grpcState by vm.grpcConnectionState.collectAsState()

    // 首次配置从存储异步加载完成前为 true，完成后永久为 false（见 MainViewModel.isConfigLoading）。
    // 加载期间禁止编辑连接字段与“启动探针”：否则用户可能在字段被填充前就点启动，拿到一份空配置。
    val configLoading = vm.isConfigLoading
    // 连接字段的可用性：既要没有写入进行中，也要首次加载已完成。
    val fieldsEnabled = !vm.isConfigWriteInProgress && !configLoading

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
            // Padding after verticalScroll, so it belongs to the scrolled content: the list slides
            // under the status bar instead of being clipped at a permanently blank strip.
            .verticalScroll(scrollState)
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = contentPadding.calculateTopPadding() + 16.dp,
                bottom = 16.dp
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 标题 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "哪吒探针",
                style = MaterialTheme.typography.headlineMedium
            )
        }

        // ── 首次配置加载提示 ──
        // 明确告知用户：输入框与“启动探针”暂时禁用，是因为正在从存储读取已保存的配置。
        if (configLoading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(SmoothCornerShape(16.dp))
                    .background(LgWarning.copy(alpha = 0.08f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = LgPrimary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    "正在加载已保存的配置，请稍候…",
                    style = MaterialTheme.typography.bodySmall,
                    color = LgWarning
                )
            }
        }

        if (!vm.isConfigStorageAvailable) {
            EtherCard(
                modifier = Modifier.border(
                    width = 2.dp,
                    color = LgError.copy(alpha = 0.75f),
                    shape = LgPanelShape
                )
            ) {
                Text(
                    "配置存储不可用",
                    style = MaterialTheme.typography.titleMedium,
                    color = LgError
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    vm.storageErrorMessage
                        ?: "配置存储不可用，连接信息不会写入；请重置配置存储",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LgError
                )
                Spacer(modifier = Modifier.height(12.dp))
                GlassButtonPrimary(
                    onClick = { vm.resetConfigurationStorage() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !vm.isResettingConfigStorage
                ) {
                    if (vm.isResettingConfigStorage) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = LgPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在重置...", fontWeight = FontWeight.Bold)
                    } else {
                        Text("重置配置存储", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else if (vm.storageStatus == StorageStatus.LEGACY_UNREADABLE) {
            EtherCard(
                modifier = Modifier.border(
                    width = 1.dp,
                    color = LgWarning.copy(alpha = 0.7f),
                    shape = LgPanelShape
                )
            ) {
                Text(
                    "旧加密配置无法读取，已改用明文兼容存储。请核对并保存连接配置；此后重启不再依赖系统密钥库。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LgWarning
                )
            }
        }

        // ── 首次启动自启动授权弹窗（保持功能不变）──
        if (vm.showAutoStartPrompt) {
            AlertDialog(
                onDismissRequest = { /* 强迫用户做出选择，不响应点击外部取消 */ },
                title = { Text("启用开机自启动？") },
                text = { Text("为了保证设备重启后探针不会离线，强烈建议您开启「开机自启动」功能。您稍后随时可以在「工具」页面修改此选项。") },
                confirmButton = {
                    GlassButtonPrimary(
                        onClick = { vm.onAutoStartPromptResult(true) },
                        enabled = !vm.isConfigWriteInProgress
                    ) {
                        Text("启用")
                    }
                },
                dismissButton = {
                    GlassButtonSecondary(
                        onClick = { vm.onAutoStartPromptResult(false) },
                        enabled = !vm.isConfigWriteInProgress
                    ) {
                        Text("暂不启用")
                    }
                }
            )
        }

        // ── gRPC 连接状态指示器 ──
        GrpcStatusIndicator(grpcState)

        // ── 常用操作：统一放在连接设置上方 ──
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GlassButtonPrimary(
                    onClick = { vm.parseClipboardConfig() },
                    modifier = Modifier.weight(1f),
                    // 粘贴会写入连接字段，故与字段同受加载态约束。
                    enabled = fieldsEnabled
                ) {
                    Text("粘贴命令", fontWeight = FontWeight.Bold)
                }

                GlassButtonSecondary(
                    onClick = { vm.runInstantTest() },
                    modifier = Modifier.weight(1f),
                    enabled = !vm.isTestRunning
                ) {
                    if (vm.isTestRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = LgPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("采集中...", fontWeight = FontWeight.Bold)
                    } else {
                        Text("即时测试", fontWeight = FontWeight.Bold)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GlassButtonPrimary(
                    onClick = {
                        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
                    modifier = Modifier.weight(1f),
                    // 加载未完成时禁止启动，避免用空配置启动探针。
                    enabled = vm.canEditConfig && !configLoading
                ) {
                    Text("启动探针", fontWeight = FontWeight.Bold)
                }

                GlassButtonSecondary(
                    onClick = { vm.stopAgent() },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("停止探针", fontWeight = FontWeight.Bold)
                }
            }
        }

        // ── 连接设置 ──
        EtherCard {
            Text("连接设置", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "连接信息按原版兼容方式明文保存在应用私有目录；Root 或同等高权限程序可以读取密钥。",
                style = MaterialTheme.typography.bodySmall,
                color = LgWarning
            )
            Spacer(modifier = Modifier.height(12.dp))

            EtherTextField(
                value = vm.server, onValueChange = { vm.server = it },
                label = "服务端 IP 或域名", modifier = Modifier.fillMaxWidth(),
                enabled = fieldsEnabled
            )
            Spacer(modifier = Modifier.height(12.dp))
            EtherTextField(
                value = vm.port, onValueChange = { vm.port = it },
                label = "gRPC 端口 (例如 8008)", modifier = Modifier.fillMaxWidth(),
                enabled = fieldsEnabled
            )
            Spacer(modifier = Modifier.height(12.dp))
            EtherTextField(
                value = vm.secret, onValueChange = { vm.secret = it },
                label = "客户端密钥 (Secret)", modifier = Modifier.fillMaxWidth(),
                enabled = fieldsEnabled
            )
            Spacer(modifier = Modifier.height(12.dp))
            EtherTextField(
                value = vm.uuid, onValueChange = { vm.uuid = it },
                label = "客户端标识 (UUID)", modifier = Modifier.fillMaxWidth(),
                enabled = fieldsEnabled
            )
            Spacer(modifier = Modifier.height(12.dp))
            EtherToggleRow(
                checked = vm.useTls,
                onCheckedChange = { newValue -> vm.onUseTlsChanged(newValue) },
                title = "使用 TLS 加密连接",
                description = "关闭后将使用明文传输，仅适用于可信内网部署（需重启服务生效）",
                highlightWhenChecked = false,
                enabled = fieldsEnabled
            )
            if (!vm.useTls) {
                Text(
                    "⚠️ 明文模式将暴露密钥、UUID 和所有传输内容，仅在完全可信的内网中使用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = LgError,
                    modifier = Modifier.padding(start = 52.dp, top = 4.dp)
                )
            }
        }

        // ── 高级特性（Root / Shizuku） ──
        EtherCard {
            Text("高级特性", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.Top) {
                EtherSwitch(
                    checked = vm.rootMode,
                    onCheckedChange = { newValue ->
                        vm.onRootModeChanged(newValue, shizukuRequestCode)
                    },
                    enabled = !vm.isConfigWriteInProgress
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text("Root / Shizuku 模式", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "仅限高级设备.请确保你完全了解 Root / Shizuku 的使用...",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (vm.shizukuStatusText.isNotEmpty()) {
                        Text(
                            vm.shizukuStatusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (vm.shizukuStatusText.startsWith("✅"))
                                LgSuccess else LgWarning
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 远程 Shell 权限与 rootMode 解耦，需用户显式开启。
            EtherToggleRow(
                checked = vm.enableRemoteCommand,
                onCheckedChange = { newValue ->
                    vm.toggleRemoteCommand(newValue)
                },
                title = "允许面板远程执行命令",
                description = "允许 TaskType 4 命令和新建交互终端 Shell；关闭后新请求立即生效",
                enabled = vm.canEditConfig
            )
            if (vm.enableRemoteCommand) {
                Text(
                    "⚠️ 安全警告：开启后，面板可通过任务或交互终端执行任意 Shell 命令。" +
                            "请确保你完全信任面板管理员，否则可能带来数据泄露或设备损坏风险。",
                    style = MaterialTheme.typography.bodySmall,
                    color = LgError,
                    modifier = Modifier.padding(start = 52.dp, top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            EtherToggleRow(
                checked = vm.enableRemoteFileManager,
                onCheckedChange = { newValue ->
                    vm.toggleRemoteFileManager(newValue)
                },
                title = "允许面板远程管理文件",
                description = "允许 TaskType 11 浏览、下载和上传设备文件；关闭后新请求立即生效",
                enabled = vm.canEditConfig
            )
            if (vm.enableRemoteFileManager) {
                Text(
                    "⚠️ 安全警告：本应用持有「所有文件访问」权限，开启后面板可读写设备上几乎全部文件，" +
                            "包括照片、下载目录和其他应用的公共数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = LgError,
                    modifier = Modifier.padding(start = 52.dp, top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            EtherToggleRow(
                checked = vm.enableRemoteNat,
                onCheckedChange = { newValue ->
                    vm.toggleRemoteNat(newValue)
                },
                title = "允许面板内网穿透",
                description = "允许 TaskType 9 通过本机转发 TCP 流量；关闭后新请求立即生效",
                enabled = vm.canEditConfig
            )
            if (vm.enableRemoteNat) {
                Text(
                    "⚠️ 安全警告：开启后，面板可借本机访问其自身无法直达的地址，" +
                            "包括你所在的家庭或公司内网设备。",
                    style = MaterialTheme.typography.bodySmall,
                    color = LgError,
                    modifier = Modifier.padding(start = 52.dp, top = 4.dp)
                )
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

        // ── 日志实时预览窗 ──
        val logs by Logger.logs.collectAsState()

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 0.dp, end = 4.dp, bottom = 8.dp)
            ) {
                Text(
                    "控制台输出",
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = LgOnSurfaceVariant
                    )
                )
                TextButton(
                    onClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText(
                            "logs",
                            Logger.getLogString()
                        )
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("复制日志", fontSize = 11.sp, color = LgCyan600, fontWeight = FontWeight.Bold)
                }
            }

            // 深色终端风格日志窗口
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .shadow(16.dp, LgConsoleShape)
                    .clip(LgConsoleShape)
                    .background(Color(0xFF1A1F20))
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.05f),
                        shape = LgConsoleShape
                    )
                    .padding(LgConsolePadding)
            ) {
                // 使用 LazyColumn 局部刷新日志列表，避免全量字符串拼接和全量重绘。
                val lazyListState = rememberLazyListState()
                // 滚动到底不能只看 size：Logger 去重命中时只改最后一条的文本、size 不变，
                // 以 size 为 key 便不会滚到底。改为对“最后一条日志”敏感——LogEntry 是 data class，
                // 无论新增（新 id）还是去重更新（同 id、文本追加 ×N），lastOrNull() 都会不相等而重触发。
                val lastEntry = logs.lastOrNull()
                LaunchedEffect(lastEntry) {
                    if (logs.isNotEmpty()) {
                        lazyListState.animateScrollToItem(logs.lastIndex)
                    }
                }
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    // 以 LogEntry.id 作为稳定且唯一的 key：缓冲区满后 removeAt(0) 让下标整体前移时，
                    // 各条 id 不变，Compose 才能识别“同一条只是位置移动”，避免整列全量重绘。
                    items(
                        items = logs,
                        key = { it.id }
                    ) { entry ->
                        Text(
                            text = entry.text,
                            color = LgCyan400.copy(alpha = 0.8f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            // 单条日志不换行截断，保持终端风格
                            maxLines = 3,
                            modifier = Modifier.padding(vertical = 1.dp)
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
// gRPC 连接状态指示器 — Liquid Glass 风格
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
internal fun GrpcStatusIndicator(state: GrpcConnectionState) {
    val (statusText, statusColor) = when (state) {
        GrpcConnectionState.IDLE -> "⚪ 未连接" to Color(0xFF9E9E9E)
        GrpcConnectionState.CONNECTING -> "🔵 连接中..." to Color(0xFF2196F3)
        GrpcConnectionState.CONNECTED -> "🟢 已连接" to LgSuccess
        GrpcConnectionState.RECONNECTING -> "🟠 重连中..." to LgWarning
        GrpcConnectionState.AUTH_FAILED -> "🔴 认证失败" to LgError
        GrpcConnectionState.PLAINTEXT_CONNECTING -> "🟠 明文模式连接中..." to Color(0xFFFF5722)
        GrpcConnectionState.PLAINTEXT_CONNECTED -> "🟡 明文模式已连接" to Color(0xFFFF9800)
        GrpcConnectionState.PLAINTEXT_RECONNECTING -> "🟠 明文模式重连中..." to Color(0xFFFF5722)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(SmoothCornerShape(16.dp))
            .background(statusColor.copy(alpha = 0.08f))
            .border(
                width = 1.dp,
                color = statusColor.copy(alpha = 0.15f),
                shape = SmoothCornerShape(16.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "gRPC 连接状态",
            style = MaterialTheme.typography.labelMedium,
            color = LgOnSurfaceVariant
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



