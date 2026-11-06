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
    private val handshakeTimeoutMillis: Long = DashboardSessionWatchdog.HANDSHAKE_TIMEOUT_MS,
    private val geoIpFetchTimeoutMillis: Long = 10_000,
    private val geoIpRetryDelayMillis: Long = 30_000
) {
    private val geoIpReportJob = AtomicReference<Job?>(null)

    suspend fun reportInitial(
        reportHost: suspend (Host) -> Unit
    ) {
        val host = collectHost()
        DashboardSessionWatchdog.callWithin(handshakeTimeoutMillis, "ReportSystemInfo2") {
            reportHost(host)
        }
        // Optional metadata must never hold up the state/task streams or reset their connection.
        requestGeoIpReport()
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
        requestGeoIpReport()
    }

    private fun requestGeoIpReport() {
        // Publish ownership before execution; every callback replaces and cancels the old probe.
        val attempt = runtimeScope.launch(start = CoroutineStart.LAZY) { reportGeoIp() }
        geoIpReportJob.getAndSet(attempt)?.cancel()
        attempt.invokeOnCompletion { geoIpReportJob.compareAndSet(attempt, null) }
        attempt.start()
    }

    private suspend fun reportGeoIp() {
        while (currentCoroutineContext().isActive) {
            try {
                // The collector bounds each address family separately inside this safety budget.
                val geoIp = DashboardSessionWatchdog.callWithin(geoIpFetchTimeoutMillis, "FetchGeoIP") {
                    fetchGeoIp()
                }
                // Resolve after fetching: a reconnect may have replaced the channel meanwhile.
                val report = currentGeoIpReporter()
                if (geoIp != null && report != null) {
                    DashboardSessionWatchdog.callWithin(handshakeTimeoutMillis, "ReportGeoIP") {
                        report(geoIp)
                    }
                    return
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                Logger.e("GeoIP 上报失败（稍后重试，不影响监控连接）", exception)
            }
            delay(geoIpRetryDelayMillis)
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
