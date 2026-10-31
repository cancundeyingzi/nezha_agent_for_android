package com.nezhahq.agent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.nezhahq.agent.util.Logger
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 这是一个为了提高保活优先级而存在的无障碍服务。
 * 只要用户在设置中开启该服务，Android 系统就会尽量不杀掉包含该服务的进程。
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    sealed class ScreenshotSaveResult {
        data class Success(val path: String, val bytes: Long) : ScreenshotSaveResult()
        data class Failure(val reason: String) : ScreenshotSaveResult()
    }

    companion object {
        @Volatile
        private var activeService: KeepAliveAccessibilityService? = null

        suspend fun saveScreenshot(path: String): ScreenshotSaveResult {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return ScreenshotSaveResult.Failure("无障碍截图仅支持 Android 11(API 30) 及以上系统")
            }

            val service = activeService
                ?: return ScreenshotSaveResult.Failure("无障碍服务未连接")

            return withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
                service.captureScreenshotToFile(File(path))
            } ?: ScreenshotSaveResult.Failure("无障碍截图超时")
        }

        private const val SCREENSHOT_TIMEOUT_MS = 10_000L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
        Logger.i("KeepAliveAccessibilityService: 无障碍保活服务已连接，提升保活等级")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不做任何实质性的事件处理，纯粹用来"占坑"保活
    }

    override fun onInterrupt() {
        Logger.i("KeepAliveAccessibilityService: 无障碍服务被中断")
    }

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun captureScreenshotToFile(targetFile: File): ScreenshotSaveResult =
        suspendCancellableCoroutine { continuation ->
            val executor = Executors.newSingleThreadExecutor()
            continuation.invokeOnCancellation { executor.shutdown() }

            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val result = try {
                                writeScreenshotResult(screenshot, targetFile)
                            } catch (e: Exception) {
                                Logger.e("KeepAliveAccessibilityService: 保存截图失败 (Error=${e.javaClass.simpleName})")
                                ScreenshotSaveResult.Failure(e.message ?: "保存截图失败")
                            } finally {
                                executor.shutdown()
                            }

                            if (continuation.isActive) continuation.resume(result)
                        }

                        override fun onFailure(errorCode: Int) {
                            executor.shutdown()
                            val reason = "无障碍截图失败: ${describeScreenshotError(errorCode)}"
                            Logger.e("KeepAliveAccessibilityService: $reason")
                            if (continuation.isActive) {
                                continuation.resume(ScreenshotSaveResult.Failure(reason))
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                executor.shutdown()
                Logger.e("KeepAliveAccessibilityService: 调用无障碍截图失败 (Error=${e.javaClass.simpleName})")
                if (continuation.isActive) {
                    continuation.resume(
                        ScreenshotSaveResult.Failure(e.message ?: "调用无障碍截图失败")
                    )
                }
            }
        }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun writeScreenshotResult(
        screenshot: ScreenshotResult,
        targetFile: File
    ): ScreenshotSaveResult {
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return ScreenshotSaveResult.Failure("无法创建目录: ${parent.absolutePath}")
        }

        val hardwareBuffer = screenshot.hardwareBuffer
        try {
            val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                ?: return ScreenshotSaveResult.Failure("无法读取截图缓冲区")
            val softwareBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
            try {
                FileOutputStream(targetFile).use { output ->
                    if (!softwareBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        return ScreenshotSaveResult.Failure("PNG 编码失败")
                    }
                }
            } finally {
                softwareBitmap.recycle()
                hardwareBitmap.recycle()
            }
        } finally {
            hardwareBuffer.close()
        }

        return ScreenshotSaveResult.Success(targetFile.absolutePath, targetFile.length())
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun describeScreenshotError(errorCode: Int): String {
        return when (errorCode) {
            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "系统内部错误"
            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截图过于频繁，请稍后重试"
            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "无效显示器"
            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "无障碍截图权限不可用"
            ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "当前画面包含安全窗口，系统拒绝截图"
            else -> "错误码 $errorCode"
        }
    }
}
