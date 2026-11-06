package com.nezhahq.agent.collector

import com.nezhahq.agent.util.awaitResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import proto.Nezha.GeoIP
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

object GeoIpCollector {
    private const val MACOS_CHROME_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val traceEndpoints = listOf(
        "https://blog.cloudflare.com/cdn-cgi/trace",
        "https://developers.cloudflare.com/cdn-cgi/trace",
        "https://hostinger.com/cdn-cgi/trace",
        "https://ahrefs.com/cdn-cgi/trace"
    )

    private val ipv4Client = createClient(IpFamily.V4)
    private val ipv6Client = createClient(IpFamily.V6)

    private val lookup = GeoIpLookup(fetch = { family ->
        fetchIp(family, if (family == IpFamily.V4) ipv4Client else ipv6Client)
    })

    suspend fun fetchGeoIP(): GeoIP? = withContext(Dispatchers.IO) {
        lookup.fetch()
    }

    private fun createClient(family: IpFamily): OkHttpClient {
        return OkHttpClient.Builder()
            .dns(FamilyDns(family))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Tries each endpoint in turn until one answers.
     *
     * GeoIpLookup bounds the whole endpoint sequence independently for each family. Cancellation
     * aborts the in-flight call and stops the loop, including during runtime teardown.
     */
    private suspend fun fetchIp(family: IpFamily, client: OkHttpClient): FetchResult? {
        for (endpoint in traceEndpoints) {
            currentCoroutineContext().ensureActive()

            val result = try {
                requestTrace(endpoint, family, client)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }

            if (result != null) return result
        }

        return null
    }

    /** Enqueues rather than blocking, so no thread is held for the duration of the call. */
    private suspend fun requestTrace(
        endpoint: String,
        family: IpFamily,
        client: OkHttpClient
    ): FetchResult? {
        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", MACOS_CHROME_UA)
            .build()

        return client.newCall(request).awaitResult { response ->
            if (!response.isSuccessful) return@awaitResult null
            val trace = response.body?.string()?.let(::parseTraceBody) ?: return@awaitResult null
            validateIpForFamily(trace.ip, family)?.let { ip -> FetchResult(ip, trace.countryCode) }
        }
    }

    internal fun parseTraceBody(body: String): TraceBody {
        var ip: String? = null
        var countryCode: String? = null
        val lines = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        for (line in lines) {
            when {
                line.startsWith("ip=") -> ip = line.substringAfter("ip=").trim()
                line.startsWith("loc=") -> countryCode = line.substringAfter("loc=").trim()
            }
        }

        if (ip == null && lines.size == 1 && !lines.first().contains("=")) {
            ip = lines.first()
        }

        return TraceBody(
            ip = ip?.takeIf { it.isNotBlank() },
            countryCode = countryCode?.takeIf { it.isNotBlank() }
        )
    }

    internal fun validateIpForFamily(ip: String?, family: IpFamily): String? {
        val normalizedIp = ip
            ?.trim()
            ?.removeSurrounding("[", "]")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        if (!family.looksLike(normalizedIp)) return null

        val address = runCatching { InetAddress.getByName(normalizedIp) }.getOrNull()
            ?: return null

        return if (family.matches(address)) address.hostAddress else null
    }

    private class FamilyDns(private val family: IpFamily) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname).filter { family.matches(it) }
            if (addresses.isEmpty()) {
                throw UnknownHostException("No ${family.label} address for $hostname")
            }
            return addresses
        }
    }

    internal enum class IpFamily(val label: String) {
        V4("IPv4") {
            override fun matches(address: InetAddress): Boolean = address is Inet4Address
            override fun looksLike(ip: String): Boolean = IPV4_REGEX.matches(ip)
        },
        V6("IPv6") {
            override fun matches(address: InetAddress): Boolean = address is Inet6Address
            override fun looksLike(ip: String): Boolean = ":" in ip
        };

        abstract fun matches(address: InetAddress): Boolean
        abstract fun looksLike(ip: String): Boolean
    }

    internal data class TraceBody(
        val ip: String?,
        val countryCode: String?
    )

    internal data class FetchResult(
        val ip: String,
        val countryCode: String?
    )

    private val IPV4_REGEX =
        Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")
}

