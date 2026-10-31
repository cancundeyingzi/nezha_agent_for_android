package com.nezhahq.agent.core.mcp

import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.core.model.RemoteCapability
import com.nezhahq.agent.core.task.TaskTypes

enum class McpTaskFamily(val taskType: Long, val capability: RemoteCapability) {
    EXEC(TaskTypes.EXEC, RemoteCapability.SHELL),
    FS_LIST(TaskTypes.FS_LIST, RemoteCapability.FILE_MANAGER),
    FS_READ(TaskTypes.FS_READ, RemoteCapability.FILE_MANAGER)
}

/** R1 identification only; this does not advertise handlers as installed in the existing runtime. */
object McpPolicy {
    fun family(taskType: Long): McpTaskFamily? = when (taskType) {
        TaskTypes.EXEC -> McpTaskFamily.EXEC
        TaskTypes.FS_LIST -> McpTaskFamily.FS_LIST
        TaskTypes.FS_READ -> McpTaskFamily.FS_READ
        else -> null
    }

    fun requiredCapability(taskType: Long): RemoteCapability? = family(taskType)?.capability

    /** Call both at routing and immediately before execution; this function never caches grants. */
    fun denial(taskType: Long, capabilities: RemoteCapabilities): McpBusinessError? {
        val required = requiredCapability(taskType) ?: return null
        if (capabilities.isEnabled(required)) return null
        val message = when (required) {
            RemoteCapability.SHELL -> "Remote shell execution is disabled on Android Agent."
            RemoteCapability.FILE_MANAGER -> "Remote file manager is disabled on Android Agent."
            RemoteCapability.NAT -> error("NAT is not an R1 MCP capability")
        }
        return McpBusinessError(McpErrorCode.PERMISSION_DENIED, message)
    }
}

/**
 * Host.version is a Dashboard protocol-entry compatibility marker, not the Android APK version.
 * R1 implements only tasks 15..17. Callers must supply the real APK version and enable this marker
 * only when those handlers are installed. Build metadata preserves the Android identity/version.
 */
object McpProtocolVersion {
    const val COMPATIBILITY_BASE = "2.1.0"

    fun hostVersion(androidApplicationVersion: String): String {
        require(androidApplicationVersion.matches(Regex("[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*"))) {
            "Android application version must be nonempty SemVer build metadata"
        }
        return "$COMPATIBILITY_BASE+android.$androidApplicationVersion"
    }
}
