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
import com.nezhahq.agent.util.ConfigStore
import com.nezhahq.agent.util.Logger

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
            
        var cpuUsage = 0.0
        var processCount = 0L
        var tcpConnCount = 0L
        var udpConnCount = 0L
        
        val isRootMode = ConfigStore.getRootMode(context)

        try {
            // CPU Usage & Process Count using top
            val topCmd = if (isRootMode) arrayOf("su", "-c", "top -n 1 -d 1") else arrayOf("top", "-n", "1", "-d", "1")
            val processTop = Runtime.getRuntime().exec(topCmd)
            val readerTop = BufferedReader(InputStreamReader(processTop.inputStream))
            var line: String?
            while (readerTop.readLine().also { line = it } != null) {
                if (line?.contains("cpu") == true || line?.contains("User") == true) {
                    val match = Regex("(\\d+(\\.\\d+)?)%\\s*user").find(line!!)
                    val sysMatch = Regex("(\\d+(\\.\\d+)?)%\\s*sys").find(line!!)
                    if (match != null && sysMatch != null) {
                        cpuUsage = match.groupValues[1].toDouble() + sysMatch.groupValues[1].toDouble()
                    }
                }
                if (line?.contains("Tasks:") == true || line?.contains("Processes:") == true) {
                    val procMatch = Regex("(\\d+)\\s*(total|Tasks)").find(line!!)
                    if (procMatch != null) processCount = procMatch.groupValues[1].toLong()
                }
            }
            processTop.waitFor()

            // If non-root or missing process count from top, fallback to limited ps
            if (processCount == 0L) {
                val psCmd = if (isRootMode) arrayOf("su", "-c", "ps -A | wc -l") else arrayOf("sh", "-c", "ps | wc -l")
                val p = Runtime.getRuntime().exec(psCmd)
                val rd = BufferedReader(InputStreamReader(p.inputStream))
                processCount = rd.readLine()?.trim()?.toLongOrNull() ?: 0L
                p.waitFor()
            }
            
            // Connection counts using ss or netstat
            val netCmd = if (isRootMode) arrayOf("su", "-c", "ss -tunA tcp,udp | grep -v State") else arrayOf("sh", "-c", "cat /proc/net/tcp /proc/net/tcp6 /proc/net/udp /proc/net/udp6 | grep -v sl")
            val netProcess = Runtime.getRuntime().exec(netCmd)
            val netReader = BufferedReader(InputStreamReader(netProcess.inputStream))
            var netLine: String?
            while (netReader.readLine().also { netLine = it } != null) {
                if (isRootMode) {
                    if (netLine?.contains("tcp") == true) tcpConnCount++
                    else if (netLine?.contains("udp") == true) udpConnCount++
                } else {
                    // Fallback parse of /proc/net (won't get all on unrooted due to permissions, but gets app's at least)
                    // The paths /proc/net/tcp usually contain hex entries
                    // Since it's a rough fallback, we just arbitrarily divide lines to represent both tcp/udp based on context size
                    tcpConnCount++ // Approximate
                }
            }
            netProcess.waitFor()
            
        } catch (e: Exception) {
            Logger.e("StateCollector execution restricted", e)
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
            .setTcpConnCount(tcpConnCount)
            .setUdpConnCount(udpConnCount)
            .setProcessCount(processCount)
            .addTemperatures(sensorTemp)
            .build()
    }
}
