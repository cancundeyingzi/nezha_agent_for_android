package com.nezhahq.agent

import android.content.Context
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
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import com.nezhahq.agent.ui.traceSmoothCorner

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class NekogramLiquidGlassBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val clipPath = Path()
    private val strokePath = Path()
    private val glassRect = RectF()
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

    private val cornerRadius = dp(28f)
    private val foregroundColor = 0xB9FFFFFF.toInt()

    /** Reused every frame; [onDraw] runs on the UI thread, so no other thread observes these. */
    private val sourceLocation = IntArray(2)
    private val selfLocation = IntArray(2)

    private var sourceView: View? = null
    private var sourceObserver: ViewTreeObserver? = null
    private var recordedOffsetX = Int.MIN_VALUE
    private var recordedOffsetY = Int.MIN_VALUE
    private var recordedSourceWidth = 0
    private var recordedSourceHeight = 0

    private val sourcePreDrawListener = ViewTreeObserver.OnPreDrawListener {
        val source = sourceView
        // This observer belongs to the WINDOW, not just source. An unconditional next-frame
        // invalidation here feeds the bar's own draw back into itself forever on an idle screen.
        if (canRefreshFromSource() && source != null && source.width > 0 && source.height > 0 &&
            (sourceContentIsDirty(source) || sourceGeometryChanged(source))) invalidate()
        true
    }
    private val sourceScrollListener = ViewTreeObserver.OnScrollChangedListener { refreshFromSource() }
    private val sourceLayoutListener = OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> refreshFromSource() }

    init {
        setWillNotDraw(false)
        topStrokePaint.strokeWidth = dp(0.4f)
        bottomStrokePaint.strokeWidth = dp(0.4f)
        topStrokePaint.color = 0x11000000
        bottomStrokePaint.color = 0x20000000
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

    /**
     * Rebuilds everything that depends on the view's size.
     *
     * The shader uniforms belong here rather than in [onDraw]: every one of them is derived from
     * the size or from a constant, so refreshing them per frame allocated a [RenderEffect] on each
     * draw even when those values had not changed. Any
     * future uniform that varies independently of the size must re-run [LiquidGlassRuntimeEffect
     * .update] when it changes.
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        blurNode.setPosition(0, 0, w, h)
        blurNode.setRenderEffect(
            RenderEffect.createBlurEffect(dp(1.66f), dp(1.66f), Shader.TileMode.CLAMP)
        )
        fillNode.setPosition(0, 0, w, h)

        rebuildPath(w, h)
        topStrokeClip.set(0, 0, w, h / 2)
        bottomStrokeClip.set(0, h / 3, w, h)

        liquidGlassEffect.update(
            width = w.toFloat(),
            height = h.toFloat(),
            cornerRadius = cornerRadius,
            thickness = dp(11f),
            intensity = 0.75f,
            index = 1.5f,
            foregroundColor = foregroundColor
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) {
            return
        }
        recordSourceGeometry()

        canvas.save()
        // An empty path would clip the whole bar away; drawing unclipped is the better degradation.
        if (!clipPath.isEmpty) canvas.clipPath(clipPath)
        if (canvas.isHardwareAccelerated) {
            recordSourceIntoBlurNode()
            fillNode.beginRecording().drawRenderNode(blurNode)
            fillNode.endRecording()
            canvas.drawRenderNode(fillNode)
        } else {
            // Android 13+ 常规环境 100% 为 HardwareAccelerated；兜底直接绘制半透明遮罩。
            canvas.drawColor(foregroundColor)
        }
        canvas.restore()

        canvas.save()
        canvas.clipRect(topStrokeClip)
        canvas.drawPath(strokePath, topStrokePaint)
        canvas.restore()

        canvas.save()
        canvas.clipRect(bottomStrokeClip)
        canvas.drawPath(strokePath, bottomStrokePaint)
        canvas.restore()
    }

    /**
     * Draws the content behind this bar into [blurNode].
     *
     * Recording straight onto the RenderNode's hardware canvas — rather than snapshotting into a
     * software Bitmap — keeps the source list's stretch overscroll working (a software canvas
     * deadlocks Compose's RenderNode pipeline in the stretched state) and avoids a resident
     * multi-megabyte bitmap that would be rewritten every frame.
     */
    private fun recordSourceIntoBlurNode() {
        val source = sourceView
        val canvas = blurNode.beginRecording()
        if (source != null && source.width > 0 && source.height > 0) {
            canvas.save()
            canvas.translate(
                recordedOffsetX.toFloat(), recordedOffsetY.toFloat()
            )
            source.draw(canvas)
            canvas.restore()
        } else {
            canvas.drawColor(Color.TRANSPARENT)
        }
        blurNode.endRecording()
    }

    private fun rebuildPath(width: Int, height: Int) {
        glassRect.set(0f, 0f, width.toFloat(), height.toFloat())
        clipPath.rewind()
        clipPath.addSmoothRect(glassRect, cornerRadius)
        strokePath.rewind()
        strokePath.addSmoothRect(glassRect, cornerRadius)
    }

    private fun attachSourceListener() {
        detachSourceListener()
        val source = sourceView ?: return
        if (isAttachedToWindow && source.viewTreeObserver.isAlive) {
            sourceObserver = source.viewTreeObserver.also {
                it.addOnPreDrawListener(sourcePreDrawListener)
                it.addOnScrollChangedListener(sourceScrollListener)
            }
            source.addOnLayoutChangeListener(sourceLayoutListener)
        }
    }

    private fun detachSourceListener() {
        sourceObserver?.takeIf { it.isAlive }?.let {
            it.removeOnPreDrawListener(sourcePreDrawListener)
            it.removeOnScrollChangedListener(sourceScrollListener)
        }
        sourceObserver = null
        sourceView?.removeOnLayoutChangeListener(sourceLayoutListener)
    }

    private fun sourceContentIsDirty(view: View): Boolean {
        // Invisible children can retain dirty flags indefinitely without ever drawing.
        if (view.visibility != VISIBLE) return false
        if (view.isDirty) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) if (sourceContentIsDirty(view.getChildAt(index))) return true
        }
        return false
    }

    private fun sourceGeometryChanged(source: View): Boolean {
        source.getLocationInWindow(sourceLocation)
        getLocationInWindow(selfLocation)
        return sourceLocation[0] - selfLocation[0] != recordedOffsetX ||
            sourceLocation[1] - selfLocation[1] != recordedOffsetY ||
            source.width != recordedSourceWidth || source.height != recordedSourceHeight
    }

    private fun recordSourceGeometry() {
        val source = sourceView ?: return
        source.getLocationInWindow(sourceLocation)
        getLocationInWindow(selfLocation)
        recordedOffsetX = sourceLocation[0] - selfLocation[0]
        recordedOffsetY = sourceLocation[1] - selfLocation[1]
        recordedSourceWidth = source.width
        recordedSourceHeight = source.height
    }

    private fun canRefreshFromSource() = isShown && alpha > 0f && width > 0 && height > 0
    private fun refreshFromSource() { if (canRefreshFromSource()) invalidate() }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

