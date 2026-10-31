package com.nezhahq.agent.util

import android.content.pm.PackageManager
import com.nezhahq.agent.core.security.PrivilegedAccessController
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess

/**
 * Process-wide privileged-shell boundary.
 *
 * Every su/Shizuku entry point checks the process-local authorization controller. Callers may
 * still choose whether a privileged strategy is useful, but they cannot bypass the authorization
 * decision by invoking a lower-level process API.
 */
object RootShell {
    private const val RETRY_COOLDOWN_MS = 30_000L
    private const val MARKER_RANDOM_BYTES = 24
    private val markerRandom = SecureRandom()

    private val managedProcessLock = Any()
    private val managedProcesses = mutableSetOf<ManagedProcess>()
    private val backendPolicy = ShellBackendPolicy()

    private val persistentShell = PersistentShell(
        sessionFactory = ::startSession,
        markerFactory = { ShellMarker(newMarkerToken()) },
        retryCooldownMs = RETRY_COOLDOWN_MS,
        onCommandFailure = { _, exception -> logShellCommandFailure(exception) }
    )

    private val accessController = PrivilegedAccessController(
        cleanup = ::closePrivilegedResources
    )

    /** Applies the persisted process-level root authorization state. */
    fun configureAuthorization(enabled: Boolean) {
        if (enabled) {
            accessController.enable()
        } else {
            accessController.disableAndRevoke()
            backendPolicy.reset()
        }
    }

    /** Returns whether privileged operations are currently authorized for this process. */
    fun isAuthorized(): Boolean = accessController.isEnabled()

    /**
     * Executes [command] on the shared session, returning at most 4 MiB of merged stdout/stderr.
     *
     * Every caller is serialized through one long-lived shell, and a command holds it for its whole
     * timeout. That is the right trade for the short `/proc` reads the metrics loop issues twice a
     * second; anything that can run for seconds must use [executeIsolated] instead.
     */
    fun execute(command: String, timeoutMs: Long = DEFAULT_SHELL_TIMEOUT_MS): String {
        if (!accessController.isEnabled()) return ""
        if (persistentShell.sessionType() == "shizuku" && !isShizukuAvailable()) {
            closePrivilegedResources()
            return ""
        }
        return persistentShell.execute(command, timeoutMs)
    }

    /**
     * Executes [command] on a dedicated process that no other caller waits behind.
     *
     * Holding the shared session for longer than the dashboard's state-report timeout stalls the
     * metrics stream; the dashboard then tears the connection down, taking with it the very
     * transfer that was holding the shell. A 120-second upload copy made that a certainty, so slow
     * work gets its own process and the metrics loop keeps reporting throughout.
     *
     * Returns the output, or an empty string if the shell could not start, the command failed, or
     * it timed out — truncated output is never returned as if it were complete. Destroying the
     * process is what ends a read that has hung: interrupting the thread does not reliably wake a
     * blocked process stream, so both the timeout and cancellation of the caller route through it.
     */
    suspend fun executeIsolated(
        command: String,
        timeoutMs: Long = DEFAULT_SHELL_TIMEOUT_MS
    ): String = withContext(Dispatchers.IO) {
        if (!accessController.isEnabled()) return@withContext ""
        val managed = startManagedShell(workingDirectory = null) ?: return@withContext ""
        val timedOut = AtomicBoolean(false)

        coroutineScope {
            val destroyer = launch {
                try {
                    delay(timeoutMs)
                    timedOut.set(true)
                    Logger.e("RootShell: 隔离命令超时（${timeoutMs}ms），已终止其专用进程")
                } finally {
                    // Also reached when the caller is cancelled, which is what unblocks the read.
                    // close(), not forceClose(): whichever of the two paths destroys the process
                    // first is the one that must also drop it from the registry, and the loser
                    // returns early.
                    managed.close()
                }
            }

            try {
                managed.process.outputStream.apply {
                    write("$command\nexit\n".toByteArray(Charsets.UTF_8))
                    flush()
                }
                val output = readLimitedUtf8(managed.process.inputStream, MAX_SHELL_OUTPUT_BYTES)
                if (timedOut.get()) "" else output
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                Logger.e("RootShell: 隔离命令执行失败 (Error=${exception.javaClass.simpleName})")
                ""
            } finally {
                destroyer.cancel()
                managed.close()
            }
        }
    }

    /** Executes [command] and returns its first non-blank output line. */
    fun executeFirstLine(command: String, timeoutMs: Long = DEFAULT_SHELL_TIMEOUT_MS): String? {
        return execute(command, timeoutMs).lineSequence().firstOrNull { it.isNotBlank() }
    }

