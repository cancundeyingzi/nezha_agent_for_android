package com.nezhahq.agent.executor

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用户应用查询器，服务于 `@agent apps` 虚拟指令。
 *
 * 只输出用户安装的应用：系统预装应用与“系统应用更新包”都会被过滤。
 * 运行/缓存状态依赖 Root/Shizuku Shell 的 `dumpsys activity processes`，避免
 * Android 普通应用进程 API 在新版本上的可见性缺口导致结果误导用户。
 */
class AppListQuery(private val context: Context) {

    data class InstalledApp(
        val label: String,
        val packageName: String,
        val versionName: String,
        val uid: Int,
        val appBytes: Long?,
        val dataBytes: Long?
    )

    enum class ProcessState(val label: String, val rank: Int) {
        RUNNING("运行中", 0),
        CACHED("缓存中", 1)
    }

    data class UserAppProcess(
        val app: InstalledApp,
        val state: ProcessState,
        val memoryBytes: Long?
    )

    sealed class ProcessQueryResult {
        data class Success(val processes: List<UserAppProcess>) : ProcessQueryResult()
        data class Failure(val reason: String) : ProcessQueryResult()
    }

    suspend fun getInstalledUserApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        loadInstalledUserApps().also {
            Logger.i("AppListQuery: 已查询已安装用户应用，共 ${it.size} 个")
        }
    }

    suspend fun getUserAppProcesses(): ProcessQueryResult = withContext(Dispatchers.IO) {
        val apps = loadInstalledUserApps()
        val appByPackage = apps.associateBy { it.packageName }
        // Isolated: two full dumpsys passes routinely run for seconds, and the metrics loop shares
        // the other shell.
        val output = RootShell.executeIsolated(
            command = """
            dumpsys activity processes 2>/dev/null
            echo $MEMINFO_MARKER
            dumpsys meminfo 2>/dev/null
            """.trimIndent(),
            timeoutMs = 30_000
        )

        if (output.isBlank()) {
            val reason = "Root/Shizuku Shell 不可用或 dumpsys 无输出"
            Logger.i("AppListQuery: 用户应用进程查询失败，高权限 Shell 不可用或 dumpsys 无输出")
            return@withContext ProcessQueryResult.Failure(reason)
        }

        val parts = output.split(MEMINFO_MARKER, limit = 2)
        val processesOutput = parts.firstOrNull().orEmpty()
        if (processesOutput.isBlank()) {
            val reason = "dumpsys activity processes 无输出"
            Logger.i("AppListQuery: 用户应用进程查询失败，$reason")
            return@withContext ProcessQueryResult.Failure(reason)
        }

        val userPackages = appByPackage.keys
        val stateByPackage = parseProcessStates(processesOutput, userPackages)
        val memoryByPackage = parseProcessMemoryBytes(parts.getOrNull(1).orEmpty(), userPackages)
        val processes = stateByPackage.mapNotNull { (packageName, state) ->
            appByPackage[packageName]?.let { app ->
                UserAppProcess(app, state, memoryByPackage[packageName])
            }
        }.sortedWith(
            compareBy<UserAppProcess> { it.state.rank }
                .thenBy { it.app.label.lowercase(Locale.getDefault()) }
                .thenBy { it.app.packageName }
        )

        Logger.i("AppListQuery: 已查询用户应用进程，共 ${processes.size} 个")
        ProcessQueryResult.Success(processes)
    }

    private fun loadInstalledUserApps(): List<InstalledApp> {
        val pm = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(0)
        }

        return packages.asSequence()
            .mapNotNull { packageInfo ->
                val appInfo = packageInfo.applicationInfo ?: return@mapNotNull null
                if (!isUserApplicationFlags(appInfo.flags)) return@mapNotNull null

                val packageName = packageInfo.packageName
                val label = appInfo.loadLabel(pm).toString().trim()
                    .takeIf { it.isNotEmpty() } ?: packageName
                val storage = loadStorageStats(packageName, appInfo)

                InstalledApp(
                    label = label,
                    packageName = packageName,
                    versionName = packageInfo.versionName?.takeIf { it.isNotBlank() } ?: "unknown",
                    uid = appInfo.uid,
                    appBytes = storage.appBytes,
                    dataBytes = storage.dataBytes
                )
            }
            .sortedWith(
                compareBy<InstalledApp> { it.label.lowercase(Locale.getDefault()) }
                    .thenBy { it.packageName }
            )
            .toList()
    }

    private data class AppStorageStats(
        val appBytes: Long?,
        val dataBytes: Long?
    )

    private fun loadStorageStats(packageName: String, appInfo: ApplicationInfo): AppStorageStats {
        val fallbackAppBytes = estimateCodeBytes(appInfo)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return AppStorageStats(fallbackAppBytes, null)
        }

        return try {
            val statsManager = context.getSystemService(android.app.usage.StorageStatsManager::class.java)
                ?: return AppStorageStats(fallbackAppBytes, null)
            val stats = statsManager.queryStatsForPackage(
                appInfo.storageUuid,
                packageName,
                Process.myUserHandle()
            )
            AppStorageStats(
                appBytes = stats.appBytes.takeIf { it >= 0L } ?: fallbackAppBytes,
                dataBytes = (stats.dataBytes + stats.cacheBytes).takeIf { it >= 0L }
            )
        } catch (_: SecurityException) {
            AppStorageStats(fallbackAppBytes, null)
        } catch (_: Exception) {
            AppStorageStats(fallbackAppBytes, null)
        }
    }

    private fun estimateCodeBytes(appInfo: ApplicationInfo): Long? {
        var total = 0L
        var hasFile = false
        val paths = linkedSetOf<String>()

        fun addPath(path: String?) {
            if (!path.isNullOrBlank()) paths.add(path)
        }
        addPath(appInfo.sourceDir)
        addPath(appInfo.publicSourceDir)
        appInfo.splitSourceDirs?.forEach { addPath(it) }
        appInfo.splitPublicSourceDirs?.forEach { addPath(it) }

        paths.forEach { path ->
            val file = File(path)
            if (file.isFile) {
                total += file.length().coerceAtLeast(0L)
                hasFile = true
            }
        }
        return total.takeIf { hasFile }
    }

    companion object {
        private const val MEMINFO_MARKER = "__NEZHA_APPS_MEMINFO__"
        private val PROC_STATE_RE = Regex("""^\s*Proc\s+#\s*\d+:\s*(\S+)""")
        private val PROCESS_NAME_RE = Regex("""\b\d+:([^\s/]+)/(?:u\d+[ai]\d+|\d+)\b""")
        private val MEMINFO_PROCESS_RE = Regex("""^\s*([0-9,]+)K:\s+([^\s(]+)""")

        fun isUserApplicationFlags(flags: Int): Boolean {
            val systemMask = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            return flags and systemMask == 0
        }

        fun parseProcessStates(
            dumpsysOutput: String,
            userPackages: Set<String>
        ): Map<String, ProcessState> {
            if (dumpsysOutput.isBlank() || userPackages.isEmpty()) return emptyMap()

            val result = linkedMapOf<String, ProcessState>()
            dumpsysOutput.lineSequence().forEach { line ->
                val stateToken = PROC_STATE_RE.find(line)?.groupValues?.getOrNull(1) ?: return@forEach
                val processName = PROCESS_NAME_RE.find(line)?.groupValues?.getOrNull(1) ?: return@forEach
                val packageName = normalizeProcessPackage(processName, userPackages) ?: return@forEach
                val state = if (isCachedProcessLine(line, stateToken)) {
                    ProcessState.CACHED
                } else {
                    ProcessState.RUNNING
                }

                // 同一应用存在多个进程时，只要有一个非缓存进程，就按“运行中”展示。
                if (result[packageName] != ProcessState.RUNNING) {
                    result[packageName] = state
                }
            }
            return result
        }

        fun parseProcessMemoryBytes(
            meminfoOutput: String,
            userPackages: Set<String>
        ): Map<String, Long> {
            if (meminfoOutput.isBlank() || userPackages.isEmpty()) return emptyMap()

            val result = linkedMapOf<String, Long>()
            var inProcessSection = false
            meminfoOutput.lineSequence().forEach { line ->
                val trimmed = line.trim()
                when {
                    trimmed == "Total PSS by process:" -> {
                        inProcessSection = true
                        return@forEach
                    }
                    inProcessSection && trimmed.startsWith("Total PSS by ") -> {
                        inProcessSection = false
                        return@forEach
                    }
                    !inProcessSection -> return@forEach
                }

                val match = MEMINFO_PROCESS_RE.find(line) ?: return@forEach
                val kb = match.groupValues[1].replace(",", "").toLongOrNull() ?: return@forEach
                val processName = match.groupValues[2]
                val packageName = normalizeProcessPackage(processName, userPackages) ?: return@forEach
                result[packageName] = (result[packageName] ?: 0L) + kb * 1024L
            }
            return result
        }

        fun normalizeProcessPackage(processName: String, userPackages: Set<String>): String? {
            if (processName in userPackages) return processName

            val baseName = processName.substringBefore(':')
            if (baseName in userPackages) return baseName

            return userPackages
                .asSequence()
                .filter { pkg ->
                    processName.startsWith("$pkg:") || processName.startsWith("$pkg.")
                }
                .maxByOrNull { it.length }
        }

        private fun isCachedProcessLine(line: String, stateToken: String): Boolean {
            val token = stateToken.lowercase(Locale.US)
            return token.startsWith("cch") ||
                    token.startsWith("cached") ||
                    line.contains("(cached", ignoreCase = true) ||
                    line.contains(" CACHED", ignoreCase = false)
        }
    }
}
