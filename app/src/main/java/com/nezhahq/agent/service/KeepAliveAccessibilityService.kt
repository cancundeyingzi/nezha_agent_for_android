package com.nezhahq.agent.service

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.nezhahq.agent.util.Logger

/**
 * 这是一个为了提高保活优先级而存在的无障碍服务。
 * 只要用户在设置中开启该服务，Android 系统就会尽量不杀掉包含该服务的进程。
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Logger.i("KeepAliveAccessibilityService: 无障碍保活服务已连接，提升保活等级")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不做任何实质性的事件处理，纯粹用来"占坑"保活
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // 监听音量键（无实质影响，但不拦截）
        // 这使得该服务具有实际的“按键监听”功能，避免被系统判定为纯占坑的空服务
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                // 可选：记录一条轻量级日志证明我们在工作
                // Logger.d("KeepAliveAccessibilityService: 检测到音量键按下")
            }
        }
        // 返回 false 表示不拦截事件，让按键事件继续传递给系统和其他应用
        return super.onKeyEvent(event)
    }

    override fun onInterrupt() {
        Logger.i("KeepAliveAccessibilityService: 无障碍服务被中断")
    }
}
