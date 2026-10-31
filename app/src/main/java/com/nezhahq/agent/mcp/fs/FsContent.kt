package com.nezhahq.agent.mcp.fs

import java.security.MessageDigest

/** Segment-only operations. This pure Base64 path also runs on API23 (java.util.Base64 is API26). */
internal object FsContent {
    private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val HEX = "0123456789abcdef"

    fun sha256(bytes: ByteArray, length: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes, 0, length)
        val hash = digest.digest()
        return buildString(64) {
            for (byte in hash) {
                val value = byte.toInt() and 255
                append(HEX[value ushr 4])
                append(HEX[value and 15])
            }
        }
    }

    fun base64(bytes: ByteArray, length: Int): String = buildString((length + 2) / 3 * 4) {
        var index = 0
        while (index < length) {
            val first = bytes[index++].toInt() and 255
            val second = if (index < length) bytes[index++].toInt() and 255 else -1
            val third = if (index < length) bytes[index++].toInt() and 255 else -1
            append(BASE64[first ushr 2])
            append(BASE64[((first and 3) shl 4) or (if (second >= 0) second ushr 4 else 0)])
            append(if (second >= 0) BASE64[((second and 15) shl 2) or (if (third >= 0) third ushr 6 else 0)] else '=')
            append(if (third >= 0) BASE64[third and 63] else '=')
        }
    }
}
