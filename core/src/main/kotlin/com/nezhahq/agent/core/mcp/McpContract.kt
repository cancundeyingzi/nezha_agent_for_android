package com.nezhahq.agent.core.mcp

/** R1 wire DTOs. JSON field names are mapped explicitly by :app, not by reflection. */
sealed interface McpRequest {
    val family: McpTaskFamily
}

data class ExecRequest(
    val cmd: String,
    val args: List<String> = emptyList(),
    val cwd: String? = null,
    val env: Map<String, String> = emptyMap(),
    val stdin: String = "",
    val timeoutSeconds: Long = McpLimits.DEFAULT_EXEC_TIMEOUT_SECONDS,
    val maxOutputBytes: Long = McpLimits.DEFAULT_EXEC_MAX_OUTPUT_BYTES
) : McpRequest {
    override val family: McpTaskFamily get() = McpTaskFamily.EXEC
}

data class FsListRequest(
    val path: String,
    val showHidden: Boolean = false
) : McpRequest {
    override val family: McpTaskFamily get() = McpTaskFamily.FS_LIST
}

data class FsReadRequest(
    val path: String,
    val offset: Long = 0,
    val length: Long = McpLimits.MAX_FS_READ_BYTES,
    val encoding: String = "utf8"
) : McpRequest {
    override val family: McpTaskFamily get() = McpTaskFamily.FS_READ
}

sealed interface McpResult {
    val family: McpTaskFamily
    val error: String?
}

data class ExecResult(
    val exitCode: Int = 0,
    val stdout: String = "",
    val stderr: String = "",
    val durationMs: Long = 0,
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val timedOut: Boolean = false,
    override val error: String? = null
) : McpResult {
    override val family: McpTaskFamily get() = McpTaskFamily.EXEC
}

enum class FsEntryType(val wireName: String) {
    FILE("file"), DIR("dir"), SYMLINK("symlink"), SPECIAL("special")
}

data class FsEntry(
    val name: String,
    val type: FsEntryType,
    val size: Long,
    val mode: String,
    /** Modification time in Unix seconds, including negative timestamps before the epoch. */
    val mtime: Long,
    val isSymlink: Boolean = false,
    val linkTarget: String? = null
)

data class FsListResult(
    val entries: List<FsEntry> = emptyList(),
    val truncated: Boolean = false,
    val total: Long = 0,
    override val error: String? = null
) : McpResult {
    override val family: McpTaskFamily get() = McpTaskFamily.FS_LIST
}

data class FsReadResult(
    val content: String = "",
    val encoding: String = "utf8",
    /** Number of raw bytes in the returned segment, before UTF-8 replacement or Base64 encoding. */
    val size: Long = 0,
    /** Lowercase SHA-256 of that same raw segment, not of the whole file or encoded content. */
    val sha256: String? = null,
    val truncated: Boolean = false,
    override val error: String? = null
) : McpResult {
    override val family: McpTaskFamily get() = McpTaskFamily.FS_READ
}

enum class McpErrorCode {
    INVALID_ARGUMENT, PERMISSION_DENIED, BUSY, NOT_FOUND, CAPABILITY_UNAVAILABLE, INTERNAL
}

/** [message] must be safe to return remotely; never fill it with raw request or exception text. */
data class McpBusinessError(val code: McpErrorCode, val message: String) {
    init {
        require(message.isNotBlank()) { "Business error message must not be blank" }
    }
}

sealed interface McpValidation {
    data class Valid(val request: McpRequest) : McpValidation
    data class Invalid(val error: McpBusinessError) : McpValidation
}

/** Business checks and limit normalization happen after JSON/type/range parsing has succeeded. */
object McpRequestRules {
    fun prepare(request: McpRequest): McpValidation = when (request) {
        is ExecRequest -> when {
            request.cmd.isBlank() -> invalid("cmd required")
            request.timeoutSeconds !in 0..McpLimits.UINT32_MAX ->
                invalid("timeout_seconds out of uint32 range")
            request.maxOutputBytes !in 0..McpLimits.UINT32_MAX ->
                invalid("max_output_bytes out of uint32 range")
            else -> McpValidation.Valid(request.copy(
                timeoutSeconds = defaultAndClamp(
                    request.timeoutSeconds,
                    McpLimits.DEFAULT_EXEC_TIMEOUT_SECONDS,
                    McpLimits.MAX_EXEC_TIMEOUT_SECONDS
                ),
                maxOutputBytes = defaultAndClamp(
                    request.maxOutputBytes,
                    McpLimits.DEFAULT_EXEC_MAX_OUTPUT_BYTES,
                    McpLimits.MAX_EXEC_OUTPUT_BYTES
                )
            ))
        }
        is FsListRequest -> pathError(request.path) ?: McpValidation.Valid(request)
        is FsReadRequest -> pathError(request.path) ?: when {
            request.offset < 0 -> invalid("offset must not be negative")
            request.length < 0 -> invalid("length must not be negative")
            request.encoding !in setOf("", "utf8", "base64") -> invalid("unknown encoding")
            else -> McpValidation.Valid(request.copy(
                length = defaultAndClamp(
                    request.length, McpLimits.MAX_FS_READ_BYTES, McpLimits.MAX_FS_READ_BYTES
                ),
                encoding = request.encoding.ifEmpty { "utf8" }
            ))
        }
    }

    private fun pathError(path: String): McpValidation.Invalid? = when {
        path.isBlank() -> invalid("path required")
        '\u0000' in path -> invalid("path contains a NUL character")
        !path.startsWith('/') -> invalid("path must be absolute")
        else -> null
    }

    private fun defaultAndClamp(value: Long, default: Long, maximum: Long): Long =
        if (value == 0L) default else minOf(value, maximum)

    private fun invalid(message: String) =
        McpValidation.Invalid(McpBusinessError(McpErrorCode.INVALID_ARGUMENT, message))
}
