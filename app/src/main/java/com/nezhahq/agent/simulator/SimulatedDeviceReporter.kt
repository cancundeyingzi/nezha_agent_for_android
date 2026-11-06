package com.nezhahq.agent.simulator

import com.nezhahq.agent.core.model.SimulatedDeviceConfig

import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.grpc.GrpcConnection
import com.nezhahq.agent.grpc.ManagedGrpcConnection
import com.nezhahq.agent.util.DashboardSessionWatchdog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

fun interface SimulatedDeviceReporter {
    suspend fun reportOne(config: SimulatedDeviceConfig, device: SimulatedDevice)
}

/** The factory creates only a wrapper. The session registers it before connect may create a channel. */
internal fun interface SimulatorConnectionFactory {
    fun create(config: SimulatedDeviceConfig, device: SimulatedDevice): GrpcConnection
}

internal class GrpcSimulatedDeviceReporter(
    private val owner: SimulatorConnectionOwner,
    private val factory: SimulatorConnectionFactory = SimulatorConnectionFactory { config, device ->
        ManagedGrpcConnection(AgentConfig(config.server, config.port, config.secret, device.uuid,
            useTls = config.useTls, rootMode = false))
    },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val report: suspend (GrpcConnection, SimulatedDevice) -> Unit = { connection, device ->
        val stub = checkNotNull(connection.stub)
        DashboardSessionWatchdog.callWithin(DashboardSessionWatchdog.HANDSHAKE_TIMEOUT_MS, "Simulator ReportSystemInfo2") {
            stub.reportSystemInfo2(device.host)
        }
        DashboardSessionWatchdog.callWithin(DashboardSessionWatchdog.HANDSHAKE_TIMEOUT_MS, "Simulator ReportGeoIP") {
            stub.reportGeoIP(device.geoIp)
        }
        DashboardSessionWatchdog.callWithin(DashboardSessionWatchdog.STATE_RECEIPT_TIMEOUT_MS, "Simulator ReportSystemState receipt") {
            stub.reportSystemState(flowOf(device.state)).first()
        }
    }
) : SimulatedDeviceReporter {
    override suspend fun reportOne(config: SimulatedDeviceConfig, device: SimulatedDevice) {
        val reportContext = currentCoroutineContext()
        reportContext.ensureActive()
        if (!owner.accepting) throw CancellationException("Simulator session stopped")
        val connection = factory.create(config, device)
        if (!owner.register(connection)) {
            // No native resource exists until connect; this wrapper lost admission during creation.
            connection.close()
            throw CancellationException("Simulator session stopped")
        }
        var primaryFailure: Throwable? = null
        try {
            currentCoroutineContext().ensureActive()
            if (!owner.accepting) throw CancellationException("Simulator session stopped")
            connection.connect()
            currentCoroutineContext().ensureActive()
            report(connection, device)
        } catch (failure: Throwable) {
            primaryFailure = failure
        }
        var cleanupFailure: Throwable? = null
        var cleanupCancellation: CancellationException? = null
        try {
            withContext(NonCancellable + io) {
                try {
                    connection.close()
                    owner.closed(connection)
                } catch (cleanup: Throwable) {
                    cleanupFailure = cleanup
                    owner.closeFailed(cleanup)
                }
            }
        } catch (cancelled: CancellationException) {
            // Returning from IO to a cancelled dispatcher may throw a recovered copy. Keep the
            // RPC's cancellation as primary and attach cleanup AFTER crossing that boundary.
            cleanupCancellation = cancelled
        } catch (cleanup: Throwable) {
            cleanupFailure = cleanup
            owner.closeFailed(cleanup)
        }
        val contextCancellation = if (!reportContext.isActive) {
            try { reportContext.ensureActive(); null }
            catch (cancelled: CancellationException) { cancelled }
        } else null
        val primary = primaryFailure
        val cleanup = cleanupFailure
        val cancellation = primary as? CancellationException ?: contextCancellation ?: cleanupCancellation
        if (cancellation != null) {
            if (primary != null && primary !is CancellationException) cancellation.addSuppressed(primary)
            cleanup?.let {
                cancellation.addSuppressed(it)
                // Job cancellation may predate the reporter and retain its original exception.
                // Keep the same cleanup evidence there as well, so Deferred's recovered copy does
                // not hide it after the exact reporter cancellation has left this boundary.
                if (contextCancellation != null && contextCancellation !== cancellation) contextCancellation.addSuppressed(it)
            }
            throw cancellation
        }
        if (cleanup != null) {
            throw SimulatorCleanupException(cleanup).also { terminal -> primary?.let(terminal::addSuppressed) }
        }
        primary?.let { throw it }
    }
}
