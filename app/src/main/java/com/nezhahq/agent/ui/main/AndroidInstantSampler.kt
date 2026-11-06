package com.nezhahq.agent.ui.main

import android.content.Context
import com.nezhahq.agent.collector.GpuCollector
import com.nezhahq.agent.collector.SystemInfoCollector
import com.nezhahq.agent.collector.SystemStateCollector
import com.nezhahq.agent.util.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class AndroidInstantSampler(context: Context) {
    private val context = context.applicationContext

    suspend fun sample(): String = withContext(Dispatchers.IO) {
        val ctx = context
        val gpuCollector = GpuCollector()
        val collector = SystemStateCollector(ctx, gpuCollector, Dispatchers.IO)
        val isPrivileged = RootShell.isAuthorized()

        // ── 第 1 次采样：建立差值基准 ──────────────────────────────
        // 网络速度和 CPU 使用率均基于差值法（两次采样的变化量 / 时间差），
        // 首次调用时无历史基准，差值必然为 0，因此需先"预热"一次。
        collector.getState(isPrivileged)

        // ── 等待采样间隔 ──────────────────────────────────────────
        // 1.5 秒足以让 /proc/stat 和网络流量产生可测量的变化量，
        // 同时对用户体验的等待时间也在可接受范围内。
        delay(1500L)

        // ── 第 2 次采样：获取真实差值数据 ──────────────────────────
        val state = collector.getState(isPrivileged)
        val hostInfo = SystemInfoCollector.getHostInfo(
            context = ctx,
            appVersion = "test",
            gpuCollector = gpuCollector,
            isRootMode = isPrivileged
        )
        // 从 CPU 显示名称中提取真实核心数
        // CPU 名称格式为 "{SoC名称} {核心数} {Physical/Virtual} Core"
        val cpuDisplayName = hostInfo.cpuList.firstOrNull() ?: "N/A"
        val actualCoreCount = Regex("(\\d+)\\s+(?:Physical|Virtual)\\s+Core")
            .find(cpuDisplayName)?.groupValues?.get(1) ?: "N/A"
        buildString {
            appendLine("═══ 采集结果预览 ═══")
            appendLine("▸ CPU 使用率: ${"%.1f".format(state.cpu)}%")
            appendLine("▸ CPU 核心数: $actualCoreCount")
            appendLine("▸ CPU 名称: $cpuDisplayName")
            appendLine("▸ Load: ${"%.2f".format(state.load1)} / ${"%.2f".format(state.load5)} / ${"%.2f".format(state.load15)}")
            appendLine("▸ 内存已用: ${state.memUsed / 1024 / 1024} MB")
            appendLine("▸ Swap 已用: ${state.swapUsed / 1024 / 1024} MB")
            appendLine("▸ 磁盘已用: ${state.diskUsed / 1024 / 1024} MB")
            appendLine("▸ 网络速度: ↓${state.netInSpeed / 1024} KB/s  ↑${state.netOutSpeed / 1024} KB/s")
            appendLine("▸ TCP 连接: ${state.tcpConnCount}")
            appendLine("▸ UDP 连接: ${state.udpConnCount}")
            appendLine("▸ 进程数: ${state.processCount}")
            appendLine("▸ 温度: ${state.temperaturesList.firstOrNull()?.let { "${it.name} ${it.temperature}°C" } ?: "N/A"}")
            // GPU 信息
            val gpuName = gpuCollector.getGpuNames().firstOrNull()
            appendLine("▸ GPU: ${gpuName ?: "N/A"}")
            val gpuUsages = state.gpuList
            appendLine("▸ GPU 使用率: ${
                if (gpuUsages.isNotEmpty()) "${"%.1f".format(gpuUsages.first())}%"
                else "N/A（需 Root/Shizuku）"
            }")
            appendLine()
            appendLine("═══ 权限状态 ═══")
            appendLine("▸ Shell 会话: ${RootShell.getSessionType() ?: "无（普通模式）"}")
            appendLine("▸ Shell 存活: ${if (RootShell.isAlive()) "✅" else "❌"}")
        }
    }
}
