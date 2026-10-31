package com.nezhahq.agent.mcp.privileged

import com.nezhahq.agent.mcp.transport.ApplicationHelperLauncher
import com.nezhahq.agent.mcp.transport.HelperBootstrapEnvironment
import com.nezhahq.agent.mcp.transport.HelperLaunchSpec
import com.nezhahq.agent.mcp.transport.HelperProtocol

/** Every value here is local installation/platform state or a program-generated nonce. */
internal class TrustedHelperProgram(
    val apk: String,
    platformEnvironment: Map<String, String>,
    val spec: HelperLaunchSpec
) {
    val nonce = HelperProtocol.nonceArgument(spec.nonce)
    val environment = HelperBootstrapEnvironment.create(apk, TEMPORARY, platformEnvironment)
    val helperName = "nezha-mcp-$nonce"
    val proxyName = "nezha-mcp-proxy-$nonce"
    private val bootstrap = environment.entries.joinToString(" ") { "${it.key}=${quote(it.value)}" }

    // A remote server may destroy its Process when the application Binder dies. It must own this
    // proxy, never HelperMain itself. mksh replaces a background job's fd0 before applying its
    // redirections, so <&0 would copy /dev/null. Save input first, then close fd3 in both owners.
    val script = "exec 3<&0\n/system/bin/env -i $bootstrap /system/bin/app_process /system/bin " +
        "${quote("--nice-name=$helperName")} ${ApplicationHelperLauncher.HELPER_MAIN} " +
        "${quote(nonce)} $DIRECTORY $TEMPORARY ${spec.lifetimeMillis} <&3 3<&- &\n" +
        "child=\$!\nexec 3<&-\nwait \"\$child\""
    val argv = arrayOf("/system/bin/sh", "-c", script, proxyName)
    val suCommand = argv.joinToString(" ", transform = ::quote)

    fun probe(identity: com.nezhahq.agent.mcp.transport.HelperProtocol.Hello?, expectedUid: Int): Array<String> = arrayOf(
        "/system/bin/env", "-i", *environment.entries.map { "${it.key}=${it.value}" }.toTypedArray(),
        "/system/bin/app_process", "/system/bin", "--nice-name=nezha-mcp-cleanup",
        CLEANUP_MAIN, nonce, expectedUid.toString(), identity?.pid?.toString() ?: "0",
        identity?.startTimeTicks?.toString() ?: "0"
    )

    companion object {
        const val DIRECTORY = "/system/bin"
        const val TEMPORARY = "/data/local/tmp"
        const val CLEANUP_MAIN = "com.nezhahq.agent.mcp.privileged.HelperCleanupMain"
        fun quote(value: String): String {
            require('\u0000' !in value)
            return "'" + value.replace("'", "'\\''") + "'"
        }
    }
}
