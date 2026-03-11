package com.nezhahq.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.nezhahq.agent.service.AgentService
import com.nezhahq.agent.util.ConfigStore
import java.util.*

/**
 * Android 平台哪吒监控探针主界面 (采用 Material 3 标准 Compose 构建)
 */
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                // 适配暗色沉浸主题
                colorScheme = darkColorScheme(
                    primary = Color(0xFF6200EE),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    var server by remember { mutableStateOf(ConfigStore.getServer(context)) }
    var port by remember { mutableStateOf(ConfigStore.getPort(context).toString()) }
    var secret by remember { mutableStateOf(ConfigStore.getSecret(context)) }
    var uuid by remember { mutableStateOf(ConfigStore.getUuid(context)) }
    var useTls by remember { mutableStateOf(ConfigStore.getUseTls(context)) }
    var rootMode by remember { mutableStateOf(false) }
    var clipboardInput by remember { mutableStateOf("") }
    
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("哪吒探针 (Android 原生版)", style = MaterialTheme.typography.headlineMedium)

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
                        if (envUuidMatch != null) uuid = envUuidMatch.groupValues[1]
                        
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

        // 高级权限特性
        Card(shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("高级特性", style = MaterialTheme.typography.titleMedium)
                Row {
                    Switch(checked = rootMode, onCheckedChange = { rootMode = it })
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text("Root / ADB (Shizuku) 大杀器模式")
                        Text("开启后增强 CPU 与全网 TCP 连接嗅探能力，允许执行系统级高权限 Shell，但仅限高级设备适用。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // 守护进程启停控制
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val p = port.toIntOrNull() ?: 5555
                    // 持久化加密存储密钥
                    ConfigStore.saveConfig(context, server, p, secret, useTls, uuid)
                    val intent = Intent(context, AgentService::class.java)
                    // 适配 Android O 以上严格的前台服务启动规则
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    Toast.makeText(context, "后台探针服务已启动", Toast.LENGTH_SHORT).show()
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
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("底层调试日志 (LogViewer)", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("logs", com.nezhahq.agent.util.Logger.getLogString())
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("一键复制日志")
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
