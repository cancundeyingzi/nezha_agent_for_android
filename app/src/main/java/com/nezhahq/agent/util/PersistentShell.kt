package com.nezhahq.agent.util

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A process and its three streams, owned exclusively by one persistent shell controller. */
internal class ShellSession(
    val process: Process,
    val type: String
) {
    val input: InputStream = process.inputStream
    private val output: OutputStream = process.outputStream
    private val error: InputStream = process.errorStream
    private val destroyed = AtomicBoolean()

    fun writeCommand(command: String, marker: ShellMarker) {
        if (destroyed.get()) throw IOException("Shell session is closed")
        val frame = buildString(command.length + marker.token.length * 2 + 96) {
            append(command)
            if (!command.endsWith('\n')) append('\n')
            append(marker.completionCommand())
            append('\n')
        }
        output.write(frame.toByteArray(StandardCharsets.UTF_8))
        output.flush()
        if (destroyed.get()) throw IOException("Shell session was closed while writing")
    }

    fun destroy(graceful: Boolean = false) {
        if (!destroyed.compareAndSet(false, true)) return
        if (graceful) {
            try {
                output.write("exit\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
            } catch (_: Exception) {
            }
        }
        destroyQuietly(process)
        closeQuietly(output)
        closeQuietly(input)
        closeQuietly(error)
    }

    companion object {
        fun openRedirected(process: Process, type: String): ShellSession {
            val session = ShellSession(process, type)
            try {
                session.output.write("exec 2>&1\n".toByteArray(StandardCharsets.US_ASCII))
                session.output.flush()
                return session
            } catch (exception: Exception) {
                session.destroy()
                throw exception
            }
        }

        fun destroyProcess(process: Process) {
            destroyQuietly(process)
            closeQuietly(tryOrNull { process.outputStream })
            closeQuietly(tryOrNull { process.inputStream })
            closeQuietly(tryOrNull { process.errorStream })
        }

        private fun closeQuietly(stream: AutoCloseable?) {
            try {
                stream?.close()
            } catch (_: Exception) {
            }
        }

        private fun destroyQuietly(process: Process) {
            try {
                process.destroy()
            } catch (_: Exception) {
            }
        }

        private fun <T> tryOrNull(block: () -> T): T? = try {
            block()
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * The single liveness check for every privileged shell process in this package.
 *
 * `Process.isAlive()` is API 26+ while this app ships to API 23, so liveness has to be inferred
 * from [Process.exitValue]. The second catch is not redundant: a Shizuku shell is a remote process
 * whose `exitValue()` reports "still running" as [IllegalStateException] rather than the
 * [IllegalThreadStateException] the JDK contract specifies, and treating that as "dead" would make
 * every Shizuku session look like it had already exited.
 */
internal fun isProcessAlive(process: Process): Boolean {
    return try {
        process.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    } catch (_: IllegalStateException) {
        true
    }
}

/** Serializes commands through one session and owns the reusable bounded reader thread. */
internal class PersistentShell(
    private val sessionFactory: () -> ShellSession?,
    private val markerFactory: () -> ShellMarker,
    private val protocolReader: ShellProtocolReader = ShellProtocolReader(),
    private val executorFactory: () -> ExecutorService = ::newShellReaderExecutor,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val retryCooldownMs: Long = 30_000L,
    private val readerStopWaitMs: Long = 250L,
    private val onCommandFailure: (String, Exception) -> Unit = { _, _ -> }
) {
    private val lock = ReentrantLock()
    private var session: ShellSession? = null
    private var readerExecutor: ExecutorService? = null
    private var lastStartFailedMs = 0L
    private val activeSession = AtomicReference<ShellSession?>()
    private val activeReadTask = AtomicReference<ReadTask?>()
    private val shutdownRequests = AtomicInteger()

    fun execute(command: String, timeoutMs: Long = DEFAULT_SHELL_TIMEOUT_MS): String = lock.withLock {
        if (isShuttingDown()) return@withLock ""
        var readTask: ReadTask? = null
        try {
            val commandSession = ensureAlive() ?: return@withLock ""
            if (isShuttingDown()) {
                destroySession()
                return@withLock ""
            }
            val marker = markerFactory()
            val task = ReadTask()
            readTask = task
            activeReadTask.set(task)
            if (isShuttingDown()) {
                activeReadTask.compareAndSet(task, null)
                commandSession.destroy()
                return@withLock ""
            }

            val future = try {
                readerExecutor().submit<ShellReadResult> {
                    try {
                        if (isShuttingDown()) throw CancellationException("Shell is shutting down")
                        commandSession.writeCommand(command, marker)
                        protocolReader.read(commandSession.input, marker)
                    } finally {
                        task.completion.countDown()
                        activeReadTask.compareAndSet(task, null)
                    }
                }
            } catch (exception: Exception) {
                task.completion.countDown()
                activeReadTask.compareAndSet(task, null)
                throw exception
            }
            task.future = future
            if (isShuttingDown()) {
                commandSession.destroy()
                task.cancel()
            }
            future.get(timeoutMs, TimeUnit.MILLISECONDS).output
        } catch (exception: Exception) {
            val resetInterrupted = resetAfterCommandFailure(readTask)
            if (!isShuttingDown()) reportFailure(command, unwrap(exception))
            if (exception is InterruptedException || resetInterrupted) {
                Thread.currentThread().interrupt()
            }
            ""
        }
    }

    fun shutdown() {
        shutdownRequests.incrementAndGet()
        try {
            activeSession.get()?.destroy()
            activeReadTask.get()?.cancel()

            lock.withLock {
                activeReadTask.getAndSet(null)?.cancel()
                destroySession()
                activeSession.getAndSet(null)?.destroy()
                discardReaderExecutor()
                lastStartFailedMs = 0L
            }
        } finally {
            shutdownRequests.decrementAndGet()
        }
    }

    fun isAlive(): Boolean = lock.withLock {
        session?.process?.let(::isProcessAlive) == true
    }

    fun sessionType(): String? = lock.withLock { session?.type }

    private fun ensureAlive(): ShellSession? {
        if (isShuttingDown()) return null
        val current = session
        if (current != null && isProcessAlive(current.process)) {
            return if (isShuttingDown()) {
                destroySession()
                null
            } else {
                current
            }
        }
        if (current != null) destroySession()
        if (isShuttingDown()) return null

        val now = nowMs()
        if (lastStartFailedMs > 0L && now - lastStartFailedMs < retryCooldownMs) return null

        return try {
            val created = sessionFactory()
            if (created == null) {
                if (!isShuttingDown()) lastStartFailedMs = now
                return null
            }

            session = created
            activeSession.set(created)
            if (isShuttingDown()) {
                destroySession()
                null
            } else {
                lastStartFailedMs = 0L
                created
            }
        } catch (exception: Exception) {
            if (!isShuttingDown()) {
                lastStartFailedMs = now
                reportFailure("<start>", exception)
            }
            null
        }
    }

    /** Returns whether this thread was interrupted while waiting for reader termination. */
    private fun resetAfterCommandFailure(readTask: ReadTask?): Boolean {
        destroySession()
        readTask?.cancel()
        if (readTask == null) {
            discardReaderExecutor()
            return false
        }

        val readerStopped = try {
            readTask.completion.await(readerStopWaitMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            discardReaderExecutor()
            activeReadTask.compareAndSet(readTask, null)
            return true
        }
        if (!readerStopped) discardReaderExecutor()
        activeReadTask.compareAndSet(readTask, null)
        return false
    }

    private fun destroySession(graceful: Boolean = false) {
        val oldSession = session
        session = null
        if (oldSession != null) activeSession.compareAndSet(oldSession, null)
        oldSession?.destroy(graceful)
    }

    private fun readerExecutor(): ExecutorService {
        val current = readerExecutor
        if (current != null && !current.isShutdown) return current
        return executorFactory().also { readerExecutor = it }
    }

    private fun discardReaderExecutor() {
        readerExecutor?.shutdownNow()
        readerExecutor = null
    }

    private fun isShuttingDown(): Boolean = shutdownRequests.get() > 0

    private fun unwrap(exception: Exception): Exception {
        return if (exception is ExecutionException) {
            (exception.cause as? Exception) ?: exception
        } else {
            exception
        }
    }

    private fun reportFailure(command: String, exception: Exception) {
        try {
            onCommandFailure(command, exception)
        } catch (_: Exception) {
            // Diagnostics must not prevent cleanup or change the public failure contract.
        }
    }

    private class ReadTask {
        val completion = CountDownLatch(1)
        @Volatile var future: Future<ShellReadResult>? = null

        fun cancel() {
            future?.cancel(true)
        }
    }
}

private val shellReaderThreadCounter = AtomicInteger()

internal fun newShellReaderExecutor(): ExecutorService {
    return Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RootShell-reader-${shellReaderThreadCounter.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
}
