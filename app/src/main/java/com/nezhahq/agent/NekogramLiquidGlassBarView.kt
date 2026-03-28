package com.nezhahq.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class NekogramLiquidGlassBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val clipPath = Path()
    private val glassRect = RectF()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val topStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val bottomStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val topStrokeClip = Rect()
    private val bottomStrokeClip = Rect()

    private val blurNode = RenderNode("AgentLiquidGlassBarBlur")
    private val fillNode = RenderNode("AgentLiquidGlassBarFill")
    private val liquidGlassEffect = LiquidGlassRuntimeEffect(context, fillNode)


    private var cornerRadius = dp(28f)
    private var foregroundColor = 0xB9FFFFFF.toInt()
    private var strokeTopColor = 0x11000000
    private var strokeBottomColor = 0x20000000
    private val strokeTopWidth = dp(0.4f)
    private val strokeBottomWidth = dp(0.4f)

    private var sourceView: View? = null

    private val sourcePreDrawListener = ViewTreeObserver.OnPreDrawListener {
        postInvalidateOnAnimation()
        true
    }

    init {
        setWillNotDraw(false)
        topStrokePaint.strokeWidth = strokeTopWidth
        bottomStrokePaint.strokeWidth = strokeBottomWidth
        topStrokePaint.color = strokeTopColor
        bottomStrokePaint.color = strokeBottomColor
    }

    fun setSourceView(view: View?) {
        if (sourceView === view) {
            return
        }
        detachSourceListener()
        sourceView = view
        attachSourceListener()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachSourceListener()
    }

    override fun onDetachedFromWindow() {
        detachSourceListener()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        blurNode.setPosition(0, 0, w, h)
        blurNode.setRenderEffect(RenderEffect.createBlurEffect(dp(1.66f), dp(1.66f), Shader.TileMode.CLAMP))
        fillNode.setPosition(0, 0, w, h)
        rebuildPath(w, h)
        topStrokeClip.set(0, 0, w, h / 2)
        bottomStrokeClip.set(0, h / 3, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) {
            return
        }

        if (canvas.isHardwareAccelerated) {
            val source = sourceView
            if (source != null && source.width > 0 && source.height > 0) {
                val sourceLocation = IntArray(2)
                val selfLocation = IntArray(2)
                source.getLocationInWindow(sourceLocation)
                getLocationInWindow(selfLocation)

                val blurCanvas = blurNode.beginRecording()
                blurCanvas.save()
                blurCanvas.translate(
                    (sourceLocation[0] - selfLocation[0]).toFloat(),
                    (sourceLocation[1] - selfLocation[1]).toFloat()
                )
                // 【架构修复】舍弃原来的软件 Canvas 快照截帧模式（软件画布会使 Compose RenderNode 处理 
                // StretchOverscrollEffect 的弹性拉伸时发生底层管线冲突导致永远卡死在拉伸状态）。
                // 这里直接利用 RenderNode 引擎分配的真·硬件加速画布(Hardware Canvas)进行绘制，
                // 不仅能让列表天然地保持带阻尼的过度拉伸-回弹物理特性，更能降低约 2MB-3MB 频繁擦写的常驻 Bitmap 内存。
                source.draw(blurCanvas)
                blurCanvas.restore()
                blurNode.endRecording()
            } else {
                val blurCanvas = blurNode.beginRecording()
                blurCanvas.drawColor(Color.TRANSPARENT)
                blurNode.endRecording()
            }

            val recordingCanvas = fillNode.beginRecording()
            recordingCanvas.drawRenderNode(blurNode)
            fillNode.endRecording()

            liquidGlassEffect.update(
                left = 0f,
                top = 0f,
                right = width.toFloat(),
                bottom = height.toFloat(),
                radiusLeftTop = cornerRadius,
                radiusRightTop = cornerRadius,
                radiusRightBottom = cornerRadius,
                radiusLeftBottom = cornerRadius,
                thickness = dp(11f),
                intensity = 0.75f,
                index = 1.5f,
                foregroundColor = foregroundColor
            )

            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawRenderNode(fillNode)
            canvas.restore()
        } else {
            // Android 13+ 常规环境 100% 为 HardwareAccelerated
            // 兜底方案直接绘制不透明/半透明遮罩
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawColor(foregroundColor)
            canvas.restore()
        }

        canvas.save()
        canvas.clipRect(topStrokeClip)
        canvas.drawRoundRect(glassRect, cornerRadius, cornerRadius, topStrokePaint)
        canvas.restore()

        canvas.save()
        canvas.clipRect(bottomStrokeClip)
        canvas.drawRoundRect(glassRect, cornerRadius, cornerRadius, bottomStrokePaint)
        canvas.restore()
    }


    private fun rebuildPath(width: Int, height: Int) {
        glassRect.set(0f, 0f, width.toFloat(), height.toFloat())
        clipPath.rewind()
        clipPath.addRoundRect(glassRect, cornerRadius, cornerRadius, Path.Direction.CW)
        clipPath.close()
    }

    private fun attachSourceListener() {
        val source = sourceView ?: return
        if (isAttachedToWindow && source.viewTreeObserver.isAlive) {
            source.viewTreeObserver.removeOnPreDrawListener(sourcePreDrawListener)
            source.viewTreeObserver.addOnPreDrawListener(sourcePreDrawListener)
        }
    }

    private fun detachSourceListener() {
        val source = sourceView ?: return
        if (source.viewTreeObserver.isAlive) {
            source.viewTreeObserver.removeOnPreDrawListener(sourcePreDrawListener)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class LiquidGlassRuntimeEffect(
    context: Context,
    private val node: RenderNode
) {
    private val shader = RuntimeShader(
        context.resources.openRawResource(R.raw.liquid_glass_shader)
            .bufferedReader()
            .use { it.readText() }
    )

    private var effect: RenderEffect =
        RenderEffect.createRuntimeShaderEffect(shader, "img")

    init {
        node.setRenderEffect(effect)
    }

    fun update(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radiusLeftTop: Float,
        radiusRightTop: Float,
        radiusRightBottom: Float,
        radiusLeftBottom: Float,
        thickness: Float,
        intensity: Float,
        index: Float,
        foregroundColor: Int
    ) {
        val alpha = Color.alpha(foregroundColor) / 255f
        val red = Color.red(foregroundColor) / 255f * alpha
        val green = Color.green(foregroundColor) / 255f * alpha
        val blue = Color.blue(foregroundColor) / 255f * alpha

        shader.setFloatUniform("resolution", node.width.toFloat(), node.height.toFloat())
        shader.setFloatUniform("center", (left + right) / 2f, (top + bottom) / 2f)
        shader.setFloatUniform("size", (right - left) / 2f, (bottom - top) / 2f)
        shader.setFloatUniform(
            "radius",
            radiusRightBottom,
            radiusRightTop,
            radiusLeftBottom,
            radiusLeftTop
        )
        shader.setFloatUniform("thickness", thickness)
        shader.setFloatUniform("refract_intensity", intensity)
        shader.setFloatUniform("refract_index", index)
        shader.setFloatUniform("foreground_color_premultiplied", red, green, blue, alpha)

        effect = RenderEffect.createRuntimeShaderEffect(shader, "img")
        node.setRenderEffect(effect)
    }
}