private fun Path.addSmoothRect(rect: RectF, radius: Float) {
    val smoothRadius = radius
        .coerceAtMost(rect.width() / 2f)
        .coerceAtMost(rect.height() / 2f)

    moveTo(rect.left + smoothRadius, rect.top)
    lineTo(rect.right - smoothRadius, rect.top)
    corner(rect.right - smoothRadius, rect.top + smoothRadius, smoothRadius, -90.0, 0.0)
    lineTo(rect.right, rect.bottom - smoothRadius)
    corner(rect.right - smoothRadius, rect.bottom - smoothRadius, smoothRadius, 0.0, 90.0)
    lineTo(rect.left + smoothRadius, rect.bottom)
    corner(rect.left + smoothRadius, rect.bottom - smoothRadius, smoothRadius, 90.0, 180.0)
    lineTo(rect.left, rect.top + smoothRadius)
    corner(rect.left + smoothRadius, rect.top + smoothRadius, smoothRadius, 180.0, 270.0)
    close()
}

private fun Path.corner(
    centerX: Float,
    centerY: Float,
    radius: Float,
    startDegrees: Double,
    endDegrees: Double
) = traceSmoothCorner(centerX, centerY, radius, startDegrees, endDegrees, lineTo = ::lineTo)

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

    /**
     * Publishes the current uniforms to [node].
     *
     * A [RenderEffect] captures the shader's uniforms when it is built, so changed values only
     * reach the GPU through a freshly built effect — mutating [shader] alone has no effect on an
     * already-installed one.
     */
    fun update(
        width: Float,
        height: Float,
        cornerRadius: Float,
        thickness: Float,
        intensity: Float,
        index: Float,
        foregroundColor: Int
    ) {
        val alpha = Color.alpha(foregroundColor) / 255f
        val red = Color.red(foregroundColor) / 255f * alpha
        val green = Color.green(foregroundColor) / 255f * alpha
        val blue = Color.blue(foregroundColor) / 255f * alpha

        shader.setFloatUniform("resolution", width, height)
        shader.setFloatUniform("center", width / 2f, height / 2f)
        shader.setFloatUniform("size", width / 2f, height / 2f)
        shader.setFloatUniform("radius", cornerRadius, cornerRadius, cornerRadius, cornerRadius)
        shader.setFloatUniform("thickness", thickness)
        shader.setFloatUniform("refract_intensity", intensity)
        shader.setFloatUniform("refract_index", index)
        shader.setFloatUniform("foreground_color_premultiplied", red, green, blue, alpha)

        node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "img"))
    }
}
