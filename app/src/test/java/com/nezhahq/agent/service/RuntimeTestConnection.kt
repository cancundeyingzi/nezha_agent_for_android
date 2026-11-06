package com.nezhahq.agent.service

import com.nezhahq.agent.grpc.GrpcConnection
import com.nezhahq.agent.grpc.GrpcTransportMode
import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.MethodDescriptor
import proto.NezhaServiceGrpcKt.NezhaServiceCoroutineStub

/** RPC flows are supplied by each test; accidental access to a real stub fails immediately. */
internal class RuntimeTestConnection(
    override val transportMode: GrpcTransportMode = GrpcTransportMode.TLS
) : GrpcConnection {
    var connects = 0
        private set
    var disconnects = 0
        private set
    val preservedStates = mutableListOf<Boolean>()
    override var stub: NezhaServiceCoroutineStub? = null
        private set

    override fun connect() {
        connects++
        stub = NezhaServiceCoroutineStub(object : io.grpc.Channel() {
            override fun authority() = "runtime-component-test"
            override fun <RequestT : Any?, ResponseT : Any?> newCall(
                methodDescriptor: MethodDescriptor<RequestT, ResponseT>,
                callOptions: CallOptions
            ): ClientCall<RequestT, ResponseT> = error("Unexpected RPC: ${methodDescriptor.fullMethodName}")
        })
    }

    override fun disconnect(preserveConnectionState: Boolean) {
        disconnects++
        preservedStates += preserveConnectionState
        stub = null
    }

    override fun close() { stub = null }
}
