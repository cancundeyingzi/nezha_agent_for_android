package com.nezhahq.agent.ui

import com.nezhahq.agent.util.PermissionChecker.PermissionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PermissionKey] 的不变量。UI 的 `when (item.key)` 依赖它被编译器穷举；两处特判
 * （按钮可用性 actionEnabled、按钮文案“启用”/“去授权”）依赖“恰好只有开机自启是本地开关”。
 * 若日后新增权限项破坏了这些前提，这里会先失败。
 */
class PermissionKeyTest {

    @Test
    fun thereAreExactlyTheEightKnownKeys() {
        assertEquals(8, PermissionKey.entries.size)
    }

    @Test
    fun onlyAutoStartIsALocalToggle() {
        // 本地开关有且仅有开机自启（应用内切换、受 canEditConfig 约束、文案为“启用”）。
        assertEquals(
            listOf(PermissionKey.AUTO_START),
            PermissionKey.entries.filter { it.isLocalToggle }
        )
        // 反向确认：其余 7 项都不是本地开关（都跳系统页或弹运行时授权）。
        assertEquals(7, PermissionKey.entries.count { !it.isLocalToggle })
    }

    @Test
    fun autoStartReportsItselfAsALocalToggle() {
        assertTrue(PermissionKey.AUTO_START.isLocalToggle)
        assertTrue(!PermissionKey.SMS.isLocalToggle)
    }
}
