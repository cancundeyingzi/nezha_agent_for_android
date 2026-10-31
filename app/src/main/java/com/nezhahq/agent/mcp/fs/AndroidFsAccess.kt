package com.nezhahq.agent.mcp.fs

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.nezhahq.agent.core.mcp.FsEntryType
import java.io.FileDescriptor
import java.util.concurrent.atomic.AtomicBoolean

/** Public API21 fd operations; the API26 directory implementation is reached only after a guard. */
class AndroidFsAccess(private val apiLevel: () -> Int = { Build.VERSION.SDK_INT }) : FsAccess {
    override fun openRead(path: String): FsReadHandle = AndroidReadHandle(open(path))

    override fun openDirectory(path: String): FsDirectoryHandle {
        if (!FsPlatformRules.boundedDirectoryEnumerationAvailable(apiLevel())) {
            throw FsFailure(FsError.ENUMERATION_UNAVAILABLE)
        }
        // Deliberately use the direct SDK check as well: lint and old Android's verifier must see
        // that the separate java.nio.file implementation cannot be entered on API23..25.
        if (Build.VERSION.SDK_INT < 26) throw FsFailure(FsError.ENUMERATION_UNAVAILABLE)
        val directoryFlag = FsPlatformRules.directoryFlagForNoFollow(OsConstants.O_NOFOLLOW)
            ?: throw FsFailure(FsError.DIRECTORY_FLAGS_UNAVAILABLE)
        val descriptor = open(path, directoryFlag)
        return pinDirectoryDescriptor(descriptor, pin = { opened ->
            val stat = androidFsCall { Os.fstat(opened) }
            if (!OsConstants.S_ISDIR(stat.st_mode)) throw FsFailure(FsError.NOT_DIRECTORY)
            Api26FsDirectory.open(opened, stat)
        }, close = { opened -> androidFsCall { Os.close(opened) } })
    }

    private fun open(path: String, extraFlags: Int = 0): FileDescriptor {
        val noFollow = OsConstants.O_NOFOLLOW
        // O_CLOEXEC is public only from API27; fcntlInt is public only from API30. Use the
        // verified Linux UAPI value atomically, and refuse unverified flag layouts before open.
        val closeOnExec = FsPlatformRules.closeOnExecFlagForNoFollow(noFollow)
            ?: throw FsFailure(FsError.OPEN_FLAGS_UNAVAILABLE)
        return androidFsCall {
            Os.open(path, OsConstants.O_RDONLY or noFollow or OsConstants.O_NONBLOCK or
                closeOnExec or extraFlags, 0)
        }
    }
}

private class AndroidReadHandle(private val descriptor: FileDescriptor) : FsReadHandle {
    private val closed = AtomicBoolean(false)

    override fun stat(): FsMetadata = androidFsCall { Os.fstat(descriptor).toFsMetadata() }

    override fun pread(buffer: ByteArray, offset: Int, length: Int, fileOffset: Long): Int =
        androidFsCall { Os.pread(descriptor, buffer, offset, length, fileOffset) }

    override fun close() {
        if (closed.compareAndSet(false, true)) androidFsCall { Os.close(descriptor) }
    }
}

internal fun StructStat.toFsMetadata() = FsMetadata(
    when {
        OsConstants.S_ISREG(st_mode) -> FsEntryType.FILE
        OsConstants.S_ISDIR(st_mode) -> FsEntryType.DIR
        OsConstants.S_ISLNK(st_mode) -> FsEntryType.SYMLINK
        else -> FsEntryType.SPECIAL
    }, st_size, st_mode and 0x1ff, st_mtime
)

internal inline fun <T> androidFsCall(block: () -> T): T = try {
    block()
} catch (failure: ErrnoException) {
    throw FsFailure(when (failure.errno) {
        OsConstants.ENOENT -> FsError.NOT_FOUND
        OsConstants.EACCES, OsConstants.EPERM -> FsError.PERMISSION_DENIED
        OsConstants.ELOOP -> FsError.SYMLINK
        OsConstants.ENOTDIR -> FsError.NOT_DIRECTORY
        else -> FsError.IO
    })
} catch (_: SecurityException) {
    throw FsFailure(FsError.PERMISSION_DENIED)
}
