package com.nezhahq.agent.device

import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nezhahq.agent.core.task.TaskTypes
import com.nezhahq.agent.mcp.transport.ApplicationHelperLauncher
import com.nezhahq.agent.mcp.transport.HelperCall
import com.nezhahq.agent.mcp.transport.HelperDeadline
import com.nezhahq.agent.mcp.transport.HelperExchangeResult
import com.nezhahq.agent.mcp.transport.HelperLifecycleOwner
import com.nezhahq.agent.mcp.transport.HelperLaunchSpec
import com.nezhahq.agent.mcp.transport.McpHelperTransport
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** No class reference/reflection to the app_process entry: only production launch-by-name can load it. */
@RunWith(AndroidJUnit4::class)
class OptimizedApplicationHelperRegressionTest {
    @get:Rule val evidence = DeviceEvidenceRule()

    @Test fun optimizedAppProcessRunsCommandsAsApplicationUidAndRoundTripsFiles() = runBlocking {
        val directory = File(deviceApplication.filesDir, "device-helper-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val owner = HelperLifecycleOwner()
        val transport = McpHelperTransport(owner)
        val native = NativeLaunchProbe(owner)
        val reserved = AtomicInteger()
        var callId = 0L
        var originalFailure: Throwable? = null
        suspend fun exchange(type: Long, data: JSONObject): JSONObject {
            val outcome = transport.exchange(
                native.launcher, HelperCall(++callId, type, data.toString()), HelperDeadline.afterMillis(15_000),
                outputLimit = if (type == TaskTypes.EXEC) 4096 else 0,
                reserveResult = { reserved.incrementAndGet(); AutoCloseable { reserved.decrementAndGet() } }
            )
            assertTrue("app_process transport failed: $outcome; ${native.details()}", outcome is HelperExchangeResult.Completed)
            return (outcome as HelperExchangeResult.Completed).owned.use {
                assertTrue(it.result.successful)
                JSONObject(it.result.data)
            }
        }
        try {
            val payload = "synthetic helper payload 中文\n"
            val target = File(directory, "roundtrip.txt")
            val execute = exchange(TaskTypes.EXEC, exec(
                directory,
                "printf '%s' \"\$DEVICE_PAYLOAD\" > \"\$1\"; /system/bin/id -u",
                listOf(target.absolutePath),
                JSONObject().put("DEVICE_PAYLOAD", payload)
            ))
            assertNoError(execute)
            assertEquals(0, execute.getInt("exit_code"))
            assertEquals(Process.myUid().toString(), execute.getString("stdout").trim())
            assertArrayEquals(payload.toByteArray(), target.readBytes())
            val read = exchange(TaskTypes.FS_READ, JSONObject()
                .put("path", target.absolutePath).put("offset", 0).put("length", 4096).put("encoding", "base64"))
            assertNoError(read)
            assertEquals("base64", read.getString("encoding"))
            assertArrayEquals(payload.toByteArray(), Base64.decode(read.getString("content"), Base64.DEFAULT))

            val listing = exchange(TaskTypes.FS_LIST, JSONObject().put("path", directory.absolutePath).put("show_hidden", true))
            if (Build.VERSION.SDK_INT < 26) {
                assertEquals("bounded_directory_enumeration_unavailable", listing.getString("error"))
            } else {
                assertNoError(listing)
                val entries = listing.getJSONArray("entries")
                assertTrue((0 until entries.length()).any { entries.getJSONObject(it).getString("name") == target.name })
            }
            val remove = exchange(TaskTypes.EXEC, exec(directory, "rm -- \"\$1\"", listOf(target.absolutePath)))
            assertNoError(remove)
            assertEquals(0, remove.getInt("exit_code"))
            assertFalse(target.exists())
            owner.close(10_000)
            native.assertReaped()
            assertEquals(0, reserved.get())
        } catch (failure: Throwable) {
            val reported = AssertionError("Native helper state: ${native.details()}", failure)
            originalFailure = reported
            throw reported
        } finally {
            cleanup(owner, native, directory, originalFailure)
        }
    }

    @Test fun cancellingOptimizedHelperReapsItsRealRunningCommandAndNativeIo() = runBlocking {
        val directory = File(deviceApplication.filesDir, "device-helper-cancel-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val marker = File(directory, "running.pid")
        val owner = HelperLifecycleOwner()
        val transport = McpHelperTransport(owner)
        val native = NativeLaunchProbe(owner)
        val request = exec(directory, "printf '%s' \"\$\$\" > \"\$1\"; exec /system/bin/sleep 30", listOf(marker.absolutePath))
        val work = async(Dispatchers.IO) {
            transport.exchange(native.launcher, HelperCall(100, TaskTypes.EXEC, request.toString()), HelperDeadline.afterMillis(45_000), 4096) {
                AutoCloseable {}
            }
        }
        var originalFailure: Throwable? = null
        try {
            withTimeout(15_000) {
                while (!marker.exists() || marker.length() == 0L) {
                    if (work.isCompleted) error("Helper exited before the command started: ${work.await()}; ${native.details()}")
                    delay(20)
                }
            }
            val pid = marker.readText().trim().toInt()
            assertTrue(pid > 1)
            assertTrue(liveProcess(pid))
            observeLegacyReaperIfRequested()
            withTimeout(10_000) { work.cancelAndJoin(); owner.close(8_000) }
            awaitCondition("cancelled native command exited") { !liveProcess(pid) }
            native.assertReaped()
        } catch (failure: Throwable) {
            val reported = AssertionError("Native helper state: ${native.details()}", failure)
            originalFailure = reported
            throw reported
        } finally {
            cleanup(owner, native, directory, originalFailure, work)
        }
    }

    @Test fun cancellingBeforeHelloAcceptanceStillReapsDetachedNativeHelper() = runBlocking {
        val directory = File(deviceApplication.filesDir, "device-helper-prehello-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val owner = HelperLifecycleOwner()
        val native = NativeLaunchProbe(owner)
        var originalFailure: Throwable? = null
        try {
            val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val lease = native.launcher.launch(HelperLaunchSpec(nonce, 15_000))
            try {
                val pid = native.singlePid()
                awaitCondition("helper setsid before caller accepts HELLO") {
                    val fields = runCatching { File("/proc/$pid/stat").readText().substringAfterLast(") ").split(' ') }.getOrNull()
                    fields != null && fields.size > 3 && fields[2] == pid.toString() && fields[3] == pid.toString()
                }
                // Deliberately neither read nor accept the HELLO. Cancellation at this boundary
                // must still reap the originally captured child rather than merely killing it.
            } finally { lease.close() }
            owner.close(10_000)
            native.assertReaped()
        } catch (failure: Throwable) {
            val reported = AssertionError("Native helper state: ${native.details()}", failure)
            originalFailure = reported
            throw reported
        } finally { cleanup(owner, native, directory, originalFailure) }
    }

    private fun exec(directory: File, script: String, arguments: List<String>, environment: JSONObject = JSONObject()) = JSONObject()
        .put("cmd", "/system/bin/sh")
        .put("args", JSONArray(listOf("-c", script, "device-fixture") + arguments))
        .put("cwd", directory.absolutePath).put("env", environment)
        .put("timeout_seconds", 45).put("max_output_bytes", 4096)

    private fun assertNoError(result: JSONObject) = assertFalse(result.toString(), result.has("error"))

    /** Explicit diagnostic window only; normal regression runs perform no settling delay. */
    private suspend fun observeLegacyReaperIfRequested() {
        val duration = InstrumentationRegistry.getArguments().getString("helperObservationMillis")
            ?.toLongOrNull()?.coerceIn(0, 5_000) ?: 0
        if (duration == 0L) return
        // A normal-group command wakes waitpid(0), exercising its ECHILD path while the isolated
        // helper remains alive. Otherwise a previously blocked watcher can hide a spin-loop bug.
        val wake = ProcessBuilder("/system/bin/sh", "-c", "exit 0").start()
        try {
            val exit = withTimeout(2_000) {
                var result: Int? = null
                while (result == null) {
                    result = try { wake.exitValue() } catch (_: IllegalThreadStateException) { null }
                    if (result == null) delay(10)
                }
                result
            }
            assertEquals(0, exit)
        } finally {
            wake.destroy()
            wake.outputStream.close()
            wake.inputStream.close()
            wake.errorStream.close()
        }
        delay(duration)
    }

    private suspend fun cleanup(
        owner: HelperLifecycleOwner, native: NativeLaunchProbe, directory: File,
        originalFailure: Throwable?, work: Job? = null
    ) {
        var cleanupFailure: Throwable? = null
        try {
            withContext(kotlinx.coroutines.NonCancellable) {
                work?.cancelAndJoin()
                owner.close(10_000)
            }
        } catch (failure: Throwable) {
            cleanupFailure = AssertionError("Native helper cleanup state: ${native.details()}", failure)
        }
        if (!directory.deleteRecursively()) {
            val failure = AssertionError("Fixture directory was not removed")
            if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure.addSuppressed(failure)
        }
        cleanupFailure?.let { if (originalFailure != null) originalFailure.addSuppressed(it) else throw it }
    }

    /** Delegates the unmodified production builder to the OS; diagnostics contain only fixture state. */
    private class NativeLaunchProbe(owner: HelperLifecycleOwner) {
        private val attempts = AtomicInteger()
        private val failure = AtomicReference<String>()
        private val processes = Collections.synchronizedList(mutableListOf<NativeProcess>())
        val launcher = ApplicationHelperLauncher(deviceApplication, owner) { builder ->
            attempts.incrementAndGet()
            try {
                builder.start().also { process ->
                    val pid = Regex("pid=(\\d+)").find(process.toString())?.groupValues?.get(1)?.toIntOrNull()
                    val descriptors = listOf(
                        runCatching { (process.inputStream as? FileInputStream)?.fd }.getOrNull(),
                        runCatching { (process.errorStream as? FileInputStream)?.fd }.getOrNull(),
                        runCatching { (process.outputStream as? FileOutputStream)?.fd }.getOrNull()
                    ).filterNotNull()
                    processes += NativeProcess(process, pid, descriptors)
                }
            }
            catch (error: Exception) {
                failure.set("${error.javaClass.simpleName}: ${error.message}".take(512))
                throw error
            }
        }
        fun singlePid(): Int = synchronized(processes) { checkNotNull(processes.single().pid) }
        fun assertReaped() {
            val captured = synchronized(processes) { processes.toList() }
            assertTrue("No real native process was started", captured.isNotEmpty())
            for (record in captured) {
                val pid = checkNotNull(record.pid) { "Native Process did not expose a PID identity" }
                record.process.exitValue()
                awaitCondition("helper PID $pid reaped, not left as a zombie") { !File("/proc/$pid").exists() }
                if (Build.VERSION.SDK_INT == 23) assertEquals("Capture all three original API23 pipe descriptors", 3, record.descriptors.size)
                for (descriptor in record.descriptors) {
                    assertFalse(descriptor.valid())
                    val closed = assertThrows(ErrnoException::class.java) { Os.fstat(descriptor) }
                    assertEquals(OsConstants.EBADF, closed.errno)
                }
            }
        }
        fun details() = "nativeAttempts=${attempts.get()}, nativeFailure=${failure.get()}, processes=" +
            synchronized(processes) { processes.joinToString("; ") { record ->
                "pid=${record.pid}, exit=${runCatching { record.process.exitValue().toString() }.getOrElse { it.javaClass.simpleName }}, " +
                    "openPipes=${record.descriptors.count { it.valid() }}, procExists=${record.pid?.let { File("/proc/$it").exists() }}"
            } }

        private data class NativeProcess(val process: java.lang.Process, val pid: Int?, val descriptors: List<FileDescriptor>)
    }

    private fun liveProcess(pid: Int): Boolean {
        val stat = runCatching { File("/proc/$pid/stat").readText() }.getOrNull() ?: return false
        val state = stat.substringAfterLast(") ").firstOrNull()
        return state != null && state != 'Z' && state != 'X' && state != 'x'
    }
}
