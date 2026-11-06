package com.nezhahq.agent.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.nezhahq.agent.util.DashboardSessionWatchdog
import com.nezhahq.agent.util.Logger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import proto.Nezha.GeoIP
import proto.Nezha.Host
import proto.Nezha.Receipt
import proto.Nezha.State

/** Borrows the runtime scope for network probes and the connection scope for state reporting. */
internal class MetricReporter(
    private val runtimeScope: CoroutineScope,
    private val collectHost: suspend () -> Host,
    private val collectState: suspend () -> State,
    private val fetchGeoIp: suspend () -> GeoIP?,
    private val currentGeoIpReporter: () -> (suspend (GeoIP) -> Unit)?,
    private val stateReportIntervalMillis: Long = DashboardSessionWatchdog.STATE_REPORT_INTERVAL_MS,
    private val stateReceiptTimeoutMillis: Long = DashboardSessionWatchdog.STATE_RECEIPT_TIMEOUT_MS,
    private val handshakeTimeoutMillis: Long = DashboardSessionWatchdog.HANDSHAKE_TIMEOUT_MS
) {
    private val geoIpReportJob = AtomicReference<Job?>(null)

    suspend fun reportInitial(
        reportHost: suspend (Host) -> Unit,
        reportGeoIp: suspend (GeoIP) -> Unit
    ) {
        val host = collectHost()
        DashboardSessionWatchdog.callWithin(handshakeTimeoutMillis, "ReportSystemInfo2") {
            reportHost(host)
        }
        // GeoIP probing is part of the handshake budget, before either continuous stream starts.
        val geoIp = DashboardSessionWatchdog.callWithin(handshakeTimeoutMillis, "FetchGeoIP") {
            fetchGeoIp()
        }
        geoIp?.let {
            DashboardSessionWatchdog.callWithin(handshakeTimeoutMillis, "ReportGeoIP") {
                reportGeoIp(it)
            }
        }
    }

    suspend fun reportState(
        openStream: (Flow<State>) -> Flow<Receipt>,
        onReceipt: () -> Unit
    ): Unit = coroutineScope {
        val states = flow {
            while (currentCoroutineContext().isActive) {
                emit(collectState())
                delay(stateReportIntervalMillis)
            }
        }
        val receipts = openStream(states).produceIn(this)
        try {
            while (isActive) {
                DashboardSessionWatchdog.receiveWithin(
                    receipts, stateReceiptTimeoutMillis, "ReportSystemState receipt"
                )
                onReceipt()
            }
        } finally {
            receipts.cancel()
        }
    }

    fun onNetworkAvailable() {
        Logger.i("Network dynamically available, polling full GeoIP metadata...")
        // Publish ownership before execution; every callback replaces and cancels the old probe.
        val attempt = runtimeScope.launch(start = CoroutineStart.LAZY) { reportNetworkGeoIp() }
        geoIpReportJob.getAndSet(attempt)?.cancel()
        attempt.invokeOnCompletion { geoIpReportJob.compareAndSet(attempt, null) }
        attempt.start()
    }

    private suspend fun reportNetworkGeoIp() {
        try {
            val geoIp = DashboardSessionWatchdog.callWithin(handshakeTimeoutMillis, "NetworkCallback FetchGeoIP") {
                fetchGeoIp()
            }
            // Resolve after the fetch, as a reconnect may have replaced the channel meanwhile.
            val report = currentGeoIpReporter()
            if (geoIp != null && report != null) {
                DashboardSessionWatchdog.callWithin(handshakeTimeoutMillis, "NetworkCallback ReportGeoIP") {
                    report(geoIp)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (exception: Exception) {
            Logger.e("GeoIP 上报失败（将在下次连接成功后重试）", exception)
        }
    }
}

internal fun runtimeAppVersionName(context: Context): String = try {
    val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    packageInfo.versionName ?: "unknown"
} catch (exception: Exception) {
    Logger.e("AgentRuntime: 读取应用版本号失败", exception)
    "unknown"
}
