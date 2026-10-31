package com.nezhahq.agent.mcp.transport

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import com.nezhahq.agent.core.mcp.McpBusinessError
import com.nezhahq.agent.core.mcp.McpErrorCode
import com.nezhahq.agent.mcp.helper.HelperSessionIdentity
import java.io.FileInputStream

/** One app_process per task, under the application UID. No shell, Service or retained process. */
class ApplicationHelperLauncher(context: Context) : HelperLauncher {
    override val backend = HelperBackend.APPLICATION
    private val apk = context.applicationInfo.sourceDir
    private val files = context.filesDir.absoluteFile
    private val temporary = context.cacheDir.absoluteFile
    private val uid = Process.myUid()

    override suspend fun launch(spec: HelperLaunchSpec): HelperProcessLease {
        require(spec.lifetimeMillis in 1..HelperProtocol.MAX_LIFETIME_MILLIS)
        return HelperIoPools.launch.run(abandoned = { it.close() }) {
            try {
                // Every argument and environment value below comes from this app, never Task.data.
                val builder = ProcessBuilder(
                    "/system/bin/app_process", "/system/bin", HELPER_MAIN,
                    HelperProtocol.nonceArgument(spec.nonce), files.path, temporary.path,
                    spec.lifetimeMillis.toString()
                ).directory(files).redirectErrorStream(false)
                builder.environment().apply {
                    clear()
                    putAll(HelperBootstrapEnvironment.create(apk, temporary.path, System.getenv()))
                }
                ApplicationHelperLease.start(uid, AndroidApplicationProcessControl) { builder.start() }
            } catch (failure: HelperFailure) {
                throw failure
            } catch (_: Exception) {
                throw HelperFailure(McpBusinessError(
                    McpErrorCode.CAPABILITY_UNAVAILABLE, "Application app_process helper could not be started"
                ))
            }
        }
    }

    companion object {
        const val HELPER_MAIN = "com.nezhahq.agent.mcp.helper.HelperMain"
    }
}

private object AndroidApplicationProcessControl : ApplicationProcessControl {
    override fun identity(pid: Int): ApplicationProcessIdentity? {
        val path = "/proc/$pid"
        val stat = FileInputStream("$path/stat").use { HelperSessionIdentity.readSnapshot(it, pid) } ?: return null
        return ApplicationProcessIdentity(Os.stat(path).st_uid, stat)
    }

    override fun killGroup(pid: Int) = Os.kill(-pid, OsConstants.SIGKILL)
}
