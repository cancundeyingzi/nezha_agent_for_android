package com.nezhahq.agent.mcp.transport

import com.nezhahq.agent.core.mcp.McpLimits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/** Internal protocol, independent of Dashboard JSON. A reader/writer has exactly one owner. */
object HelperProtocol {
    const val VERSION = 2
    const val NONCE_BYTES = 32
    const val HELLO_BYTES = 4 + NONCE_BYTES + 4 * 4 + 8
    const val REQUEST_METADATA_BYTES = 8 + 8 + 4 + 4
    const val OUTPUT_CHUNK_BYTES = 8192
    private const val MAGIC = 0x4e5a4d48 // NZMH

    enum class Kind(val tag: Int, val minimum: Int, val maximum: Int) {
        HELLO(1, HELLO_BYTES, HELLO_BYTES),
        REQUEST(2, REQUEST_METADATA_BYTES, REQUEST_METADATA_BYTES + McpLimits.MAX_REQUEST_BYTES.toInt()),
        STDOUT(3, 1, OUTPUT_CHUNK_BYTES),
        STDERR(4, 1, OUTPUT_CHUNK_BYTES),
        TRUNCATED(5, 1, 1),
        RESULT(6, 1, McpLimits.MAX_TASK_RESULT_BYTES),
        UNAVAILABLE(7, 0, 0)
    }

    data class Header(val kind: Kind, val length: Int)

    /** Validate tag and signed length before any payload allocation, including RESULT. */
    class Reader(input: InputStream) {
        private val input = DataInputStream(input)
        private var pending: Header? = null

        fun header(allowed: Set<Kind>): Header {
            check(pending == null) { "Previous frame was not consumed" }
            if (input.readInt() != MAGIC) throw ProtocolFailure()
            val tag = input.readUnsignedByte()
            val kind = Kind.values().firstOrNull { it.tag == tag } ?: throw ProtocolFailure()
            val length = input.readInt()
            if (kind !in allowed || length !in kind.minimum..kind.maximum) throw ProtocolFailure()
            return Header(kind, length).also { pending = it }
        }

        fun payload(header: Header): ByteArray {
            check(pending === header) { "Frame is not owned by this reader" }
            return ByteArray(header.length).also {
                input.readFully(it)
                pending = null
            }
        }
    }

    class Writer(output: OutputStream) {
        private val output = DataOutputStream(output)

        fun frame(kind: Kind, payload: ByteArray) {
            require(payload.size in kind.minimum..kind.maximum)
            output.writeInt(MAGIC)
            output.writeByte(kind.tag)
            output.writeInt(payload.size)
            output.write(payload)
            output.flush()
        }
    }

    data class Hello(
        val version: Int,
        val nonce: ByteArray,
        val uid: Int,
        val effectiveUid: Int,
        val pid: Int,
        val sessionId: Int,
        val startTimeTicks: Long = 0
    ) {
        fun verified(expectedNonce: ByteArray, expectedUid: Int): Boolean =
            version == VERSION && expectedUid >= 0 && uid == expectedUid && effectiveUid == expectedUid &&
                pid > 1 && sessionId == pid && nonce.size == NONCE_BYTES &&
                MessageDigest.isEqual(nonce, expectedNonce)
    }

    data class Request(
        val id: Long,
        val type: Long,
        val remainingMillis: Int,
        val elapsedMillis: Int,
        val data: String
    )

    fun nonce(): ByteArray = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
    fun nonceArgument(nonce: ByteArray): String {
        require(nonce.size == NONCE_BYTES)
        return nonce.joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun parseNonce(argument: String): ByteArray? {
        if (argument.length != NONCE_BYTES * 2 || argument.any { it !in "0123456789abcdef" }) return null
        return ByteArray(NONCE_BYTES) { argument.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun encodeHello(hello: Hello): ByteArray = binary {
        require(hello.nonce.size == NONCE_BYTES)
        writeInt(hello.version)
        write(hello.nonce)
        writeInt(hello.uid)
        writeInt(hello.effectiveUid)
        writeInt(hello.pid)
        writeInt(hello.sessionId)
        writeLong(hello.startTimeTicks)
    }

    fun decodeHello(payload: ByteArray): Hello {
        if (payload.size != HELLO_BYTES) throw ProtocolFailure()
        return DataInputStream(ByteArrayInputStream(payload)).run {
            Hello(readInt(), ByteArray(NONCE_BYTES).also { readFully(it) }, readInt(), readInt(), readInt(), readInt(), readLong())
        }
    }

    fun encodeRequest(request: Request): ByteArray = binary {
        val data = request.data.toByteArray(Charsets.UTF_8)
        require(data.size <= McpLimits.MAX_REQUEST_BYTES)
        require(request.remainingMillis in 1..MAX_LIFETIME_MILLIS)
        require(request.elapsedMillis in 0..MAX_LIFETIME_MILLIS)
        writeLong(request.id)
        writeLong(request.type)
        writeInt(request.remainingMillis)
        writeInt(request.elapsedMillis)
        write(data)
    }

    fun decodeRequest(payload: ByteArray): Request {
        if (payload.size !in REQUEST_METADATA_BYTES..Kind.REQUEST.maximum) throw ProtocolFailure()
        return DataInputStream(ByteArrayInputStream(payload)).run {
            val id = readLong()
            val type = readLong()
            val remaining = readInt()
            val elapsed = readInt()
            if (remaining !in 1..MAX_LIFETIME_MILLIS || elapsed !in 0..MAX_LIFETIME_MILLIS) {
                throw ProtocolFailure()
            }
            val bytes = ByteArray(payload.size - REQUEST_METADATA_BYTES).also { readFully(it) }
            // Reject malformed UTF-8 on the control channel; target output uses replacement instead.
            val decoder = Charsets.UTF_8.newDecoder()
            val data = try { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
                catch (_: java.nio.charset.CharacterCodingException) { throw ProtocolFailure() }
            Request(id, type, remaining, elapsed, data)
        }
    }

    private fun binary(write: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).use(write) }.toByteArray()

    const val MAX_LIFETIME_MILLIS = 300_000
}

/** Deliberately carries no raw bytes or exception text. */
class ProtocolFailure : IOException("Invalid helper protocol")
