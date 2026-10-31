package com.nezhahq.agent.ui

import com.nezhahq.agent.util.LogBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogBuffer] 是从 Logger 抽出的纯逻辑（去重 / FIFO 淘汰 / 单调递增 id），
 * 用注入的假时钟即可精确测时序，无需真实等待 30s 去重窗口。
 *
 * 之所以放在 ui 测试包下：并行代理的文件归属边界让本代理只负责 test/.../ui/。
 * 被测类在 util 包，但 internal 可见性是 module 级的，同一 module 内可直接访问。
 */
class LogBufferTest {

    // 可控时钟：改 now 即模拟时间流逝。
    private var now = 1_000L
    private fun newBuffer(max: Int = 200, window: Long = 30_000L) =
        LogBuffer(maxSize = max, dedupWindowMs = window, initialMessage = "seed", clock = { now })

    @Test
    fun seedIsTheFirstEntryWithIdZero() {
        val snapshot = newBuffer().snapshot()
        assertEquals(1, snapshot.size)
        assertEquals(0L, snapshot[0].id)
        assertEquals("seed", snapshot[0].text)
    }

    @Test
    fun distinctMessagesAppendWithStrictlyIncreasingIds() {
        val buffer = newBuffer()
        buffer.add("[t] a", "a")
        val idA = buffer.snapshot().last().id
        now += 10
        buffer.add("[t] b", "b")
        val snapshot = buffer.snapshot()

        assertEquals(3, snapshot.size) // seed + a + b
        val idSeed = snapshot[0].id
        val idB = snapshot[2].id
        assertTrue(idA > idSeed)
        assertTrue(idB > idA)
        // a 的身份在追加 b 后保持不变
        assertEquals(idA, snapshot[1].id)
    }

    @Test
    fun aRepeatWithinTheWindowKeepsTheSameIdAndCountsUp() {
        val buffer = newBuffer()
        buffer.add("[t] boom", "boom")
        val firstId = buffer.snapshot().last().id
        val sizeAfterFirst = buffer.snapshot().size

        now += 5_000 // 仍在 30s 窗口内
        buffer.add("[t] boom", "boom")
        val afterRepeat = buffer.snapshot()

        // size 不变（未追加新条目），id 不变（同一条身份），文本带上 ×2
        assertEquals(sizeAfterFirst, afterRepeat.size)
        assertEquals(firstId, afterRepeat.last().id)
        assertTrue(afterRepeat.last().text.endsWith("×2"))
    }

    @Test
    fun aRepeatAfterTheWindowStartsANewEntryWithANewId() {
        val buffer = newBuffer(window = 30_000L)
        buffer.add("[t] boom", "boom")
        val firstId = buffer.snapshot().last().id

        now += 30_001 // 超过窗口
        buffer.add("[t] boom", "boom")
        val after = buffer.snapshot()

        // 视为新一轮：追加了新条目，且 id 是全新的
        assertEquals(3, after.size) // seed + 窗口内第一次 + 窗口外第二次
        assertNotEquals(firstId, after.last().id)
        assertTrue(after.last().id > firstId)
    }

    @Test
    fun evictionDropsOldestButLeavesSurvivorIdsStable() {
        // 容量设为 3：seed + a + b 恰好占满，加入 c 时必须淘汰最旧的 seed。
        val buffer = newBuffer(max = 3)
        buffer.add("[t] a", "a"); now += 1
        buffer.add("[t] b", "b"); now += 1
        val bId = buffer.snapshot().last().id
        buffer.add("[t] c", "c")
        val afterC = buffer.snapshot()

        assertEquals(3, afterC.size)
        // seed（id=0）已被淘汰
        assertTrue(afterC.none { it.id == 0L })
        // 关键：幸存条目的 id 不因淘汰而改变——这正是日志窗稳定 key 的依据。
        assertTrue(afterC.any { it.id == bId })
    }
}