    /**
     * Starts one independently managed interactive privileged shell.
     *
     * The returned process is tracked so [shutdown] or a later authorization denial can revoke it.
     */
    internal fun startInteractiveShell(workingDirectory: File): ManagedProcess? {
        return startManagedShell(workingDirectory)
    }

    /**
     * Opens stdout for one privileged command without buffering the complete result in memory.
     * Closing the stream also destroys and unregisters its backing process.
     */
    internal fun openCommandInputStream(command: String): InputStream? {
        val managed = startManagedShell(workingDirectory = null) ?: return null
        return try {
            managed.process.outputStream.apply {
                write("exec $command\n".toByteArray(Charsets.UTF_8))
                flush()
            }
            ManagedProcessInputStream(managed)
        } catch (exception: Exception) {
            managed.close()
            Logger.e("RootShell: 启动流式高权限命令失败 (Error=${exception.javaClass.simpleName})")
            null
        }
    }

    /** Closes the process and reader executor. A later call to [execute] can start them again. */
    fun shutdown() {
        closePrivilegedResources()
    }

    fun isAlive(): Boolean = accessController.isEnabled() && persistentShell.isAlive()

    fun getSessionType(): String? {
        if (!accessController.isEnabled()) return null
        return persistentShell.sessionType()
    }

    /**
     * Starts an authorized privileged shell.
     *
     * Selects one backend from authorization state. A failed Shizuku session never triggers su.
     */
    private fun startSession(): ShellSession? {
        if (!accessController.isEnabled()) return null
        val launched = startShellProcess(workingDirectory = null) ?: return null
        if (!accessController.isEnabled()) {
            ShellSession.destroyProcess(launched.process)
            return null
        }
        return try {
            ShellSession.openRedirected(launched.process, launched.type)
        } catch (exception: Exception) {
            ShellSession.destroyProcess(launched.process)
            throw exception
        }
    }

    private fun startManagedShell(workingDirectory: File?): ManagedProcess? {
        if (!accessController.isEnabled()) return null
        val launched = startShellProcess(workingDirectory) ?: return null
        if (!accessController.isEnabled()) {
            ShellSession.destroyProcess(launched.process)
            return null
        }

        return try {
            // Shizuku does not expose ProcessBuilder.redirectErrorStream; configure the shell once.
            launched.process.outputStream.apply {
                write("exec 2>&1\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            val managed = ManagedProcess(launched.process, launched.type)
            synchronized(managedProcessLock) {
                managedProcesses += managed
            }
            if (!accessController.isEnabled()) {
                managed.close()
                null
            } else {
                managed
            }
        } catch (exception: Exception) {
            ShellSession.destroyProcess(launched.process)
            Logger.e("RootShell: 初始化独立高权限 Shell 失败 (Error=${exception.javaClass.simpleName})")
            null
        }
    }

    private fun startShellProcess(workingDirectory: File?): LaunchedShell? {
        if (!accessController.isEnabled()) return null

        return synchronized(backendPolicy) {
            val backend = backendPolicy.select(
                shizukuAuthorized = isShizukuAvailable(),
                shizukuRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false),
                suInstalled = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
                    .filter { it.isNotBlank() }.any { File(it, "su").canExecute() }
            ) ?: return@synchronized null
            if (!accessController.isEnabled()) return@synchronized null
            when (backend) {
                ShellBackend.SHIZUKU -> startShizukuShell(workingDirectory)
                ShellBackend.SU -> startSuShell(workingDirectory).also {
                    if (it == null) backendPolicy.rejectSu()
                }
            }
        }
    }

    private fun startSuShell(workingDirectory: File?): LaunchedShell? {
        var temporarySuProcess: Process? = null
        try {
            temporarySuProcess = ProcessBuilder("su")
                .redirectErrorStream(true)
                .apply {
                    if (workingDirectory != null) directory(workingDirectory)
                }
                .start()
            if (verifyRootUid(temporarySuProcess)) {
                val launched = LaunchedShell(temporarySuProcess, "su")
                temporarySuProcess = null
                Logger.i("RootShell: su Shell 已建立（Root 模式）。")
                return launched
            }
            Logger.i("RootShell: Root UID 校验未通过，本次启用期间不再请求 su。")
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.i("RootShell: su 启动被中断，取消本次高权限 Shell 建立。")
            return null
        } catch (exception: Exception) {
            Logger.i("RootShell: su 命令执行失败（${exception.javaClass.simpleName}）。")
        } finally {
            temporarySuProcess?.let(ShellSession::destroyProcess)
        }
        return null
    }

    private fun startShizukuShell(workingDirectory: File?): LaunchedShell? {
        var temporaryProcess: Process? = null
        try {
            @Suppress("DEPRECATION")
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            temporaryProcess = method.invoke(
                null,
                arrayOf("sh"),
                null,
                workingDirectory?.absolutePath
            ) as Process
            val remote = temporaryProcess as ShizukuRemoteProcess
            val launched = LaunchedShell(RemoteShellProcess(remote, remote::alive), "shizuku")
            val uid = Shizuku.getUid()
            Logger.i("RootShell: Shizuku Shell 已建立（${if (uid == 0) "Root" else "ADB"} 模式，UID=$uid，状态检查=alive）。")
            temporaryProcess = null
            return launched
        } catch (exception: Exception) {
            Logger.e("RootShell: 已授权的 Shizuku Shell 启动失败 (Error=${exception.javaClass.simpleName})")
            return null
        } finally {
            temporaryProcess?.let(ShellSession::destroyProcess)
        }
    }

    private fun closePrivilegedResources() {
        persistentShell.shutdown()
        val processes = synchronized(managedProcessLock) {
            managedProcesses.toList().also { managedProcesses.clear() }
        }
        processes.forEach(ManagedProcess::forceClose)
    }

    private fun unregister(managedProcess: ManagedProcess) {
        synchronized(managedProcessLock) {
            managedProcesses -= managedProcess
        }
    }

    private fun verifyRootUid(process: Process): Boolean {
        val reader = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "nezha-root-auth").apply { isDaemon = true }
        }
        try {
            val session = ShellSession.openRedirected(process, "su")
            val marker = ShellMarker(newMarkerToken())
            session.writeCommand("id -u", marker)
            val result = reader.submit<ShellReadResult> {
                ShellProtocolReader(maxOutputBytes = 1024).read(session.input, marker)
            }.get(DEFAULT_SHELL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            return result.exitCode == 0 && !result.truncated && result.output.trim() == "0"
        } finally {
            reader.shutdownNow()
        }
    }

