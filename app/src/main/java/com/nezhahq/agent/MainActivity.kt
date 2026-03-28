package com.nezhahq.agent

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset

import androidx.compose.ui.draw.*

import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nezhahq.agent.grpc.GrpcConnectionState
import rikka.shizuku.Shizuku

// ══════════════════════════════════════════════════════════════════════════════
// Liquid Glass 调色板（源自 Tailwind 配置）
// ══════════════════════════════════════════════════════════════════════════════

/** 背景色 — 极浅青灰 */
private val LgBackground = Color(0xFFF3F7F8)

/** 主文字色 — 深灰 */
private val LgOnSurface = Color(0xFF2B2F31)

/** 主色调 — 深青 */
private val LgPrimary = Color(0xFF006575)

/** 次要文字 — 中灰 */
private val LgOnSurfaceVariant = Color(0xFF575C5D)

/** 轮廓线 */
private val LgOutline = Color(0xFF737879)

/** 青色重点 — 用于按钮/指示器 */
private val LgCyan600 = Color(0xFF0891B2) // tailwind cyan-600 近似
private val LgCyan400 = Color(0xFF22D3EE) // tailwind cyan-400

/** 面板容器色 */
private val LgSurfaceContainerLow = Color(0xFFEDF2F3)
private val LgSurfaceContainerHigh = Color(0xFFDDE4E5)

/** 玻璃半透明白 */
private val LgGlassWhite70 = Color(0xB3FFFFFF) // 70 % 白
private val LgGlassWhite40 = Color(0x66FFFFFF) // 40 % 白

/** 成功 / 警告 / 错误 */
private val LgSuccess = Color(0xFF4CAF50)
private val LgWarning = Color(0xFFFF9800)
private val LgError = Color(0xFFF44336)

/** Nekogram 底栏默认配色 */
private val NekoGlassBarFillTop = Color(0xD9FFFFFF)
private val NekoGlassBarFillBottom = Color(0xD9FFFFFF)
private val NekoGlassBarStroke = Color(0x20000000)
private val NekoGlassTabSelected = Color(0xFF1A91E6)
private val NekoGlassTabSelectedText = Color(0xFF0D7FCF)
private val NekoGlassTabUnselected = Color(0xFF1A1D21)

// ══════════════════════════════════════════════════════════════════════════════
// 自定义 Modifier — 外部柔光阴影 (Ether Button 效果)
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 模拟 CSS `box-shadow: 6px 6px 12px rgba(0,0,0,0.08), -6px -6px 12px rgba(255,255,255,0.9)`。
 * 在 Compose 中使用 `shadow` + 微弱白色底边框来近似呈现。
 */
private fun Modifier.etherShadow(
    elevation: Dp = 6.dp,
    shape: Shape = RoundedCornerShape(24.dp)
): Modifier = this
    .shadow(elevation = elevation, shape = shape, ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.06f))
    .border(width = 1.dp, color = Color.White.copy(alpha = 0.5f), shape = shape)

// ══════════════════════════════════════════════════════════════════════════════
// 玻璃风格面板（EtherCard）
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 半透明毛玻璃卡片容器。
 * 取代原先 Material3 `Card`，采用 70 % 白 + 圆角 24dp + 柔光阴影。
 */
@Composable
private fun EtherCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .etherShadow()
            .clip(RoundedCornerShape(24.dp))
            .background(LgGlassWhite70)
            .padding(20.dp),
        content = content
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// 玻璃风格按钮
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Primary 玻璃按钮 — 半透明青色背景 + 白色光晕边框。
 * 取代原先 Material3 `Button`。
 */
@Composable
private fun GlassButtonPrimary(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val bgAlpha = if (isPressed) 0.55f else 0.4f

    Button(
        onClick = onClick,
        modifier = modifier
            .height(52.dp)
            .etherShadow(shape = RoundedCornerShape(26.dp)),
        enabled = enabled,
        shape = RoundedCornerShape(26.dp),
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = LgCyan400.copy(alpha = bgAlpha),
            contentColor = LgPrimary,
            disabledContainerColor = LgCyan400.copy(alpha = 0.2f),
            disabledContentColor = LgOnSurfaceVariant
        ),
        elevation = ButtonDefaults.buttonElevation(0.dp),
        contentPadding = PaddingValues(horizontal = 24.dp),
        content = content
    )
}

