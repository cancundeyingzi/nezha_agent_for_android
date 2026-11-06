package com.nezhahq.agent.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

private const val PROCESS_READ_BUFFER_BYTES = 8 * 1024

/**
 * Retains at most [maxBytes], but keeps draining [inputStream] so a full pipe cannot stall the
 * child process. Decoding happens once after collection, preserving UTF-8 sequences split across
 * read boundaries.
 *
 * The buffer grows as output arrives rather than being sized to [maxBytes] up front: callers pass
 * caps in the megabytes, and most commands produce a few hundred bytes.
 */
internal fun readLimitedUtf8(inputStream: InputStream, maxBytes: Int): String =
    readProcessOutput(inputStream, maxBytes).output

internal data class ProcessOutput(val output: String, val truncated: Boolean, val complete: Boolean)

internal fun readProcessOutput(inputStream: InputStream, maxBytes: Int): ProcessOutput {
    require(maxBytes >= 0) { "maxBytes must not be negative" }

    val retained = ByteArrayOutputStream(minOf(maxBytes, PROCESS_READ_BUFFER_BYTES))
    val readBuffer = ByteArray(PROCESS_READ_BUFFER_BYTES)
    var truncated = false
    var complete = true
    try {
        while (true) {
            val bytesRead = inputStream.read(readBuffer)
            if (bytesRead == -1) break
            if (bytesRead == 0) continue

            val bytesToRetain = minOf(bytesRead, maxBytes - retained.size())
            if (bytesToRetain < bytesRead) truncated = true
            if (bytesToRetain > 0) retained.write(readBuffer, 0, bytesToRetain)
        }
    } catch (_: IOException) {
        // Preserve partial output, but structured callers must not confuse an IO failure with EOF.
        complete = false
    }
    return ProcessOutput(String(retained.toByteArray(), Charsets.UTF_8), truncated, complete)
}
