package com.nezhahq.agent.mcp.exec

import com.nezhahq.agent.core.mcp.ExecRequest
import com.nezhahq.agent.core.mcp.ExecResult
import com.nezhahq.agent.mcp.transport.HelperDeadline
import com.nezhahq.agent.mcp.transport.HelperProtocol
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs only inside a disposable, independently owned helper session. The main thread observes
 * process exit and output concurrently. Daemon pipe workers never delay terminal serialization;
 * the helper kills the complete group immediately after writing that terminal (or on control EOF).
 */
class HelperExecRunner(
    private val environment: ExecEnvironment,
    private val starter: ExecProcessStarter = SystemExecProcessStarter,
    private val nanoTime: () -> Long = System::nanoTime,
    private val drainGraceMillis: Long = 350
) {
    init { require(drainGraceMillis in 1..500) }

    fun execute(
        request: ExecRequest,
        deadline: HelperDeadline,
        elapsedBeforeRequest: Long,
        emit: (ExecOutput) -> Unit
    ): ExecResult {
        val capture = ExecOutputCapture(request.maxOutputBytes.toInt())
        fun result(exitCode: Int, timedOut: Boolean = false, error: String? = null): ExecResult {
            val output = capture.snapshot()
            return ExecResult(
                exitCode, output.stdout, output.stderr, elapsedBeforeRequest + deadline.elapsedMillis,
                output.stdoutTruncated, output.stderrTruncated, timedOut, error
            )
        }
        val spec = try { environment.prepare(request) }
        catch (failure: ExecPreparationFailure) { return result(-1, error = failure.safeMessage) }
        catch (_: Exception) { return result(-1, error = "exec preparation failed") }
        if (deadline.remainingMillis == 0L) return result(-1, timedOut = true)
        val process = try { starter.start(spec) }
        catch (_: Exception) { return result(-1, error = "exec process could not be started") }

        val events = ArrayBlockingQueue<ExecOutput>(4)
        val stopped = AtomicBoolean(false)
        val stdoutDone = AtomicBoolean(false)
        val stderrDone = AtomicBoolean(false)
        val ioFailure = AtomicReference<String?>()

        fun publish(output: ExecOutput) {
            while (!stopped.get()) {
                if (events.offer(output, 20, TimeUnit.MILLISECONDS)) return
            }
        }

        fun drain(input: InputStream, stderr: Boolean, done: AtomicBoolean) = daemon(
            if (stderr) "mcp-target-stderr" else "mcp-target-stdout"
        ) {
            try {
                input.use {
                    val buffer = ByteArray(HelperProtocol.OUTPUT_CHUNK_BYTES)
                    var retained = 0
                    var truncated = false
                    while (!stopped.get()) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        val keep = minOf(count, request.maxOutputBytes.toInt() - retained)
                        if (keep > 0) {
                            val output = ExecOutput.Bytes(stderr, buffer.copyOf(keep))
                            capture.accept(output)
                            retained += keep
                            publish(output)
                        }
                        if (keep < count && !truncated) {
                            truncated = true
                            val output = ExecOutput.Truncated(stderr)
                            capture.accept(output)
                            publish(output)
                        }
                        // Once full, keep reading/discarding. Never leave the target's pipe full.
                    }
                }
            } catch (_: Exception) {
                if (!stopped.get()) ioFailure.compareAndSet(null, "exec output IO failed")
            } finally { done.set(true) }
        }

        try {
            drain(process.inputStream, false, stdoutDone)
            drain(process.errorStream, true, stderrDone)
            daemon("mcp-target-stdin") {
                try {
                    process.outputStream.use { it.write(request.stdin.toByteArray(Charsets.UTF_8)) }
                } catch (_: IOException) {
                    // A process can deliberately close stdin before it exits. Broken pipe is not
                    // a replacement exit status; continue observing the real main process exit.
                } catch (_: Exception) {
                    if (!stopped.get()) {
                        ioFailure.compareAndSet(null, "exec stdin IO failed")
                    }
                }
            }
            var exit: Int? = null
            var exitedNanos = 0L
            var timedOut = false
            while (true) {
                val output = events.poll(10, TimeUnit.MILLISECONDS)
                if (output != null) emit(output)
                if (exit == null) {
                    exit = exitCode(process)
                    if (exit != null) exitedNanos = nanoTime()
                }
                if (exit != null && stdoutDone.get() && stderrDone.get() && events.isEmpty()) break
                if (deadline.remainingMillis == 0L) {
                    timedOut = true
                    break
                }
                if (exit != null && nanoTime() - exitedNanos >= drainGraceMillis * 1_000_000) break
                if (ioFailure.get() != null) break
            }
            stopped.set(true)
            // A descendant can keep an output fd open after the main process exits. Retain the
            // real main exit, mark an incomplete stream, and let the session owner kill descendants.
            if (!stdoutDone.get()) capture.accept(ExecOutput.Truncated(false))
            if (!stderrDone.get()) capture.accept(ExecOutput.Truncated(true))
            while (true) emit(events.poll() ?: break)
            if (!stdoutDone.get()) emit(ExecOutput.Truncated(false))
            if (!stderrDone.get()) emit(ExecOutput.Truncated(true))
            return result(exit ?: -1, timedOut, ioFailure.get())
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedException("Helper execution interrupted")
        } finally {
            stopped.set(true)
            // Do not close a pipe on this thread: a blocked stdin writer/read lock could pin it.
            // Each worker owns/closes its stream. HelperMain owns the final group SIGKILL and is
            // also guarded by a finite watchdog, including while serializing/writing the result.
        }
    }

    private fun exitCode(process: Process): Int? = try { process.exitValue() }
        catch (_: IllegalThreadStateException) { null }

    private fun daemon(name: String, block: () -> Unit) = Thread(block, name).apply { isDaemon = true; start() }
}