/**
 * Secondary 玻璃按钮 — 半透明白色背景。
 * 取代原先 Material3 `OutlinedButton`。
 */
@Composable
private fun GlassButtonSecondary(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .height(52.dp)
            .etherShadow(shape = RoundedCornerShape(26.dp)),
        shape = RoundedCornerShape(26.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = LgGlassWhite40,
            contentColor = LgOnSurfaceVariant
        ),
        border = null,
        contentPadding = PaddingValues(horizontal = 24.dp),
        content = content
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// 玻璃风格输入框
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Ether 风格输入框 — 浅色圆角容器 + 柔光内阴影效果。
 * 使用原生 `OutlinedTextField` 去掉边框，改用自定义容器包裹。
 */
@Composable
private fun EtherTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1
) {
    Column(modifier = modifier) {
        // 输入容器（保留 label 语义以支持 TalkBack 无障碍朗读）
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, fontSize = 12.sp, color = LgOnSurfaceVariant) },
            modifier = Modifier
                .fillMaxWidth()
                .etherShadow(elevation = 2.dp, shape = RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            maxLines = maxLines,
            textStyle = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = LgOnSurface
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = LgSurfaceContainerLow,
                unfocusedContainerColor = LgSurfaceContainerLow,
                focusedBorderColor = LgCyan400.copy(alpha = 0.5f),
                unfocusedBorderColor = Color.Transparent,
                cursorColor = LgPrimary,
                focusedLabelColor = LgPrimary,
                unfocusedLabelColor = LgOnSurfaceVariant
            )
        )
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 玻璃风格 Switch (Toggle)
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 使用 Material3 Switch，但着色为 Liquid Glass 调色板。
 */
@Composable
private fun EtherSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = LgCyan400,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = LgSurfaceContainerHigh,
            uncheckedBorderColor = Color.Transparent
        )
    )
}

// ══════════════════════════════════════════════════════════════════════════════
// Activity 入口（保持不变 — 仅修改主题色）
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Android 平台哪吒监控探针主界面（MVVM 架构，Liquid Glass Compose）。
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
            // ── Liquid Glass 浅色主题 ──
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = LgPrimary,
                    onPrimary = Color.White,
                    primaryContainer = LgCyan400,
                    background = LgBackground,
                    surface = LgBackground,
                    onSurface = LgOnSurface,
                    onSurfaceVariant = LgOnSurfaceVariant,
                    outline = LgOutline,
                    surfaceVariant = LgSurfaceContainerLow,
                    error = LgError
                ),
                typography = Typography(
                    headlineMedium = TextStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        color = LgPrimary
                    ),
                    titleMedium = TextStyle(
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = LgOnSurface
                    ),
                    bodyMedium = TextStyle(fontSize = 14.sp, color = LgOnSurface),
                    bodySmall = TextStyle(fontSize = 12.sp, color = LgOnSurfaceVariant),
                    labelMedium = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = LgOnSurfaceVariant
                    )
                )
            ) {
                // 最外层容器 — 浅色背景 + 装饰性渐变光斑
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LgBackground)
                ) {
                    // 装饰光斑 — 左上角青色
                    Box(
                        modifier = Modifier
                            .size(280.dp)
                            .offset(x = (-40).dp, y = 120.dp)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        LgCyan400.copy(alpha = 0.12f),
                                        Color.Transparent
                                    ),
                                    radius = 400f
                                ),
                                shape = CircleShape
                            )
                            .blur(100.dp)
                    )
                    // 装饰光斑 — 右下角蓝色
                    Box(
                        modifier = Modifier
                            .size(340.dp)
                            .align(Alignment.BottomEnd)
                            .offset(x = 40.dp, y = (-80).dp)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFF93C5FD).copy(alpha = 0.12f),
                                        Color.Transparent
                                    ),
                                    radius = 500f
                                ),
                                shape = CircleShape
                            )
                            .blur(120.dp)
                    )

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
// 主框架布局（Nekogram 风格底部导航 + 页面切换）
// ══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: MainViewModel,
    shizukuRequestCode: Int = 19527
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val contentViewHolder = remember { mutableStateOf<View?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        MainPagesHost(
            vm = vm,
            shizukuRequestCode = shizukuRequestCode,
            selectedTab = selectedTab,
            modifier = Modifier.fillMaxSize(),
            onViewReady = { view ->
                if (contentViewHolder.value !== view) {
                    contentViewHolder.value = view
                }
            }
        )

        // ── Nekogram 风格底部导航栏 ──
        NekogramBottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars),
            sourceView = contentViewHolder.value,
            selectedIndex = selectedTab,
            items = listOf(
                NekoBottomBarItem(
                    icon = Icons.Default.Home,
                    label = "配置"
                ),
                NekoBottomBarItem(
                    icon = Icons.Default.Build,
                    label = "工具"
                )
            ),
            onSelect = { selectedTab = it }
        )
    }
}

