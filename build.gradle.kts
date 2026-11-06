import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.dsl.LockMode
import org.gradle.api.artifacts.result.UnresolvedDependencyResult

// Top-level build file where you can add configuration options common to all sub-projects/modules.
// Versions live in gradle/libs.versions.toml; nothing here should carry a version literal.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.protobuf) apply false
}

// Includes application, JVM/device test, code generation and tool configurations. Plugin/buildscript
// classpaths are a separate Gradle scope; their direct versions remain in the version catalog.
subprojects {
    dependencyLocking {
        lockAllConfigurations()
        lockMode.set(LockMode.STRICT)
    }

    tasks.register("resolveAndLockAll") {
        group = "verification"
        description = "Resolve every resolvable configuration and write dependency locks explicitly."
        notCompatibleWithConfigurationCache("Inspects and resolves project configurations at execution time")
        doLast {
            check(gradle.startParameter.isWriteDependencyLocks || gradle.startParameter.lockedDependenciesToUpdate.isNotEmpty()) {
                "$path requires --write-locks or --update-locks. Use verifyDependencyLocks for read-only verification."
            }
            project.resolveDependencyLockCoverage(verify = false)
        }
    }

    tasks.register("verifyDependencyLocks") {
        group = "verification"
        description = "Verify every resolvable configuration against the committed dependency locks."
        notCompatibleWithConfigurationCache("Inspects and resolves project configurations at execution time")
        doLast {
            check(!gradle.startParameter.isWriteDependencyLocks && gradle.startParameter.lockedDependenciesToUpdate.isEmpty()) {
                "$path must not rewrite locks. Remove --write-locks/--update-locks."
            }
            project.resolveDependencyLockCoverage(verify = true)
        }
    }
}

fun Project.resolveDependencyLockCoverage(verify: Boolean) {
    val resolvable = configurations.filter { it.isCanBeResolved }.sortedBy { it.name }
    val requiredClasspaths = when (path) {
        ":app" -> listOf("debug", "release", "r1test").flatMap { variant ->
            listOf("CompileClasspath", "RuntimeClasspath", "UnitTestCompileClasspath", "UnitTestRuntimeClasspath")
                .map { variant + it }
        } + listOf("r1testAndroidTestCompileClasspath", "r1testAndroidTestRuntimeClasspath")
        ":core" -> listOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath")
        else -> emptyList()
    }
    val report = layout.buildDirectory.file("reports/dependency-locks/coverage.tsv").get().asFile
    report.parentFile.mkdirs()
    val rows = mutableListOf("configuration\trequired_nonempty\tmodule_count\tstatus\texternal_modules\tdetail")
    val failures = mutableListOf<String>()
    val lockedModules = mutableMapOf<String, MutableSet<String>>()
    if (verify) {
        val lockFile = file("gradle.lockfile")
        if (!lockFile.isFile) {
            failures += "Missing ${lockFile.name}; generate and commit real dependency locks first."
        } else {
            try {
                lockFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                    val separator = line.indexOf('=')
                    check(separator > 0) { "Malformed dependency lock entry: $line" }
                    val module = line.substring(0, separator)
                    line.substring(separator + 1).split(',').filter { it.isNotBlank() }.forEach { configuration ->
                        val entries = lockedModules.getOrPut(configuration) { sortedSetOf() }
                        if (module != "empty") entries += module
                    }
                }
            } catch (failure: Exception) {
                val detail = (failure.message ?: failure.javaClass.simpleName).replace(Regex("[\\t\\r\\n]+"), " ")
                rows += "<lockfile>\ttrue\t0\tFAILED\t\t$detail"
                failures += "${lockFile.name}: $detail"
            }
        }
    }
    val missing = requiredClasspaths - resolvable.map { it.name }.toSet()
    missing.forEach { name ->
        rows += "$name\ttrue\t0\tMISSING\t\tRequired classpath is not resolvable"
        failures += "$name is missing or not resolvable"
    }
    resolvable.forEach { configuration ->
        val required = configuration.name in requiredClasspaths
        var modules: Set<String> = emptySet()
        try {
            // Check the complete dependency graph, including project edges. Only Maven components
            // have lock entries. Request their artifacts without asking AGP to choose an untyped
            // local app output (classes, symbols, metadata, etc.) for test compile configurations.
            val resolution = configuration.incoming.resolutionResult
            modules = resolution.allComponents.mapNotNull { component ->
                (component.id as? ModuleComponentIdentifier)?.let { "${it.group}:${it.module}:${it.version}" }
            }.toSortedSet()
            val unresolved = resolution.allDependencies.filterIsInstance<UnresolvedDependencyResult>()
            check(unresolved.isEmpty()) {
                unresolved.take(8).joinToString(" | ") {
                    "${it.attempted.displayName}: ${it.failure.lockFailureDescription()}"
                }
            }
            configuration.incoming.artifactView {
                componentFilter { it is ModuleComponentIdentifier }
            }.files.files
            check(!required || modules.isNotEmpty()) { "Required classpath has no external modules" }
            if (verify) {
                check(lockedModules.containsKey(configuration.name)) { "Configuration is absent from gradle.lockfile" }
                check(lockedModules.getValue(configuration.name) == modules) {
                    "Resolved external modules differ from gradle.lockfile"
                }
            }
            val status = if (verify) "VERIFIED" else "RESOLVED_FOR_WRITE"
            rows += "${configuration.name}\t$required\t${modules.size}\t$status\t${modules.joinToString(",")}\t"
        } catch (failure: Exception) {
            val unresolved = runCatching {
                configuration.incoming.resolutionResult.allDependencies
                    .filterIsInstance<UnresolvedDependencyResult>().take(8)
                    .map { "${it.attempted.displayName}: ${it.failure.lockFailureDescription()}" }
            }.getOrDefault(emptyList())
            val detail = (listOf(failure.lockFailureDescription()) + unresolved).distinct()
                .joinToString(" | ").take(24_000).replace(Regex("[\\t\\r\\n]+"), " ")
            rows += "${configuration.name}\t$required\t${modules.size}\tFAILED\t${modules.joinToString(",")}\t$detail"
            failures += "${configuration.name}: $detail"
        }
    }
    report.writeText(rows.joinToString("\n", postfix = "\n"))
    logger.lifecycle("$path dependency lock coverage: ${resolvable.size} configurations; $report")
    check(failures.isEmpty()) { "Dependency lock verification failed:\n${failures.joinToString("\n")}\nSee $report" }
}

fun Throwable.lockFailureDescription(): String {
    val messages = mutableListOf<String>()
    val visited = mutableSetOf<Throwable>()
    var current: Throwable? = this
    while (current != null && messages.size < 8 && visited.add(current)) {
        messages += "${current.javaClass.simpleName}: ${current.message.orEmpty().take(6_000)}"
        current = current.cause
    }
    return messages.joinToString(" <- ")
}
