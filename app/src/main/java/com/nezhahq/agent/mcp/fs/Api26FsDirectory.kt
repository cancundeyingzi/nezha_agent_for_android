package com.nezhahq.agent.mcp.fs

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.StructStat
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

/** No API26 java.nio.file type occurs in any class used by the API23 FsRead path. */
@RequiresApi(26)
internal class Api26FsDirectory private constructor(
    private val anchorDescriptor: ParcelFileDescriptor,
    private val anchor: String,
    private val stream: DirectoryStream<Path>
) : FsDirectoryHandle {
    private val closed = AtomicBoolean(false)
    private var iterated = false

    override fun iterator(): Iterator<String> {
        check(!iterated)
        iterated = true
        val source = nioFsCall { stream.iterator() }
        return object : Iterator<String> {
            override fun hasNext(): Boolean = nioFsCall { source.hasNext() }
            override fun next(): String = nioFsCall { source.next().fileName.toString() }
        }
    }

    override fun lstat(name: String): FsMetadata = androidFsCall {
        Os.lstat("$anchor/$name").toFsMetadata()
    }

    override fun readlink(name: String): String = androidFsCall { Os.readlink("$anchor/$name") }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        withFsCleanup(block = { stream.close() }, cleanup = { anchorDescriptor.close() })
    }

    companion object {
        @DoNotInline
        fun open(descriptor: FileDescriptor, opened: StructStat): FsDirectoryHandle {
            val anchorDescriptor = ParcelFileDescriptor.dup(descriptor)
            var stream: DirectoryStream<Path>? = null
            try {
                val anchor = "/proc/self/fd/${anchorDescriptor.fd}"
                // Fail as a capability problem if this platform cannot resolve the proc fd
                // anchor. Identity is compared to the already opened directory, never its path.
                try {
                    val resolved = Os.stat(anchor)
                    if (resolved.st_dev != opened.st_dev || resolved.st_ino != opened.st_ino) {
                        throw FsFailure(FsError.ANCHOR_UNAVAILABLE)
                    }
                } catch (_: Exception) {
                    throw FsFailure(FsError.ANCHOR_UNAVAILABLE)
                }
                val openedStream = nioFsCall { Files.newDirectoryStream(Paths.get(anchor)) }
                stream = openedStream
                return Api26FsDirectory(anchorDescriptor, anchor, openedStream)
            } catch (failure: Throwable) {
                runCatching { stream?.close() }
                runCatching { anchorDescriptor.close() }
                throw failure
            }
        }
    }
}

@RequiresApi(26)
private inline fun <T> nioFsCall(block: () -> T): T = try {
    block()
} catch (failure: DirectoryIteratorException) {
    throw FsFailure(nioReason(failure.cause))
} catch (failure: IOException) {
    throw FsFailure(nioReason(failure))
} catch (_: SecurityException) {
    throw FsFailure(FsError.PERMISSION_DENIED)
}

@RequiresApi(26)
private fun nioReason(failure: IOException?): FsError = when (failure) {
    is AccessDeniedException -> FsError.PERMISSION_DENIED
    is NoSuchFileException -> FsError.NOT_FOUND
    is NotDirectoryException -> FsError.NOT_DIRECTORY
    else -> FsError.IO
}
