package com.nezhahq.agent.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

internal fun interface RuntimeNetworkRegistration : AutoCloseable {
    override fun close()
}

internal fun interface RuntimeNetworkRegistrar {
    fun register(onNetworkAvailable: () -> Unit): RuntimeNetworkRegistration
}

internal class AndroidRuntimeNetworkRegistrar(context: Context) : RuntimeNetworkRegistrar {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

    override fun register(onNetworkAvailable: () -> Unit): RuntimeNetworkRegistration {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkAvailable()
        }
        connectivityManager.registerNetworkCallback(request, callback)
        return IdempotentNetworkRegistration {
            connectivityManager.unregisterNetworkCallback(callback)
        }
    }
}

private class IdempotentNetworkRegistration(
    private val unregister: () -> Unit
) : RuntimeNetworkRegistration {
    private var closed = false

    override fun close() = synchronized(this) {
        if (!closed) {
            unregister()
            closed = true
        }
    }
}
