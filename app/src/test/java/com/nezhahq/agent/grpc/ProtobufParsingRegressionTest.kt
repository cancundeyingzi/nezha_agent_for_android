package com.nezhahq.agent.grpc

import com.google.protobuf.InvalidProtocolBufferException
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.protobuf.lite.ProtoLiteUtils
import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import proto.Nezha

class ProtobufParsingRegressionTest {
    @Test
    fun ordinaryTaskRoundTripsThroughGeneratedParserAndGrpcMarshaller() {
        val task = task()
        val payload = task.toByteArray()

        assertEquals(task, Nezha.Task.parseFrom(payload))
        assertEquals(task, Nezha.Task.parseFrom(ByteArrayInputStream(payload)))
        assertEquals(task, ProtoLiteUtils.marshaller(Nezha.Task.getDefaultInstance()).parse(ByteArrayInputStream(payload)))
    }

    @Test
    fun shallowUnknownGroupsPreserveTaskFieldsAndForwardCompatibleBytes() {
        val task = task()
        val payload = task.toByteArray() + unknownGroups(depth = 4)
        val parsedMessages = listOf(
            Nezha.Task.parseFrom(payload),
            Nezha.Task.parseFrom(ByteArrayInputStream(payload))
        )

        parsedMessages.forEach { parsed ->
            assertEquals(task.id, parsed.id)
            assertEquals(task.type, parsed.type)
            assertEquals(task.data, parsed.data)
            assertArrayEquals(payload, parsed.toByteArray())
        }
    }

    @Test
    fun byteArrayParserRejectsUnknownGroupsPastTheDefaultRecursionLimit() {
        assertThrows(InvalidProtocolBufferException::class.java) {
            Nezha.Task.parseFrom(unknownGroups(depth = 101))
        }
    }

    @Test
    fun streamParserRejectsUnknownGroupsPastTheDefaultRecursionLimit() {
        assertThrows(InvalidProtocolBufferException::class.java) {
            Nezha.Task.parseFrom(ByteArrayInputStream(unknownGroups(depth = 101)))
        }
    }

    @Test
    fun grpcMarshallerReportsMalformedNestedGroupsAsAParseFailure() {
        val failure = assertThrows(StatusRuntimeException::class.java) {
            ProtoLiteUtils.marshaller(Nezha.Task.getDefaultInstance())
                .parse(ByteArrayInputStream(unknownGroups(depth = 101)))
        }

        assertEquals(Status.Code.INTERNAL, failure.status.code)
        assertTrue(failure.cause is InvalidProtocolBufferException)
    }

    private fun task(): Nezha.Task = Nezha.Task.newBuilder()
        .setId(42)
        .setType(1)
        .setData("正常任务 / task")
        .build()

    // Unknown field 15, wire types START_GROUP/END_GROUP. CVE-2024-7254 bypassed the
    // recursion limit here. 101 levels are only 202 bytes and need no resource-exhaustion payload.
    private fun unknownGroups(depth: Int): ByteArray = ByteArray(depth * 2) { index ->
        if (index < depth) 0x7b.toByte() else 0x7c.toByte()
    }
}
