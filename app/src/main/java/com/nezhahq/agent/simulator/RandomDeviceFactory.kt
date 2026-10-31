package com.nezhahq.agent.simulator

import com.nezhahq.agent.util.ByteUnits.GIB
import com.nezhahq.agent.util.ByteUnits.MIB
import proto.Nezha.GeoIP
import proto.Nezha.Host
import proto.Nezha.IP
import proto.Nezha.State
import proto.Nezha.State_SensorTemperature
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

object RandomDeviceFactory {
    private val publicIpv4FirstOctets = intArrayOf(
        8, 13, 18, 20, 23, 34, 35, 40, 44, 45, 52, 54,
        64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75,
        76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87,
        88, 89, 90, 91, 92, 93, 94, 95, 96, 97, 98, 99
    )
    private val countries = arrayOf(
        "US", "JP", "SG", "DE", "FR", "GB", "CA", "AU", "KR", "NL", "SE", "BR"
    )
    private val platforms = arrayOf(
        PlatformProfile("Android", listOf("11", "12", "13", "14", "15")),
        PlatformProfile("Linux", listOf("Ubuntu 22.04", "Debian 12", "Alpine 3.20", "Fedora 40")),
        PlatformProfile("Windows", listOf("10.0.19045", "10.0.22631", "10.0.26100")),
        PlatformProfile("Darwin", listOf("13.6", "14.5", "15.0"))
    )
    private val cpus = arrayOf(
        "Qualcomm SM8650", "MediaTek Dimensity 9300", "Apple M2",
        "Intel Core i7-12700", "AMD Ryzen 7 7840U", "Ampere Altra Q80"
    )
    private val gpus = arrayOf(
        "Adreno 750", "Mali-G720", "Apple GPU", "Intel Iris Xe",
        "AMD Radeon 780M", "NVIDIA RTX A2000"
    )

    fun create(nowMs: Long = System.currentTimeMillis()): SimulatedDevice {
        val random = ThreadLocalRandom.current()
        val nowSeconds = TimeUnit.MILLISECONDS.toSeconds(nowMs)
        val uptime = random.nextLong(
            TimeUnit.MINUTES.toSeconds(5),
            TimeUnit.DAYS.toSeconds(180)
        )
        val bootTime = (nowSeconds - uptime).coerceAtLeast(1L)
        val memTotal = random.nextLong(2L, 257L) * GIB
        val diskTotal = random.nextLong(32L, 4097L) * GIB
        val swapTotal = random.nextLong(0L, 33L) * GIB
        val memUsed = random.nextLong(memTotal / 12L, memTotal - (memTotal / 16L))
        val diskUsed = random.nextLong(diskTotal / 20L, diskTotal - (diskTotal / 10L))
        val swapUsed = if (swapTotal == 0L) 0L else random.nextLong(0L, swapTotal)
        val platform = platforms.random(random)
        val cpuCores = listOf(2, 4, 6, 8, 10, 12, 16, 24, 32, 64).random(random)
        val cpuRole = if (random.nextBoolean()) "Physical" else "Virtual"
        val cpuName = "${cpus.random(random)} $cpuCores $cpuRole Core"
        val gpuName = gpus.random(random)
        val geoIp = createGeoIp(random, bootTime)

        val host = Host.newBuilder()
            .setPlatform(platform.name)
            .setPlatformVersion(platform.versions.random(random))
            .addCpu(cpuName)
            .addGpu(gpuName)
            .setMemTotal(memTotal)
            .setDiskTotal(diskTotal)
            .setSwapTotal(swapTotal)
            .setArch(arrayOf("arm64-v8a", "x86_64", "aarch64").random(random))
            .setVirtualization(if (cpuRole == "Virtual") arrayOf("kvm", "docker", "qemu").random(random) else "")
            .setBootTime(bootTime)
            .setVersion(randomAppVersion(random))
            .build()

        val state = State.newBuilder()
            .setCpu(random.nextDouble(0.2, 98.0))
            .setMemUsed(memUsed)
            .setSwapUsed(swapUsed)
            .setDiskUsed(diskUsed)
            .setNetInTransfer(random.nextLong(10L * MIB, 80_000L * GIB))
            .setNetOutTransfer(random.nextLong(10L * MIB, 80_000L * GIB))
            .setNetInSpeed(random.nextLong(0L, 80L * MIB))
            .setNetOutSpeed(random.nextLong(0L, 40L * MIB))
            .setUptime(uptime)
            .setLoad1(random.nextDouble(0.0, cpuCores.toDouble()))
            .setLoad5(random.nextDouble(0.0, cpuCores.toDouble()))
            .setLoad15(random.nextDouble(0.0, cpuCores.toDouble()))
            .setTcpConnCount(random.nextLong(1L, 1200L))
            .setUdpConnCount(random.nextLong(1L, 300L))
            .setProcessCount(random.nextLong(40L, 900L))
            .addTemperatures(
                State_SensorTemperature.newBuilder()
                    .setName(arrayOf("CPU", "Battery", "SoC", "NVMe").random(random))
                    .setTemperature(random.nextDouble(28.0, 82.0))
                    .build()
            )
            .addGpu(random.nextDouble(0.0, 100.0))
            .build()

        return SimulatedDevice(
            uuid = UUID.randomUUID().toString(),
            host = host,
            geoIp = geoIp,
            state = state
        )
    }

    internal fun randomPublicIpv4(random: ThreadLocalRandom = ThreadLocalRandom.current()): String {
        val first = publicIpv4FirstOctets.random(random)
        return "$first.${random.nextInt(0, 256)}.${random.nextInt(0, 256)}.${random.nextInt(1, 255)}"
    }

    internal fun randomPublicIpv6(random: ThreadLocalRandom = ThreadLocalRandom.current()): String {
        val first = random.nextInt(0x2000, 0x4000)
        val parts = IntArray(7) { random.nextInt(0, 0x10000) }
        return buildString {
            append(first.toString(16))
            parts.forEach { part ->
                append(':')
                append(part.toString(16))
            }
        }
    }

    private fun createGeoIp(random: ThreadLocalRandom, bootTime: Long): GeoIP {
        val ip = IP.newBuilder()
            .setIpv4(randomPublicIpv4(random))
            .setIpv6(randomPublicIpv6(random))
            .build()
        return GeoIP.newBuilder()
            .setUse6(random.nextBoolean())
            .setIp(ip)
            .setCountryCode(countries.random(random))
            .setDashboardBootTime(bootTime)
            .build()
    }

    private fun randomAppVersion(random: ThreadLocalRandom): String =
        "A${random.nextInt(0, 3)}.${random.nextInt(1, 16)}.${random.nextInt(0, 40)}"

    private fun <T> Array<T>.random(random: ThreadLocalRandom): T = this[random.nextInt(size)]
    private fun IntArray.random(random: ThreadLocalRandom): Int = this[random.nextInt(size)]
    private fun <T> List<T>.random(random: ThreadLocalRandom): T = this[random.nextInt(size)]

    /**
     * `versions` is a List, not an Array, because this is a data class.
     *
     * A data class holding an array generates equals/hashCode that compare the array by identity,
     * so two profiles with identical contents are unequal and one put in a set is unfindable. The
     * declaration promises value semantics that the array silently withholds; only reading the
     * generated code reveals it. Nothing compares profiles today, which is exactly why the trap
     * would go unnoticed until something did.
     */
    private data class PlatformProfile(
        val name: String,
        val versions: List<String>
    )
}

data class SimulatedDevice(
    val uuid: String,
    val host: Host,
    val geoIp: GeoIP,
    val state: State
)
