package com.nezhahq.agent.collector

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import proto.Nezha.GeoIP
import proto.Nezha.IP

object GeoIpCollector {
    private val client = OkHttpClient()

    suspend fun fetchGeoIP(): GeoIP? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://blog.cloudflare.com/cdn-cgi/trace")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null

                val body = response.body?.string() ?: return@use null
                
                var ipAddr = ""
                var loc = ""
                
                body.lines().forEach { line ->
                    if (line.startsWith("ip=")) {
                        ipAddr = line.substringAfter("ip=")
                    } else if (line.startsWith("loc=")) {
                        loc = line.substringAfter("loc=")
                    }
                }

                val ipMessage = IP.newBuilder()
                if (ipAddr.contains(":")) {
                    ipMessage.setIpv6(ipAddr)
                } else {
                    ipMessage.setIpv4(ipAddr)
                }

                return@use GeoIP.newBuilder()
                    .setIp(ipMessage.build())
                    .setCountryCode(loc)
                    .build()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}


