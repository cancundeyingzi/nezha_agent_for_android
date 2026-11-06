package com.nezhahq.agent.mcp.transport

import android.annotation.SuppressLint
import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants
import com.nezhahq.agent.core.mcp.McpBusinessError
import com.nezhahq.agent.core.mcp.McpErrorCode
import com.nezhahq.agent.mcp.helper.HelperSessionIdentity
import java.io.FileInputStream
import java.lang.reflect.InvocationTargetException

/**
 * Fixed Android 6 libcore ABI only. No hidden call is made on newer Android releases.
 * AOSP android-6.0.1_r81 ProcessManager uses waitpid(0); its registered child can become unreapable
 * by that thread after setsid. Preflight all private members before starting a native child.
 */
internal object Api23ChildReaper {
    private val framework by lazy { Framework() }

    fun prepare(expectedUid: Int): ApplicationProcessExitFactory {
        check(Build.VERSION.SDK_INT == 23)
        val selected = try { framework } catch (_: Exception) { throw unavailable() }
        val parentPid = Process.myPid()
        return ApplicationProcessExitFactory { process ->
            try {
                check(process.javaClass == selected.processClass) { "Unexpected Android process implementation" }
                val pid = selected.pidField.getInt(process)
                DetachedChildExitProof.capture(process, pid, expectedUid, parentPid, selected)
            } catch (_: Exception) {
                throw unavailable()
            }
        }
    }

    private fun unavailable() = HelperFailure(McpBusinessError(
        McpErrorCode.CAPABILITY_UNAVAILABLE,
        "Android 6 app_process child reaper is unavailable on this platform"
    ))

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private class Framework : DetachedChildReaper {
        private val managerClass = Class.forName("java.lang.ProcessManager")
        val processClass: Class<*> = Class.forName("java.lang.ProcessManager\$ProcessImpl")
        val pidField = processClass.getDeclaredField("pid").apply {
            check(type == Int::class.javaPrimitiveType)
            isAccessible = true
        }
        private val manager = managerClass.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
        private val registryField = managerClass.getDeclaredField("processReferences").apply { isAccessible = true }
        override val registry = checkNotNull(registryField.get(manager)) as MutableMap<*, *>
        private val setExitValue = processClass.getDeclaredMethod(
            "setExitValue", Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
        private val statusClass = Class.forName("android.util.MutableInt")
        private val statusConstructor = statusClass.getConstructor(Int::class.javaPrimitiveType)
        private val statusValue = statusClass.getField("value").apply { check(type == Int::class.javaPrimitiveType) }
        private val osClass = Class.forName("libcore.io.Os")
        private val os = Class.forName("libcore.io.Libcore").getField("os").get(null)
        private val waitpid = osClass.getMethod(
            "waitpid", Int::class.javaPrimitiveType, statusClass, Int::class.javaPrimitiveType
        ).apply { check(returnType == Int::class.javaPrimitiveType) }

        override fun observe(pid: Int): ChildProcessObservation? = runCatching {
            val path = "/proc/$pid"
            val stat = FileInputStream("$path/stat").use { HelperSessionIdentity.readSnapshot(it, pid) } ?: return null
            ChildProcessObservation(Os.stat(path).st_uid, stat)
        }.getOrNull()

        override fun kill(pid: Int) = Os.kill(pid, OsConstants.SIGKILL)

        override fun waitNoHang(pid: Int): NativeChildWait {
            val status = statusConstructor.newInstance(0)
            val waited = invokeUnwrapped { waitpid.invoke(os, pid, status, OsConstants.WNOHANG) } as Int
            val value = statusValue.getInt(status)
            return NativeChildWait(waited, when {
                waited != pid -> null
                OsConstants.WIFEXITED(value) -> OsConstants.WEXITSTATUS(value)
                OsConstants.WIFSIGNALED(value) -> OsConstants.WTERMSIG(value)
                else -> null
            })
        }

        override fun publishExit(process: java.lang.Process, exitCode: Int) {
            invokeUnwrapped { setExitValue.invoke(process, exitCode) }
        }

        private inline fun invokeUnwrapped(call: () -> Any?): Any? = try { call() }
            catch (failure: InvocationTargetException) { throw failure.targetException }
    }
}