@Composable
private fun MainPagesHost(
    vm: MainViewModel,
    shizukuRequestCode: Int,
    selectedTab: Int,
    modifier: Modifier = Modifier,
    onViewReady: (View) -> Unit
) {
    val parentComposition = rememberCompositionContext()
    val selectedTabState = remember { mutableIntStateOf(selectedTab) }
    selectedTabState.intValue = selectedTab

    AndroidView(
        modifier = modifier,
        factory = { context ->
            ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setParentCompositionContext(parentComposition)
                setContent {
                    MainPagesContent(
                        vm = vm,
                        shizukuRequestCode = shizukuRequestCode,
                        selectedTab = selectedTabState.intValue
                    )
                }
                onViewReady(this)
            }
        },
        update = { view ->
            onViewReady(view)
        }
    )
}

@Composable
private fun MainPagesContent(
    vm: MainViewModel,
    shizukuRequestCode: Int,
    selectedTab: Int
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LgBackground)
    ) {
        Box(
            modifier = Modifier
                .size(280.dp)
                .offset(x = (-40).dp, y = 120.dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            LgCyan400.copy(alpha = 0.12f),
                            Color.Transparent
                        ),
                        radius = 400f
                    ),
                    shape = CircleShape
                )
                .blur(100.dp)
        ) {
        }
        Box(
            modifier = Modifier
                .size(340.dp)
                .align(Alignment.BottomEnd)
                .offset(x = 40.dp, y = (-80).dp)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF93C5FD).copy(alpha = 0.12f),
                            Color.Transparent
                        ),
                        radius = 500f
                    ),
                    shape = CircleShape
                )
                .blur(120.dp)
        ) {
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
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
// Nekogram 风格底部导航
// ══════════════════════════════════════════════════════════════════════════════

private data class NekoBottomBarItem(
    val icon: ImageVector,
    val label: String
)

@Composable
private fun NekogramBottomBar(
    modifier: Modifier = Modifier,
    sourceView: View?,
    selectedIndex: Int,
    items: List<NekoBottomBarItem>,
    onSelect: (Int) -> Unit
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter
    ) {
        val containerWidth = (maxWidth - 16.dp).coerceAtMost(344.dp)
        val containerShape = RoundedCornerShape(28.dp)
        val glassInset = 7.666.dp

        Box(
            modifier = Modifier
                .width(containerWidth)
                .height(72.dp)
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(horizontal = glassInset, vertical = glassInset)
                    .shadow(
                        elevation = 3.dp,
                        shape = containerShape,
                        ambientColor = Color(0x20000000),
                        spotColor = Color(0x20000000)
                    )
                    .clip(containerShape)
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    AndroidView(
                        modifier = Modifier.matchParentSize(),
                        factory = { context ->
                            NekogramLiquidGlassBarView(context)
                        },
                        update = { view ->
                            view.setSourceView(sourceView)
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        NekoGlassBarFillTop,
                                        NekoGlassBarFillBottom
                                    )
                                )
                            )
                    )
                }
            }
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                items.forEachIndexed { index, item ->
                    NekogramBottomBarItem(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        icon = item.icon,
                        label = item.label,
                        selected = selectedIndex == index,
                        onClick = { onSelect(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NekogramBottomBarItem(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val selectionFactor by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 320),
        label = "nekoTabSelection"
    )
    val iconColor = lerp(NekoGlassTabUnselected, NekoGlassTabSelected, selectionFactor)
    val textColor = lerp(NekoGlassTabUnselected, NekoGlassTabSelectedText, selectionFactor)
    val backgroundScale = 0.6f + 0.4f * selectionFactor
    val interactionSource = remember { MutableInteractionSource() }
    val selectedShape = RoundedCornerShape(24.dp)

    Box(
        modifier = modifier
            .clip(selectedShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = backgroundScale
                    scaleY = backgroundScale
                }
                .clip(selectedShape)
                .background(NekoGlassTabSelected.copy(alpha = 0.09f * selectionFactor))
        )
        Box(modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 4.dp)
                    .size(24.dp),
                tint = iconColor
            )
            Text(
                text = label,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 28.dp)
                    .fillMaxWidth(),
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Bold,
                color = textColor,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
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
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("工具与设置", style = MaterialTheme.typography.headlineMedium)

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

            permissionList.forEach { item ->
                Spacer(modifier = Modifier.height(4.dp))
                PermissionStatusRow(
                    item = item,
                    onAction = {
                        // 根据权限类型执行不同的授权动作
                        when (item.key) {
                            "sms" -> smsPermLauncher.launch(Manifest.permission.READ_SMS)
                            "usage_stats" -> {
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
                                safeStartActivity(context, Intent().apply {
                                    action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                })
                            }
                            "auto_start" -> {
                                vm.toggleAutoStart(!item.granted)
                                permissionList = com.nezhahq.agent.util.PermissionChecker.getAllPermissionStatus(context)
                            }
                            "storage" -> {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                                    safeStartActivity(context, Intent(
                                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    ))
                                } else {
                                    safeStartActivity(context, Intent(
                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
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
                onCheckedChange = { newValue ->
                    vm.enableKeepAliveAudio = newValue
                    vm.saveToolSettings()
                },
                title = "允许后台播放微弱音频",
                description = "发送极其微弱的次声波骗过部分系统的静音检测，防止杀后台（需重启服务生效）"
            )

            // 悬浮窗
            EtherToggleRow(
                checked = vm.enableFloatWindow,
                onCheckedChange = { newValue ->
                    vm.enableFloatWindow = newValue
                    vm.saveToolSettings()
                },
                title = "开启像素级透明悬浮窗",
                description = "创建一个1x1不可见的悬浮窗来拉高进程优先级（需授予悬浮窗权限并重启服务生效）"
            )

            // 开机自启动
            EtherToggleRow(
                checked = vm.enableAutoStart,
                onCheckedChange = { newValue ->
                    vm.toggleAutoStart(newValue)
                },
                title = "开机自启动",
                description = "设备重启后自动恢复探针后台服务，建议开启以防失联"
            )
        }

        // ══════════════════════════════════════════════════════════════════
        // 数据采集增强
        // ══════════════════════════════════════════════════════════════════

        val vpnContext = androidx.compose.ui.platform.LocalContext.current

        // VPN 授权回调：用户同意后保存配置
        val vpnAuthLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == ComponentActivity.RESULT_OK) {
                vm.enableVpnTraffic = true
                vm.saveToolSettings()
                Toast.makeText(vpnContext, "VPN 流量计量已授权，重启探针生效", Toast.LENGTH_SHORT).show()
            } else {
                vm.enableVpnTraffic = false
                Toast.makeText(vpnContext, "VPN 授权被拒绝", Toast.LENGTH_SHORT).show()
            }
        }

        EtherCard {
            Text("数据采集增强", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.Top) {
                EtherSwitch(
                    checked = vm.enableVpnTraffic,
                    onCheckedChange = { newValue ->
                        if (newValue) {
                            val prepareIntent = VpnService.prepare(vpnContext)
                            if (prepareIntent != null) {
                                vpnAuthLauncher.launch(prepareIntent)
                            } else {
                                vm.enableVpnTraffic = true
                                vm.saveToolSettings()
                            }
                        } else {
                            vm.enableVpnTraffic = false
                            vm.saveToolSettings()
                        }
                    }
                )
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text("VPN 流量计量模式", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "在无 Root/Shizuku 且 Android 12 以下的设备上，通过本地 VPN 隧道精确统计网络流量（需重启服务生效）",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "⚠️ 开启后将与其他 VPN 应用冲突（系统仅允许同时运行一个 VPN）",
                        style = MaterialTheme.typography.bodySmall,
                        color = LgWarning
                    )
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
private fun EtherToggleRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (checked) LgCyan400.copy(alpha = 0.06f) else Color.Transparent)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        EtherSwitch(checked = checked, onCheckedChange = onCheckedChange)
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
private fun PermissionStatusRow(
    item: com.nezhahq.agent.util.PermissionChecker.PermissionItem,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
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
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (!item.granted) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = LgCyan600)
            ) {
                Text(
                    text = if (item.key == "auto_start") "启用" else "去授权",
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
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── 标题 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "哪吒探针",
                style = MaterialTheme.typography.headlineMedium
            )
        }

        // ── 首次启动自启动授权弹窗（保持功能不变）──
        if (vm.showAutoStartPrompt) {
            AlertDialog(
                onDismissRequest = { /* 强迫用户做出选择，不响应点击外部取消 */ },
                title = { Text("启用开机自启动？") },
                text = { Text("为了保证设备重启后探针不会离线，强烈建议您开启「开机自启动」功能。您稍后随时可以在「工具」页面修改此选项。") },
                confirmButton = {
                    GlassButtonPrimary(onClick = { vm.onAutoStartPromptResult(true) }) {
                        Text("启用")
                    }
                },
                dismissButton = {
                    GlassButtonSecondary(onClick = { vm.onAutoStartPromptResult(false) }) {
                        Text("暂不启用")
                    }
                }
            )
        }

        // ── gRPC 连接状态指示器 ──
        GrpcStatusIndicator(grpcState)

        // ── 智能解析面板 ──
        EtherCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("智能解析一键配置", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "扫描二维码或粘贴链接自动填充",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(12.dp))
            EtherTextField(
                value = vm.clipboardInput,
                onValueChange = { vm.clipboardInput = it },
                label = "请粘贴面板上的 curl 安装脚本/命令",
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3
            )
            Spacer(modifier = Modifier.height(12.dp))
            GlassButtonPrimary(
                onClick = { vm.parseClipboardConfig() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("开始智能解析 (Smart Parse)", fontWeight = FontWeight.Bold) }
        }

        // ── 连接设置 ──
        EtherCard {
            Text("连接设置", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(12.dp))

            EtherTextField(
                value = vm.server, onValueChange = { vm.server = it },
                label = "服务端 IP 或域名", modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            EtherTextField(
                value = vm.port, onValueChange = { vm.port = it },
                label = "gRPC 端口 (例如 8008)", modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            EtherTextField(
                value = vm.secret, onValueChange = { vm.secret = it },
                label = "客户端密钥 (Secret)", modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            EtherTextField(
                value = vm.uuid, onValueChange = { vm.uuid = it },
                label = "客户端标识 (UUID)", modifier = Modifier.fillMaxWidth()
            )
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
                    }
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
        }

        // ── 即时测试按钮 ──
        EtherCard {
            Text("数据采集测试", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "立即执行一次系统数据采集，验证当前权限模式是否生效。",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(12.dp))
            GlassButtonPrimary(
                onClick = { vm.runInstantTest() },
                modifier = Modifier.fillMaxWidth(),
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
                    Text("⚡ 即时测试", fontWeight = FontWeight.Bold)
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
            GlassButtonPrimary(
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
            ) { Text("▶ 启动探针", fontWeight = FontWeight.Bold) }

            GlassButtonSecondary(
                onClick = { vm.stopAgent() },
                modifier = Modifier.weight(1f)
            ) { Text("停止探针", fontWeight = FontWeight.Bold) }
        }

        // ── 日志实时预览窗 ──
        val logs by com.nezhahq.agent.util.Logger.logs.collectAsState()

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
                            com.nezhahq.agent.util.Logger.getLogString()
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
                    .shadow(16.dp, RoundedCornerShape(24.dp))
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xFF1A1F20))
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(16.dp)
            ) {
                // 使用 LazyColumn 局部刷新日志列表，避免全量字符串拼接和全量重绘。
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
private fun GrpcStatusIndicator(state: GrpcConnectionState) {
    val (statusText, statusColor) = when (state) {
        GrpcConnectionState.IDLE -> "⚪ 未连接" to Color(0xFF9E9E9E)
        GrpcConnectionState.CONNECTING -> "🔵 连接中..." to Color(0xFF2196F3)
        GrpcConnectionState.CONNECTED -> "🟢 已连接" to LgSuccess
        GrpcConnectionState.RECONNECTING -> "🟠 重连中..." to LgWarning
        GrpcConnectionState.AUTH_FAILED -> "🔴 认证失败" to LgError
        GrpcConnectionState.TLS_FALLBACK -> "🟠 TLS 失败，已降级明文" to Color(0xFFFF5722)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(statusColor.copy(alpha = 0.08f))
            .border(
                width = 1.dp,
                color = statusColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(16.dp)
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
