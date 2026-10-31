package com.nezhahq.agent.mcp

import com.nezhahq.agent.core.mcp.McpLimits
import com.nezhahq.agent.mcp.transport.HelperProtocol
import com.nezhahq.agent.mcp.transport.ProtocolFailure
import com.nezhahq.agent.mcp.helper.HelperSessionIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class HelperProtocolTest {
    @Test fun invalidTagLengthAndUnexpectedKindAreRejectedBeforePayloadRead() {
        for ((tag, length) in listOf(
            255 to 1, 6 to -1, 6 to McpLimits.MAX_TASK_RESULT_BYTES + 1, 1 to 1, 3 to 8193, 2 to 24
        )) {
            val source = CountingInput(rawHeader(tag, length))
            val reader = HelperProtocol.Reader(source)
            assertThrows(ProtocolFailure::class.java) { reader.header(setOf(HelperProtocol.Kind.RESULT)) }
            assertTrue(source.position <= 9)
        }
    }

    @Test fun requestRetainsUnsignedLongBitPatternsAndOriginalJsonWithoutDoubleEscaping() {
        val raw = "{\"cmd\":\"echo\",\"args\":[\"a\\nb\",\";$()\"],\"extra\":{\"x\":1}}"
        val payload = HelperProtocol.encodeRequest(HelperProtocol.Request(Long.MIN_VALUE, -1, 1000, 29, raw))
        assertEquals(raw.toByteArray().size + HelperProtocol.REQUEST_METADATA_BYTES, payload.size)
        val decoded = HelperProtocol.decodeRequest(payload)
        assertEquals(Long.MIN_VALUE, decoded.id)
        assertEquals(-1L, decoded.type)
        assertEquals(raw, decoded.data)
    }

    @Test fun invalidUtf8AndInvalidLifetimeAreRejectedOnControlChannel() {
        val valid = HelperProtocol.encodeRequest(HelperProtocol.Request(1, 15, 1, 0, "{}"))
        val invalidUtf8 = valid.copyOf().apply { this[lastIndex] = 0xff.toByte() }
        assertThrows(ProtocolFailure::class.java) { HelperProtocol.decodeRequest(invalidUtf8) }
        val zeroTimeout = valid.copyOf().apply { for (i in 16..19) this[i] = 0 }
        assertThrows(ProtocolFailure::class.java) { HelperProtocol.decodeRequest(zeroTimeout) }
    }

    @Test fun handshakeRequiresAllIdentityFieldsAndFullNonce() {
        val nonce = HelperProtocol.nonce()
        val valid = HelperProtocol.Hello(HelperProtocol.VERSION, nonce, 10213, 10213, 123, 123)
        assertTrue(valid.verified(nonce, 10213))
        assertFalse(valid.copy(version = HelperProtocol.VERSION + 1).verified(nonce, 10213))
        assertFalse(valid.copy(uid = 0).verified(nonce, 10213))
        assertFalse(valid.copy(effectiveUid = 0).verified(nonce, 10213))
        assertFalse(valid.copy(pid = 1, sessionId = 1).verified(nonce, 10213))
        assertFalse(valid.copy(sessionId = 22).verified(nonce, 10213))
        assertFalse(valid.copy(nonce = nonce.copyOf().apply { this[31] = (this[31].toInt() xor 1).toByte() }).verified(nonce, 10213))
        assertArrayEquals(nonce, HelperProtocol.parseNonce(HelperProtocol.nonceArgument(nonce)))
        assertNull(HelperProtocol.parseNonce("0".repeat(63)))
    }

    @Test fun preestablishedSessionChecksKernelFieldsAfterLastCommParenthesis() {
        fun stat(pid: String = "123", group: String = "123", session: String = "123") =
            ("$pid (name ) with ( spaces)) S 99 $group $session " + "0 ".repeat(15) + "987654 0 0\n").toByteArray()
        assertTrue(HelperSessionIdentity.isOwnedSession(stat(), 123))
        assertFalse(HelperSessionIdentity.isOwnedSession(stat(pid = "124"), 123))
        assertFalse(HelperSessionIdentity.isOwnedSession(stat(group = "99"), 123))
        assertFalse(HelperSessionIdentity.isOwnedSession(stat(session = "99"), 123))
        assertFalse(HelperSessionIdentity.isOwnedSession(stat(group = "+123"), 123))
        assertEquals(987654L, HelperSessionIdentity.snapshot(stat(), 123)!!.startTimeTicks)
        assertNull(HelperSessionIdentity.snapshot(stat().toString(Charsets.UTF_8).replace("987654", "-1").toByteArray(), 123))
        assertFalse(HelperSessionIdentity.isOwnedSession("123 (name) S 99 123".toByteArray(), 123))
        assertFalse(HelperSessionIdentity.readOwnedSession(ByteArrayInputStream(ByteArray(4097) { 32 }), 123))
    }

    private fun rawHeader(tag: Int, length: Int): ByteArray = ByteArrayOutputStream().also {
        DataOutputStream(it).apply { writeInt(0x4e5a4d48); writeByte(tag); writeInt(length) }
    }.toByteArray()

    internal class CountingInput(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        val position: Int get() = pos
    }
}
