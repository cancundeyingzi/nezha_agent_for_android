package com.nezhahq.agent.util

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class ShellCommandResult(
    val output: String = "",
    val exitCode: Int? = null,
    val timedOut: Boolean = false,
    val outputComplete: Boolean = false,
    val truncated: Boolean = false
) {
    val successful: Boolean get() = exitCode == 0 && !timedOut && outputComplete && !truncated
}

/** The caller owns authorization/registration; this function always returns that ownership via close. */
internal suspend fun executeIsolatedShellCommand(
    process: Process,
    command: String,
    timeoutMillis: Long,
    close: () -> Unit,
    maxOutputBytes: Int = MAX_SHELL_OUTPUT_BYTES
): ShellCommandResult = withContext(Dispatchers.IO) {
    var output = ProcessOutput("", truncated = false, complete = false)
    try {
        withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                // Destroying the process, rather than interrupting an IO thread, unblocks its pipes.
                continuation.invokeOnCancellation { close() }
                if (!continuation.isActive) return@suspendCancellableCoroutine
                try {
                    process.outputStream.apply {
                        write("$command\nexit\n".toByteArray(Charsets.UTF_8))
                        flush()
                    }
                    output = readProcessOutput(process.inputStream, maxOutputBytes)
                    // Untimed waitFor is available on API 23; the same deadline/cancellation closes
                    // the process even when stdout has closed before the process itself exits.
                    val exit = process.waitFor()
                    continuation.resume(ShellCommandResult(output.output, exit,
                        outputComplete = output.complete, truncated = output.truncated))
                } catch (failure: Exception) {
                    continuation.resumeWithException(failure)
                }
            }
        } ?: ShellCommandResult(output.output, timedOut = true,
            outputComplete = output.complete, truncated = output.truncated)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Logger.e("RootShell: 隔离命令执行失败", failure)
        ShellCommandResult(output.output, outputComplete = output.complete, truncated = output.truncated)
    } finally {
        close()
    }
}
