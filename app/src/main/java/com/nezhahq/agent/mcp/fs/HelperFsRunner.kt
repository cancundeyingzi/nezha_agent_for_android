package com.nezhahq.agent.mcp.fs

import com.nezhahq.agent.core.mcp.FsEntry
import com.nezhahq.agent.core.mcp.FsEntryType
import com.nezhahq.agent.core.mcp.FsListRequest
import com.nezhahq.agent.core.mcp.FsListResult
import com.nezhahq.agent.core.mcp.FsReadRequest
import com.nezhahq.agent.core.mcp.FsReadResult
import com.nezhahq.agent.core.mcp.McpLimits
import com.nezhahq.agent.core.mcp.McpRequestRules
import com.nezhahq.agent.core.mcp.McpValidation
import com.nezhahq.agent.mcp.McpResultEncoder
import com.nezhahq.agent.mcp.helper.HelperExecutionContext
import com.nezhahq.agent.mcp.transport.HelperDeadline

/** Synchronous native IO lives in a disposable helper; its independent watchdog is the hard stop. */
class HelperFsRunner(
    private val access: FsAccess,
    private val retainedEntryBytes: Int = McpLimits.MAX_TASK_RESULT_BYTES - LIST_ENVELOPE_ALLOWANCE
) {
    init { require(retainedEntryBytes in 0..McpLimits.MAX_TASK_RESULT_BYTES - LIST_ENVELOPE_ALLOWANCE) }

    fun list(request: FsListRequest, context: HelperExecutionContext): FsListResult {
        val prepared = when (val checked = McpRequestRules.prepare(request)) {
            is McpValidation.Invalid -> return FsListResult(error = checked.error.message)
            is McpValidation.Valid -> checked.request as FsListRequest
        }
        return try {
            checkDeadline(context.deadline)
            val result = access.openDirectory(prepared.path).use { directory ->
                val iterator = directory.iterator()
                val entries = ArrayList<FsEntry>()
                var remainingBytes = retainedEntryBytes
                var total = 0L
                while (true) {
                    checkDeadline(context.deadline)
                    if (!iterator.hasNext()) break
                    checkDeadline(context.deadline)
                    val name = iterator.next()
                    checkDeadline(context.deadline)
                    if (!prepared.showHidden && name.startsWith('.')) continue
                    if (total == Long.MAX_VALUE) throw FsFailure(FsError.IO)
                    total++
                    // Complete the count without metadata or retained names past the entry cap.
                    if (entries.size >= McpLimits.MAX_FS_LIST_ENTRIES) continue
                    val metadata = directory.lstat(name)
                    checkDeadline(context.deadline)
                    val isSymlink = metadata.type == FsEntryType.SYMLINK
                    val target = if (isSymlink) directory.readlink(name) else null
                    checkDeadline(context.deadline)
                    val entry = FsEntry(name, metadata.type, metadata.size,
                        FsPlatformRules.permissionMode(metadata.permissions), metadata.mtime, isSymlink, target)
                    val separatorBytes = if (entries.isEmpty()) 0 else 1
                    val entryBytes = McpResultEncoder.fsEntryEncodedBytes(entry, remainingBytes - separatorBytes)
                        ?: throw FsFailure(FsError.RESULT_TOO_LARGE)
                    remainingBytes -= entryBytes + separatorBytes
                    entries.add(entry)
                }
                checkDeadline(context.deadline)
                FsListResult(entries, total > entries.size.toLong(), total)
            }
            checkDeadline(context.deadline)
            result
        } catch (failure: FsFailure) {
            // Incomplete enumeration has no honest total. Return no partial count or entries.
            FsListResult(error = failure.reason.safeMessage)
        } catch (_: SecurityException) {
            FsListResult(error = FsError.PERMISSION_DENIED.safeMessage)
        } catch (_: Exception) {
            FsListResult(error = FsError.IO.safeMessage)
        }
    }

    fun read(request: FsReadRequest, context: HelperExecutionContext): FsReadResult {
        val prepared = when (val checked = McpRequestRules.prepare(request)) {
            is McpValidation.Invalid -> return FsReadResult(error = checked.error.message)
            is McpValidation.Valid -> checked.request as FsReadRequest
        }
        return try {
            checkDeadline(context.deadline)
            val result = access.openRead(prepared.path).use { handle ->
                val metadata = handle.stat()
                checkDeadline(context.deadline)
                if (metadata.type != FsEntryType.FILE) throw FsFailure(FsError.NOT_REGULAR)
                if (metadata.size < 0) throw FsFailure(FsError.IO)
                // No positive file position or position+length ever exceeds signed off_t's range.
                val limit = minOf(prepared.length, Long.MAX_VALUE - prepared.offset).toInt()
                val bytes = ByteArray(limit)
                var count = 0
                while (count < limit) {
                    checkDeadline(context.deadline)
                    val read = handle.pread(bytes, count, minOf(READ_CHUNK_BYTES, limit - count),
                        prepared.offset + count.toLong())
                    checkDeadline(context.deadline)
                    if (read == 0) break
                    if (read < 0 || read > minOf(READ_CHUNK_BYTES, limit - count)) throw FsFailure(FsError.IO)
                    count += read
                }
                val content = if (prepared.encoding == "base64") FsContent.base64(bytes, count)
                    else String(bytes, 0, count, Charsets.UTF_8)
                val sha256 = FsContent.sha256(bytes, count)
                checkDeadline(context.deadline)
                FsReadResult(content, prepared.encoding, count.toLong(), sha256,
                    prepared.offset < metadata.size && count.toLong() < metadata.size - prepared.offset)
            }
            checkDeadline(context.deadline)
            result
        } catch (failure: FsFailure) {
            FsReadResult(encoding = prepared.encoding, error = failure.reason.safeMessage)
        } catch (_: SecurityException) {
            FsReadResult(encoding = prepared.encoding, error = FsError.PERMISSION_DENIED.safeMessage)
        } catch (_: Exception) {
            FsReadResult(encoding = prepared.encoding, error = FsError.IO.safeMessage)
        }
    }

    private fun checkDeadline(deadline: HelperDeadline) {
        if (deadline.remainingMillis == 0L || Thread.currentThread().isInterrupted) throw FsFailure(FsError.TIMED_OUT)
    }

    companion object {
        // Success fields, maximum decimal total and protobuf uint64 envelope fit well within this.
        private const val LIST_ENVELOPE_ALLOWANCE = 512
        private const val READ_CHUNK_BYTES = 64 * 1024
    }
}
