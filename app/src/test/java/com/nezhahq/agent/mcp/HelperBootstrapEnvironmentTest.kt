package com.nezhahq.agent.mcp

import com.nezhahq.agent.core.mcp.ExecRequest
import com.nezhahq.agent.mcp.exec.ExecEnvironment
import com.nezhahq.agent.mcp.transport.HelperBootstrapEnvironment
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HelperBootstrapEnvironmentTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun copiesOnlyPlatformBootstrapAllowlistAndForcesInstalledApkAndLocalBaseEnvironment() {
        val platform = mapOf(
            "BOOTCLASSPATH" to "/apex/art/core.jar:/system/framework/framework.jar",
            "DEX2OATBOOTCLASSPATH" to "/apex/art/core.jar", "ANDROID_ART_ROOT" to "/apex/com.android.art",
            "ANDROID_I18N_ROOT" to "/apex/com.android.i18n", "ANDROID_TZDATA_ROOT" to "/apex/com.android.tzdata",
            "ANDROID_RUNTIME_ROOT" to "/apex/legacy-runtime",
            "CLASSPATH" to "/stale/other.apk", "PATH" to "/private/bin", "TMPDIR" to "/private/tmp",
            "ANDROID_ROOT" to "/private/root", "ANDROID_DATA" to "/private/data",
            "HOME" to "/private/home", "TOKEN" to "credential", "LD_PRELOAD" to "/private/hook.so",
            "JAVA_TOOL_OPTIONS" to "-Dprivate=value"
        )
        val environment = HelperBootstrapEnvironment.create("/installed/base.apk", "/local/cache", platform)
        assertEquals("/installed/base.apk", environment["CLASSPATH"])
        assertEquals("/local/cache", environment["TMPDIR"])
        assertEquals("/system", environment["ANDROID_ROOT"])
        assertEquals("/data", environment["ANDROID_DATA"])
        assertEquals("/system/bin:/system/xbin:/vendor/bin", environment["PATH"])
        val copied = setOf("BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT", "ANDROID_TZDATA_ROOT", "ANDROID_RUNTIME_ROOT")
        for (name in copied) assertEquals(platform[name], environment[name])
        assertEquals(copied + setOf("CLASSPATH", "PATH", "ANDROID_ROOT", "ANDROID_DATA", "TMPDIR"), environment.keys)
    }

    @Test fun absentLegacyVariablesAreOptionalAndHelperBootstrapIsNotInheritedByTargetExec() {
        val bootstrap = HelperBootstrapEnvironment.create("/installed/base.apk", "/local/cache", emptyMap())
        assertEquals(setOf("CLASSPATH", "PATH", "ANDROID_ROOT", "ANDROID_DATA", "TMPDIR"), bootstrap.keys)
        val target = ExecEnvironment(temporary.root, temporary.root).prepare(ExecRequest("example", env = mapOf("EXPLICIT" to "value")))
        assertEquals(setOf("PATH", "ANDROID_ROOT", "ANDROID_DATA", "TMPDIR", "EXPLICIT"), target.environment.keys)
        assertFalse(target.environment.containsKey("BOOTCLASSPATH"))
        assertFalse(target.environment.containsKey("CLASSPATH"))
    }
}
