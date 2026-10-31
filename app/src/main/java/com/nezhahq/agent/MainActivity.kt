package com.nezhahq.agent

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.*

import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.nezhahq.agent.ui.UiEvent
import com.nezhahq.agent.ui.ConfigScreenContent
import com.nezhahq.agent.ui.LgBackground
import com.nezhahq.agent.ui.LgCyan400
import com.nezhahq.agent.ui.LgError
import com.nezhahq.agent.ui.LgOnSurface
import com.nezhahq.agent.ui.LgOnSurfaceVariant
import com.nezhahq.agent.ui.LgOutline
import com.nezhahq.agent.ui.LgPrimary
import com.nezhahq.agent.ui.LgSurfaceContainerLow
import com.nezhahq.agent.ui.NekoGlassBarFillBottom
import com.nezhahq.agent.ui.NekoGlassBarFillTop
import com.nezhahq.agent.ui.NekoGlassTabSelected
import com.nezhahq.agent.ui.NekoGlassTabSelectedText
import com.nezhahq.agent.ui.NekoGlassTabUnselected
import com.nezhahq.agent.ui.SmoothCornerShape
import com.nezhahq.agent.ui.ToolsScreenContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import rikka.shizuku.Shizuku

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

    /**
     * Held at activity level so the Shizuku listener can deliver straight to it.
     *
     * The result used to be routed through a callback that composition installed, which left a gap
     * between activity recreation and the first composition: a grant arriving in that window was
     * dropped, and the user saw an authorization that appeared to do nothing.
     */
    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }

    /** Shizuku 权限回调：将结果转发给 ViewModel。 */
    private val shizukuPermResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                viewModel.onShizukuPermissionResult(
                    grantResult == PackageManager.PERMISSION_GRANTED
                )
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
                // The decorative gradient blobs live in MainPagesContent, which draws its own
                // opaque background over this whole area; painting them here too would render two
                // blurred layers that nothing can ever see.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LgBackground)
                ) {
                    // 一次性事件在此消费；ViewModel 不再直接触碰 Android 的 Toast。
                    UiEventHost(viewModel)

                    MainScreen(
                        vm = viewModel,
                        shizukuRequestCode = SHIZUKU_REQUEST_CODE
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermResultListener)
    }

    companion object {
        /**
         * Shizuku 权限请求码（任意唯一整数）。
         *
         * 单一事实来源：改为编译期 `const val`（而非每实例一份的属性），供权限回调与 setContent 引用。
         * MainScreen / ConfigScreenContent 原先各自把 19527 写进默认参数，现已删除那两个默认值——
         * 两处都由本 Activity 显式下传，避免同一常量散落三处。
         */
        private const val SHIZUKU_REQUEST_CODE = 19527
    }
}

/**
 * Shows the view model's one-off messages.
 *
 * Collection is tied to the composition, so a message emitted while the UI is gone waits in the
 * channel rather than being shown to nobody.
 */
@Composable
private fun UiEventHost(vm: MainViewModel) {
    val context = LocalContext.current
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            val (text, duration) = when (event) {
                is UiEvent.Message -> event.text to Toast.LENGTH_SHORT
                is UiEvent.LongMessage -> event.text to Toast.LENGTH_LONG
            }
            Toast.makeText(context, text, duration).show()
        }
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// 主框架布局（Nekogram 风格底部导航 + 页面切换）
// ══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: MainViewModel,
    shizukuRequestCode: Int
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val contentViewHolder = remember { mutableStateOf<View?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        MainPagesHost(
            vm = vm,
            shizukuRequestCode = shizukuRequestCode,
            selectedTab = selectedTab,
            // Read here rather than inside the pages: they compose inside an embedded ComposeView,
            // which is a separate composition root that window insets do not reach, so reading them
            // there reported zero and the content drew under the status bar.
            contentPadding = WindowInsets.safeDrawing.only(WindowInsetsSides.Top).asPaddingValues(),
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
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onViewReady: (View) -> Unit
) {
    val parentComposition = rememberCompositionContext()
    // 这两个 state 是“外层组合 → 内嵌 ComposeView 组合”的桥梁：ComposeView 只在 factory 里创建一次，
    // 其 setContent 捕获的正是这两个 state 对象，靠读取它们才能在旋转 / 刘海变化时把新值送进去。
    // 写入放到 SideEffect：在组合期直接写 snapshot state 属于“组合中修改自身依赖的状态”，是
    // Compose 明确不建议的反模式；SideEffect 在组合成功提交后才运行，正是发布这类值的时机。
    val selectedTabState = remember { mutableIntStateOf(selectedTab) }
    SideEffect { selectedTabState.intValue = selectedTab }
    val contentPaddingState = remember { mutableStateOf(contentPadding) }
    SideEffect { contentPaddingState.value = contentPadding }

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
                        selectedTab = selectedTabState.intValue,
                        contentPadding = contentPaddingState.value
                    )
                }
                // onViewReady 只在 update 里回调一次（update 在首次组合后也会执行）。
                // 该回调会写外层 state，原先 factory + update 各调一次，重复且时序脆弱。
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
    selectedTab: Int,
    contentPadding: PaddingValues
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
        // Not padded here: padding outside a scrollable clips its viewport, leaving the inset strip
        // permanently blank and cutting content off at its edge. Each screen folds the inset into
        // its own scrollable content instead, so the list scrolls under the status bar.
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (selectedTab == 0) 1f else 0f)
                    .alpha(if (selectedTab == 0) 1f else 0f)
            ) {
                ConfigScreenContent(vm, shizukuRequestCode, contentPadding)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (selectedTab == 1) 1f else 0f)
                    .alpha(if (selectedTab == 1) 1f else 0f)
            ) {
                ToolsScreenContent(vm, contentPadding)
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
        val containerShape = SmoothCornerShape(28.dp)
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
                        factory = ::NekogramLiquidGlassBarView,
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
    val selectedShape = SmoothCornerShape(24.dp)

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