    private fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (exception: Exception) {
            Logger.e("RootShell: 检测 Shizuku 状态时异常 (Error=${exception.javaClass.simpleName})")
            false
        }
    }

    private fun newMarkerToken(): String {
        val randomBytes = ByteArray(MARKER_RANDOM_BYTES)
        markerRandom.nextBytes(randomBytes)
        val hex = CharArray(randomBytes.size * 2)
        val alphabet = "0123456789abcdef"
        for (index in randomBytes.indices) {
            val value = randomBytes[index].toInt() and 0xff
            hex[index * 2] = alphabet[value ushr 4]
            hex[index * 2 + 1] = alphabet[value and 0x0f]
        }
        return String(hex)
    }

    private data class LaunchedShell(
        val process: Process,
        val type: String
    )

    internal class ManagedProcess internal constructor(
        val process: Process,
        val type: String
    ) : AutoCloseable {
        private val closed = AtomicBoolean()

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            unregister(this)
            ShellSession.destroyProcess(process)
        }

        internal fun forceClose() {
            if (!closed.compareAndSet(false, true)) return
            ShellSession.destroyProcess(process)
        }
    }

    private class ManagedProcessInputStream(
        private val managedProcess: ManagedProcess
    ) : InputStream() {
        private val delegate = managedProcess.process.inputStream

        override fun read(): Int = delegate.read()

        override fun read(buffer: ByteArray): Int = delegate.read(buffer)

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)

        override fun available(): Int = delegate.available()

        override fun close() {
            managedProcess.close()
        }
    }
}

internal enum class ShellBackend {
    SHIZUKU,
    SU
}

/** Remembers the selected backend until the user disables privileged mode. No launch fallback. */
internal class ShellBackendPolicy {
    private var selected: ShellBackend? = null
    private var suRejected = false

    @Synchronized
    fun select(shizukuAuthorized: Boolean, shizukuRunning: Boolean, suInstalled: Boolean): ShellBackend? {
        if (selected == ShellBackend.SHIZUKU) return if (shizukuAuthorized) selected else null
        if (selected == ShellBackend.SU) return if (suRejected || !suInstalled) null else selected
        selected = when {
            shizukuAuthorized || shizukuRunning -> ShellBackend.SHIZUKU
            !suInstalled || suRejected -> null
            else -> ShellBackend.SU
        }
        return if (selected == ShellBackend.SHIZUKU && !shizukuAuthorized) null else selected
    }

    @Synchronized
    fun rejectSu() { suRejected = true }

    @Synchronized
    fun reset() {
        selected = null
        suRejected = false
    }
}

/** Command text and exception messages can both contain credentials; retain only the failure type. */
internal fun logShellCommandFailure(failure: Throwable) {
    Logger.e("RootShell: 命令或会话失败，正在重置会话 (Error=${failure.javaClass.simpleName})")
}
