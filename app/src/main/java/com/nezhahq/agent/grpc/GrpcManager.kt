package com.nezhahq.agent.grpc

import android.content.Context
import com.nezhahq.agent.util.ConfigStore
import com.nezhahq.agent.util.Logger
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.okhttp.OkHttpChannelBuilder
import proto.NezhaServiceGrpcKt.NezhaServiceCoroutineStub
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

object GrpcManager {

    private var channel: ManagedChannel? = null
    var stub: NezhaServiceCoroutineStub? = null
        private set

    fun initialize(context: Context) {
        val server = ConfigStore.getServer(context)
        val port = ConfigStore.getPort(context)
        val secret = ConfigStore.getSecret(context)
        val uuid = ConfigStore.getUuid(context)
        val useTls = ConfigStore.getUseTls(context)

        if (server.isEmpty() || secret.isEmpty() || uuid.isEmpty()) return

        shutdown() // Close previous if any

        var builder = OkHttpChannelBuilder.forAddress(server, port)
        if (useTls) {
            builder.useTransportSecurity()
            // Optional: If you need to trust all self-signed certs (not recommended for prod, but often needed for self-hosted dash)
            try {
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
                })
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, java.security.SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            builder.usePlaintext()
        }

        Logger.i("Connecting to $server:$port via ChannelBuilder...")
        channel = builder
            .keepAliveTime(10, TimeUnit.SECONDS)
            .keepAliveTimeout(5, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .intercept(AuthInterceptor(secret, uuid))
            .build()

        stub = NezhaServiceCoroutineStub(channel!!)
    }

    fun shutdown() {
        Logger.i("Closing Grpc connection stub.")
        channel?.shutdownNow()
        channel = null
        stub = null
    }
}
