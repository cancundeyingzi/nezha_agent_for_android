package com.nezhahq.agent.mcp.fs

import com.nezhahq.agent.core.mcp.FsEntryType
import java.io.IOException

/** Only the task helper instantiates AndroidFsAccess. A parent backend never opens user paths. */
interface FsAccess {
    fun openRead(path: String): FsReadHandle
    fun openDirectory(path: String): FsDirectoryHandle
}

data class FsMetadata(val type: FsEntryType, val size: Long, val permissions: Int, val mtime: Long)

/** stat and pread operate on the same opened fd, owned exclusively by this handle. */
interface FsReadHandle : AutoCloseable {
    fun stat(): FsMetadata
    fun pread(buffer: ByteArray, offset: Int, length: Int, fileOffset: Long): Int
}

/** Iterator, child lstat and readlink are all anchored to the same opened directory. */
interface FsDirectoryHandle : AutoCloseable {
    fun iterator(): Iterator<String>
    fun lstat(name: String): FsMetadata
    fun readlink(name: String): String
}

enum class FsError(val safeMessage: String) {
    NOT_FOUND("path not found"),
    PERMISSION_DENIED("permission denied"),
    SYMLINK("symlink is not allowed"),
    NOT_DIRECTORY("path is not a directory"),
    NOT_REGULAR("path is not a regular file"),
    IO("file I/O failed"),
    TIMED_OUT("file operation timed out"),
    ENUMERATION_UNAVAILABLE("bounded_directory_enumeration_unavailable"),
    ANCHOR_UNAVAILABLE("directory_fd_anchor_unavailable"),
    DIRECTORY_FLAGS_UNAVAILABLE("directory_open_flags_unavailable"),
    OPEN_FLAGS_UNAVAILABLE("file_open_flags_unavailable"),
    RESULT_TOO_LARGE("output/result too large")
}

/** Never retain a platform exception or its path-bearing message in a remotely visible error. */
class FsFailure(val reason: FsError) : IOException(reason.safeMessage)

object FsPlatformRules {
    fun boundedDirectoryEnumerationAvailable(apiLevel: Int): Boolean = apiLevel >= 26

    /** NDK27 Linux UAPI: ARM/ARM64 override asm-generic, x86/x86_64 use asm-generic. */
    fun directoryFlagForNoFollow(noFollow: Int): Int? = when (noFollow) {
        32_768 -> 16_384
        131_072 -> 65_536
        else -> null
    }

    /** All four verified NDK27 ABI headers inherit O_CLOEXEC=02000000 from asm-generic. */
    fun closeOnExecFlagForNoFollow(noFollow: Int): Int? = when (noFollow) {
        32_768, 131_072 -> 524_288
        else -> null
    }

    /** os.FileMode.Perm() wire semantics: ordinary rwx bits, without setuid/sticky/type bits. */
    fun permissionMode(permissions: Int): String {
        val bits = permissions and 0x1ff
        return if (bits == 0) "0" else "0" + bits.toString(8)
    }
}
