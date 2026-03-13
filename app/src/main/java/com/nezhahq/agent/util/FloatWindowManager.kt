package com.nezhahq.agent.util

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.provider.Settings
import kotlin.random.Random

object FloatWindowManager {
    private var isAdded = false
    private var floatView: View? = null

    fun show(context: Context) {
        if (isAdded) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Logger.e("FloatWindowManager: 没有悬浮窗权限，跳过悬浮窗保活")
            return
        }

        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            floatView = FrameLayout(context).apply {
                // 1x1 全透明像素，对用户不可见
                setBackgroundColor(0x00000000)
            }

            // 随机偏移坐标，避免某些ROM对固定坐标(0,0)的透明窗口做查杀
            val randomX = Random.nextInt(10, 100)
            val randomY = Random.nextInt(10, 100)

            val params = WindowManager.LayoutParams(
                1, 1,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSPARENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = randomX
                y = randomY
            }

            windowManager.addView(floatView, params)
            isAdded = true
            Logger.i("FloatWindowManager: 悬浮窗已添加于 ($randomX, $randomY)")
        } catch (e: Exception) {
            Logger.e("FloatWindowManager: 添加悬浮窗失败", e)
        }
    }

    fun hide(context: Context) {
        if (!isAdded) return
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (floatView != null) {
                windowManager.removeView(floatView)
                floatView = null
            }
            isAdded = false
            Logger.i("FloatWindowManager: 悬浮窗已移除")
        } catch (e: Exception) {
            Logger.e("FloatWindowManager: 移除悬浮窗失败", e)
        }
    }
}
