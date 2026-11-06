package com.nezhahq.agent.service

import android.content.Context
import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.grpc.ConnectionStateHolder
import com.nezhahq.agent.grpc.ManagedGrpcConnection

/** The process graph supplies construction; each Service still owns its runtime and status sink. */
internal fun interface AgentServiceRuntimeFactory {
    fun create(config: AgentConfig, connectionState: ConnectionStateHolder, statusSink: (String) -> Unit): AgentRuntimeHandle
}

internal class AndroidAgentServiceRuntimeFactory(private val context: Context) : AgentServiceRuntimeFactory {
    override fun create(
        config: AgentConfig,
        connectionState: ConnectionStateHolder,
        statusSink: (String) -> Unit
    ): AgentRuntimeHandle = AgentRuntime(
        context = context,
        config = config,
        statusSink = { state, message ->
            connectionState.updateState(state)
            statusSink(message)
        },
        grpcConnection = ManagedGrpcConnection(config, connectionState::updateState)
    )
}
