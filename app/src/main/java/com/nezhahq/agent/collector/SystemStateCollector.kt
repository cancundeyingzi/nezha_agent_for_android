package com.nezhahq.agent.collector

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import proto.Nezha.State
import proto.Nezha.State_SensorTemperature
import java.io.BufferedReader
import java.io.InputStreamReader

class SystemStateCollector(private val context: Context) {

    private var lastRxBytes = TrafficStats.getTotalRxBytes()
    private var lastTxBytes = TrafficStats.getTotalTxBytes()
    private var lastTimeMs = SystemClock.elapsedRealtime()

    fun getState(): State {
        // RAM
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val memUsed = memInfo.totalMem - memInfo.availMem

        // Disk
        val statFs = StatFs(Environment.getDataDirectory().path)
        val diskTotal = statFs.blockCountLong * statFs.blockSizeLong
        val diskFree = statFs.availableBlocksLong * statFs.blockSizeLong
        val diskUsed = diskTotal - diskFree

        // Network Speed calculate
        val currentRx = TrafficStats.getTotalRxBytes()
        val currentTx = TrafficStats.getTotalTxBytes()
        val currentTime = SystemClock.elapsedRealtime()
        val timeDiff = currentTime - lastTimeMs
        
        var rxSpeed = 0L
        var txSpeed = 0L

        if (timeDiff > 0) {
            rxSpeed = ((currentRx - lastRxBytes) * 1000 / timeDiff)
            txSpeed = ((currentTx - lastTxBytes) * 1000 / timeDiff)
        }

        lastRxBytes = currentRx
        lastTxBytes = currentTx
        lastTimeMs = currentTime

        // Temperature (Use battery temp as system fallback)
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.toDouble()?.div(10) ?: 0.0

        val sensorTemp = State_SensorTemperature.newBuilder()
            .setName("Battery")
            .setTemperature(temp)
            .build()
            
        // CPU Usage (Approximate using top command for non-root, or 0.0 if fail)
        var cpuUsage = 0.0
        try {
            val process = Runtime.getRuntime().exec(arrayOf("top", "-n", "1", "-d", "1"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line?.contains("cpu") == true || line?.contains("User") == true) {
                    val match = Regex("(\\d+)%\\s*user").find(line!!)
                    val sysMatch = Regex("(\\d+)%\\s*sys").find(line!!)
                    if (match != null && sysMatch != null) {
                        cpuUsage = match.groupValues[1].toDouble() + sysMatch.groupValues[1].toDouble()
                        break
                    }
                }
            }
            process.destroy()
        } catch (e: Exception) {
            // Ignore if restricted
        }

        return State.newBuilder()
            .setCpu(cpuUsage)
            .setMemUsed(memUsed)
            .setSwapUsed(0)
            .setDiskUsed(diskUsed)
            .setNetInTransfer(currentRx)
            .setNetOutTransfer(currentTx)
            .setNetInSpeed(rxSpeed)
            .setNetOutSpeed(txSpeed)
            .setUptime(SystemClock.elapsedRealtime() / 1000)
            .setLoad1(0.0)
            .setLoad5(0.0)
            .setLoad15(0.0)
            .setTcpConnCount(0)
            .setUdpConnCount(0)
            .setProcessCount(0)
            .addTemperatures(sensorTemp)
            .build()
    }
}
