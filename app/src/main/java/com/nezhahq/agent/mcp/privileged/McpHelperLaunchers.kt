package com.nezhahq.agent.mcp.privileged

import android.content.Context
import com.nezhahq.agent.mcp.transport.ApplicationHelperLauncher
import com.nezhahq.agent.mcp.transport.HelperBackend
import com.nezhahq.agent.mcp.transport.HelperIoPools
import com.nezhahq.agent.mcp.transport.HelperLaunchSpec
import com.nezhahq.agent.mcp.transport.HelperLauncher
import com.nezhahq.agent.mcp.transport.HelperProcessLease
import com.nezhahq.agent.mcp.transport.HelperProtocol
import com.nezhahq.agent.util.RootShell

object McpHelperLaunchers {
    /** The false branch performs no RootShell, su or Shizuku discovery/initialization. */
    fun select(context: Context, rootMode: Boolean): HelperLauncher = helperLauncherForMode(rootMode, {
        ApplicationHelperLauncher(context)
    }, {
        PrivilegedHelperLauncher(context.applicationInfo.sourceDir, System.getenv(), RootShell.mcpAuthority,
            AndroidPrivilegedGateway)
    })
}

internal fun helperLauncherForMode(
    rootMode: Boolean, application: () -> HelperLauncher, privileged: () -> HelperLauncher
): HelperLauncher = if (rootMode) privileged() else application()

internal class PrivilegedHelperLauncher(
    private val apk: String,
    private val platformEnvironment: Map<String, String>,
    private val authority: PrivilegedHelperAuthority,
    private val gateway: PrivilegedGateway
) : HelperLauncher {
    @Volatile private var selected = HelperBackend.ROOT
    override val backend: HelperBackend get() = selected

    override suspend fun launch(spec: HelperLaunchSpec): HelperProcessLease {
        require(spec.lifetimeMillis in 1..HelperProtocol.MAX_LIFETIME_MILLIS)
        return HelperIoPools.launch.run(abandoned = { it.close() }) {
            val permit = authority.acquire()
            try {
                selected = permit.backend
                val program = TrustedHelperProgram(apk, platformEnvironment, spec)
                PrivilegedHelperLease.start(permit, program) {
                    permit.requireLaunch()
                    if (permit.backend == HelperBackend.SHIZUKU) permit.service!!.launch(
                        program.argv, program.environment.entries.map { "${it.key}=${it.value}" }.toTypedArray(),
                        TrustedHelperProgram.DIRECTORY
                    ) else gateway.launchSu(permit.suPath!!, program.suCommand, program.environment, TrustedHelperProgram.DIRECTORY)
                }
            } catch (failure: Throwable) {
                permit.close()
                throw failure
            }
        }
    }
}
