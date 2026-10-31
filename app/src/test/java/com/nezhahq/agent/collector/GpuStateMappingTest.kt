package com.nezhahq.agent.collector

import com.google.protobuf.CodedInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import proto.Nezha.State
import proto.Nezha.State_GPU
import java.util.concurrent.CancellationException

class GpuStateMappingTest {
    private val base = State.newBuilder()
        .setCpu(12.5)
        .setMemUsed(1024L)
        .setNetInTransfer(123456789L)
        .setUptime(3600L)
        .build()

    @Test
    fun oneSampleFeedsBothFieldsInOrderAndKeepsGpuMemoryUnknown() {
        var calls = 0
        val state = base.toBuilder().addGpuSample {
            calls++
            listOf(37.5, 0.0, 82.125)
        }.build()
        val decoded = State.parseFrom(state.toByteArray())

        assertEquals(1, calls)
        assertEquals(listOf(37.5, 0.0, 82.125), decoded.gpuList)
        assertEquals(decoded.gpuList, decoded.gpusList.map { it.utilization })
        decoded.gpusList.forEach {
            assertEquals(0L, it.memoryUsed)
            assertEquals(0L, it.memoryTotal)
        }
        assertEquals(base, decoded.toBuilder().clearGpu().clearGpus().build())
    }

    @Test
    fun emptySamplePreservesAllOtherStateFieldsWithoutInventingZeroUsage() {
        val state = base.toBuilder().addGpuSample { emptyList() }.build()

        assertEquals(base, state)
        assertEquals(0, state.gpuCount)
        assertEquals(0, state.gpusCount)
    }

    @Test
    fun failedSamplePreservesAllOtherStateFields() {
        var calls = 0
        val state = base.toBuilder().addGpuSample {
            calls++
            throw SecurityException("GPU node unavailable")
        }.build()

        assertEquals(1, calls)
        assertEquals(base, state)
    }

    @Test
    fun nonFiniteReadingMakesTheWholeSampleUnavailableWithoutShiftingIndices() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val state = base.toBuilder().addGpuSample { listOf(12.5, invalid, 75.0) }.build()
            assertEquals(base, state)
        }
    }

    @Test
    fun cancellationStillPropagates() {
        val cancellation = CancellationException("sampling cancelled")
        try {
            base.toBuilder().addGpuSample { throw cancellation }
            fail("Cancellation must not become a successful sample")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun fixedLegacyPackedFixtureStillUsesField17() {
        // 旧 State：cpu=12.5、mem_used=1024、packed gpu=17 为 [12.5, 90.0]。
        val fixture = hex(
            "09 00 00 00 00 00 00 29 40 10 80 08 " +
                "8a 01 10 00 00 00 00 00 00 29 40 00 00 00 00 00 80 56 40"
        )
        val state = State.parseFrom(fixture)

        assertEquals(12.5, state.cpu, 0.0)
        assertEquals(1024L, state.memUsed)
        assertEquals(listOf(12.5, 90.0), state.gpuList)
        assertEquals(0, state.gpusCount)
        assertArrayEquals(fixture, state.toByteArray())
    }

    @Test
    fun legacyUnpackedDoubleEncodingAlsoRemainsReadable() {
        val state = State.parseFrom(hex("89 01 00 00 00 00 00 00 29 40"))

        assertEquals(listOf(12.5), state.gpuList)
        assertEquals(0, state.gpusCount)
    }

    @Test
    fun fixedNewFixtureUsesField18AndMemoryValuesInMiB() {
        // gpus=18，内层 utilization=1 为 42.5、memory_used=2 为 512 MiB、memory_total=3 为 2048 MiB。
        val fixture = hex("92 01 0f 09 00 00 00 00 00 40 45 40 10 80 04 18 80 10")
        val state = State.parseFrom(fixture)
        val gpu = state.getGpus(0)

        assertEquals(0, state.gpuCount)
        assertEquals(1, state.gpusCount)
        assertEquals(42.5, gpu.utilization, 0.0)
        assertEquals(512L, gpu.memoryUsed)
        assertEquals(2048L, gpu.memoryTotal)
        val encoded = State.newBuilder().addGpus(
            State_GPU.newBuilder().setUtilization(42.5).setMemoryUsed(512L).setMemoryTotal(2048L)
        ).build().toByteArray()
        assertArrayEquals(fixture, encoded)
        assertArrayEquals(fixture, state.toByteArray())
    }

    @Test
    fun legacyWireReaderSkipsField18AndRetainsKnownFields() {
        val state = State.newBuilder().setCpu(12.5).setMemUsed(1024L)
            .addGpuSample { listOf(12.5, 90.0) }.build()
        val input = CodedInputStream.newInstance(state.toByteArray())
        var cpu = 0.0
        var memUsed = 0L
        val usages = mutableListOf<Double>()
        val skippedFields = mutableListOf<Int>()

        // 只认识旧 State 的字段/编码；新字段走 protobuf 的标准 unknown-field 跳过路径。
        while (!input.isAtEnd) {
            val tag = input.readTag()
            when (tag) {
                9 -> cpu = input.readDouble()
                16 -> memUsed = input.readUInt64()
                137 -> usages += input.readDouble()
                138 -> {
                    val limit = input.pushLimit(input.readRawVarint32())
                    while (input.bytesUntilLimit > 0) usages += input.readDouble()
                    input.popLimit(limit)
                }
                else -> {
                    skippedFields += tag ushr 3
                    check(input.skipField(tag))
                }
            }
        }

        assertEquals(12.5, cpu, 0.0)
        assertEquals(1024L, memUsed)
        assertEquals(listOf(12.5, 90.0), usages)
        assertEquals(listOf(18, 18), skippedFields)
    }

    private fun hex(value: String): ByteArray =
        value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
