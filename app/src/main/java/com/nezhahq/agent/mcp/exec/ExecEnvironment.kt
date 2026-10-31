package com.nezhahq.agent.mcp.exec

import com.nezhahq.agent.core.mcp.ExecRequest
import com.nezhahq.agent.core.mcp.McpBusinessError
import com.nezhahq.agent.core.mcp.McpErrorCode
import java.io.File

/** Pure argument checks also run in the parent, before a helper can be launched. */
object ExecArguments {
    private val environmentName = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun error(request: ExecRequest): McpBusinessError? = when {
        '\u0000' in request.cmd -> invalid("cmd contains a NUL character")
        request.args.any { '\u0000' in it } -> invalid("args contains a NUL character")
        request.cwd?.contains('\u0000') == true -> invalid("cwd contains a NUL character")
        request.env.keys.any { !environmentName.matches(it) } -> invalid("env contains an invalid key")
        request.env.values.any { '\u0000' in it } -> invalid("env contains a NUL character")
        else -> null
    }

    private fun invalid(message: String) = McpBusinessError(McpErrorCode.INVALID_ARGUMENT, message)
}

data class ExecStartSpec(val command: List<String>, val directory: File, val environment: Map<String, String>)

fun interface ExecProcessStarter {
    fun start(spec: ExecStartSpec): Process
}

/** ProcessBuilder receives the argument vector directly; only an explicitly requested shell parses it. */
object SystemExecProcessStarter : ExecProcessStarter {
    override fun start(spec: ExecStartSpec): Process = ProcessBuilder(spec.command)
        .directory(spec.directory)
        .redirectErrorStream(false)
        .apply { environment().apply { clear(); putAll(spec.environment) } }
        .start()
}

/** Directory operations belong in the isolated helper; they are never coroutine-only timeouts in app. */
class ExecEnvironment(private val defaultDirectory: File, private val temporaryDirectory: File) {
    fun prepare(request: ExecRequest): ExecStartSpec {
        ExecArguments.error(request)?.let { throw ExecPreparationFailure(it.message) }
        val directory = try {
            val requested = request.cwd?.takeIf { it.isNotEmpty() }?.let(::File) ?: defaultDirectory
            (if (requested.isAbsolute) requested else File(defaultDirectory, requested.path)).canonicalFile
        } catch (_: Exception) { throw ExecPreparationFailure("cwd is not accessible") }
        if (!directory.isDirectory || !directory.canRead() || !directory.canExecute()) {
            throw ExecPreparationFailure("cwd is not an accessible directory")
        }
        val environment = linkedMapOf(
            "PATH" to "/system/bin:/system/xbin:/vendor/bin",
            "ANDROID_ROOT" to "/system", "ANDROID_DATA" to "/data",
            "TMPDIR" to temporaryDirectory.absolutePath
        )
        environment.putAll(request.env)
        return ExecStartSpec(listOf(request.cmd) + request.args, directory, environment)
    }
}

class ExecPreparationFailure(val safeMessage: String) : Exception(safeMessage)
