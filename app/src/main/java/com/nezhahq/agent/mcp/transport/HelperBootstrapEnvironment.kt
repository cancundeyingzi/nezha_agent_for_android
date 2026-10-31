package com.nezhahq.agent.mcp.transport

/** VM bootstrap only. This environment is never used as the target Exec environment. */
object HelperBootstrapEnvironment {
    private val platformNames = setOf(
        "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH", "ANDROID_ART_ROOT",
        "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT", "ANDROID_RUNTIME_ROOT"
    )

    /** All arguments come from trusted local installation/platform state, never Task.data/env. */
    fun create(apkPath: String, temporaryPath: String, platformEnvironment: Map<String, String>): Map<String, String> =
        linkedMapOf(
            "CLASSPATH" to apkPath, "PATH" to "/system/bin:/system/xbin:/vendor/bin",
            "ANDROID_ROOT" to "/system", "ANDROID_DATA" to "/data", "TMPDIR" to temporaryPath
        ).apply {
            for (name in platformNames) {
                platformEnvironment[name]?.takeIf { it.isNotEmpty() }?.let { put(name, it) }
            }
        }
}
