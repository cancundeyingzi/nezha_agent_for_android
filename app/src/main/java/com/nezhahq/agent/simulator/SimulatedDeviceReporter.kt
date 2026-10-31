package com.nezhahq.agent.simulator

import com.nezhahq.agent.core.model.SimulatedDeviceConfig

import com.nezhahq.agent.grpc.GrpcChannelFactory
import com.nezhahq.agent.grpc.GrpcTransportMode
import com.nezhahq.agent.util.DashboardSessionWatchdog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import proto.NezhaServiceGrpcKt.NezhaServiceCoroutineStub
import java.util.concurrent.TimeUnit

fun interface SimulatedDeviceReporter {
    suspend fun reportOne(config: SimulatedDeviceConfig, device: SimulatedDevice)
}

class GrpcSimulatedDeviceReporter : SimulatedDeviceReporter {
    override suspend fun reportOne(config: SimulatedDeviceConfig, device: SimulatedDevice) {
        val transportMode = if (config.useTls) GrpcTransportMode.TLS else GrpcTransportMode.PLAINTEXT
        val channel = GrpcChannelFactory.create(
            server = config.server,
            port = config.port,
            secret = config.secret,
            uuid = device.uuid,
            transportMode = transportMode
        )

        try {
            val stub = NezhaServiceCoroutineStub(channel)
            DashboardSessionWatchdog.callWithin(
                DashboardSessionWatchdog.HANDSHAKE_TIMEOUT_MS,
                "Simulator ReportSystemInfo2"
            ) {
                stub.reportSystemInfo2(device.host)
            }
            DashboardSessionWatchdog.callWithin(
                DashboardSessionWatchdog.HANDSHAKE_TIMEOUT_MS,
                "Simulator ReportGeoIP"
            ) {
                stub.reportGeoIP(device.geoIp)
            }
            DashboardSessionWatchdog.callWithin(
                DashboardSessionWatchdog.STATE_RECEIPT_TIMEOUT_MS,
                "Simulator ReportSystemState receipt"
            ) {
                stub.reportSystemState(flowOf(device.state)).first()
            }
        } finally {
            channel.shutdownNow()
            channel.awaitTermination(1, TimeUnit.SECONDS)
        }
    }
}
