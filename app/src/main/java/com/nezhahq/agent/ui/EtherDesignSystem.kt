package com.nezhahq.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Liquid Glass 设计系统：配色、形状、阴影与基础控件。
// 说明：曲线数学（PI/sin/cos/pow 等）已抽到 SmoothCorner.kt 的 traceSmoothCorner，
// 本文件不再直接引用 kotlin.math.*，故移除相应 import。

// ══════════════════════════════════════════════════════════════════════════════
// Liquid Glass 调色板（源自 Tailwind 配置）
// ══════════════════════════════════════════════════════════════════════════════

/** 背景色 — 极浅青灰 */
internal val LgBackground = Color(0xFFF3F7F8)

/** 主文字色 — 深灰 */
internal val LgOnSurface = Color(0xFF2B2F31)

/** 主色调 — 深青 */
internal val LgPrimary = Color(0xFF006575)

/** 次要文字 — 中灰 */
internal val LgOnSurfaceVariant = Color(0xFF575C5D)

/** 轮廓线 */
internal val LgOutline = Color(0xFF737879)

/** 青色重点 — 用于按钮/指示器 */
internal val LgCyan600 = Color(0xFF0891B2) // tailwind cyan-600 近似
internal val LgCyan400 = Color(0xFF22D3EE) // tailwind cyan-400

/** 面板容器色 */
internal val LgSurfaceContainerLow = Color(0xFFEDF2F3)
internal val LgSurfaceContainerHigh = Color(0xFFDDE4E5)

/** 玻璃半透明白 */
internal val LgGlassWhite70 = Color(0xB3FFFFFF) // 70 % 白
internal val LgGlassWhite40 = Color(0x66FFFFFF) // 40 % 白

/** 成功 / 警告 / 错误 */
internal val LgSuccess = Color(0xFF4CAF50)
internal val LgWarning = Color(0xFFFF9800)
internal val LgError = Color(0xFFF44336)

/** Nekogram 底栏默认配色 */
internal val NekoGlassBarFillTop = Color(0xD9FFFFFF)
internal val NekoGlassBarFillBottom = Color(0xD9FFFFFF)
internal val NekoGlassTabSelected = Color(0xFF1A91E6)
internal val NekoGlassTabSelectedText = Color(0xFF0D7FCF)
internal val NekoGlassTabUnselected = Color(0xFF1A1D21)

internal val LgControlRadius = 26.dp
internal val LgPanelPadding = 20.dp
internal val LgConsolePadding = 16.dp

internal val LgControlShape = SmoothCornerShape(LgControlRadius)
internal val LgPanelShape = outerSmoothShape(innerRadius = LgControlRadius, inset = LgPanelPadding)
internal val LgConsoleShape = outerSmoothShape(innerRadius = LgControlRadius, inset = LgConsolePadding)

internal fun outerSmoothShape(innerRadius: Dp, inset: Dp): SmoothCornerShape {
    return SmoothCornerShape(innerRadius + inset)
}

internal data class SmoothCornerShape(
    private val radius: Dp,
    private val exponent: Double = SMOOTH_CORNER_EXPONENT
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radiusPx = with(density) { radius.toPx() }
            .coerceAtMost(size.width / 2f)
            .coerceAtMost(size.height / 2f)

        val path = Path().apply {
            moveTo(radiusPx, 0f)
            lineTo(size.width - radiusPx, 0f)
            corner(size.width - radiusPx, radiusPx, radiusPx, -90.0, 0.0)
            lineTo(size.width, size.height - radiusPx)
            corner(size.width - radiusPx, size.height - radiusPx, radiusPx, 0.0, 90.0)
            lineTo(radiusPx, size.height)
            corner(radiusPx, size.height - radiusPx, radiusPx, 90.0, 180.0)
            lineTo(0f, radiusPx)
            corner(radiusPx, radiusPx, radiusPx, 180.0, 270.0)
            close()
        }

        return Outline.Generic(path)
    }

    private fun Path.corner(
        centerX: Float,
        centerY: Float,
        radius: Float,
        startDegrees: Double,
        endDegrees: Double
    ) = traceSmoothCorner(centerX, centerY, radius, startDegrees, endDegrees, exponent, ::lineTo)
}

// ══════════════════════════════════════════════════════════════════════════════
// 自定义 Modifier — 外部柔光阴影 (Ether Button 效果)
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 模拟 CSS `box-shadow: 6px 6px 12px rgba(0,0,0,0.08), -6px -6px 12px rgba(255,255,255,0.9)`。
 * 在 Compose 中使用 `shadow` + 微弱白色底边框来近似呈现。
 */
internal fun Modifier.etherShadow(
    elevation: Dp = 6.dp,
    shape: Shape = LgControlShape
): Modifier = this
    .shadow(elevation = elevation, shape = shape, ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.06f))
    .border(width = 1.dp, color = Color.White.copy(alpha = 0.5f), shape = shape)

// ══════════════════════════════════════════════════════════════════════════════
// 玻璃风格面板（EtherCard）
// ══════════════════════════════════════════════════════════════════════════════

/**
 * 半透明毛玻璃卡片容器。
 * 外层圆角等距包裹内层控件：外半径 = 内层控件半径 + 面板内边距。
 */
@Composable
internal fun EtherCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .etherShadow(shape = LgPanelShape)
            .clip(LgPanelShape)
            .background(LgGlassWhite70)
            .padding(LgPanelPadding),
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
internal fun GlassButtonPrimary(
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
            .etherShadow(shape = LgControlShape),
        enabled = enabled,
        shape = LgControlShape,
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
internal fun GlassButtonSecondary(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .height(52.dp)
            .etherShadow(shape = LgControlShape),
        enabled = enabled,
        shape = LgControlShape,
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
internal fun EtherTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true
) {
    Column(modifier = modifier) {
        // 输入容器（保留 label 语义以支持 TalkBack 无障碍朗读）
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label, fontSize = 12.sp, color = LgOnSurfaceVariant) },
            modifier = Modifier
                .fillMaxWidth()
                .etherShadow(elevation = 2.dp, shape = LgControlShape),
            shape = LgControlShape,
            enabled = enabled,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
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
internal fun EtherSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
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
