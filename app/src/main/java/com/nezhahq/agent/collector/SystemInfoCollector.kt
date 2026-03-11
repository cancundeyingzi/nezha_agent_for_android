package com.nezhahq.agent.collector

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import proto.Nezha.Host
import java.io.File

object SystemInfoCollector {

    fun getHostInfo(context: Context, appVersion: String): Host {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val statFs = StatFs(Environment.getDataDirectory().path)
        val diskTotal = statFs.blockCountLong * statFs.blockSizeLong

        // Try reading CPU info
        val cpuNames = mutableListOf<String>()
        try {
            val cpuInfo = File("/proc/cpuinfo").readText()
            val matcher = Regex("Hardware\\s+:\\s+(.*)").find(cpuInfo)
            if (matcher != null) {
                cpuNames.add(matcher.groupValues[1])
            } else {
                cpuNames.add(Build.HARDWARE)
            }
        } catch (e: Exception) {
            cpuNames.add(Build.HARDWARE)
        }

        return Host.newBuilder()
            .setPlatform("Android")
            .setPlatformVersion(Build.VERSION.RELEASE)
            .addAllCpu(cpuNames)
            .setMemTotal(memInfo.totalMem)
            .setDiskTotal(diskTotal)
            .setSwapTotal(0) // Physical swap is hard to read on non-root Android
            .setArch(System.getProperty("os.arch") ?: Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            .setVirtualization("none")
            .setBootTime((System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 1000)
            .setVersion(appVersion)
            .build()
    }
}
