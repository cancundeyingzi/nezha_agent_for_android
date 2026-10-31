package com.nezhahq.agent.simulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

class RandomDeviceFactoryTest {
    @Test
    fun generatedDevicesUseUniqueUuids() {
        val uuids = (1..200)
            .map { RandomDeviceFactory.create().uuid }
            .toSet()

        assertEquals(200, uuids.size)
    }

    @Test
    fun generatedDeviceFieldsAreSane() {
        repeat(100) {
            val device = RandomDeviceFactory.create()
            val host = device.host
            val state = device.state

            assertTrue(host.platform.isNotBlank())
            assertTrue(host.platformVersion.isNotBlank())
            assertTrue(host.cpuList.isNotEmpty())
            assertTrue(host.memTotal > 0L)
            assertTrue(host.diskTotal > 0L)
            assertTrue(host.bootTime > 0L)
            assertTrue(host.version.isNotBlank())
            assertTrue(state.cpu in 0.0..100.0)
            assertTrue(state.memUsed in 0L..host.memTotal)
            assertTrue(state.diskUsed in 0L..host.diskTotal)
            assertTrue(state.swapUsed in 0L..host.swapTotal)
            assertTrue(state.uptime > 0L)
            assertTrue(state.processCount > 0L)
            assertTrue(state.temperaturesList.isNotEmpty())
            assertTrue(state.gpuList.isNotEmpty())
            assertEquals(state.gpuList, state.gpusList.map { it.utilization })
            assertTrue(state.gpusList.all { it.memoryUsed == 0L && it.memoryTotal == 0L })
        }
    }

    @Test
    fun generatedIpsAvoidLocalAndReservedRanges() {
        repeat(200) {
            val device = RandomDeviceFactory.create()
            val ipv4 = InetAddress.getByName(device.geoIp.ip.ipv4)
            val ipv6 = InetAddress.getByName(device.geoIp.ip.ipv6)

            assertTrue(ipv4 is Inet4Address)
            assertTrue(ipv6 is Inet6Address)
            assertPublicLooking(ipv4)
            assertPublicLooking(ipv6)
        }
    }

    private fun assertPublicLooking(address: InetAddress) {
        assertFalse(address.isAnyLocalAddress)
        assertFalse(address.isLoopbackAddress)
        assertFalse(address.isLinkLocalAddress)
        assertFalse(address.isSiteLocalAddress)
        assertFalse(address.isMulticastAddress)

        if (address is Inet4Address) {
            val octets = address.address.map { it.toInt() and 0xff }
            assertFalse(octets[0] == 0)
            assertFalse(octets[0] == 127)
            assertFalse(octets[0] == 169 && octets[1] == 254)
            assertFalse(octets[0] == 100 && octets[1] in 64..127)
            assertFalse(octets[0] >= 224)
        }
    }
}
