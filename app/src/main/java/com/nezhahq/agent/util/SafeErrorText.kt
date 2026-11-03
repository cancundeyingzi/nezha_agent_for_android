package com.nezhahq.agent.util

import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import java.util.Collections
import java.util.IdentityHashMap

/** The only exception-to-display conversion. Exception messages are never inspected or copied. */
object SafeErrorText {
    private const val MAX_EXCEPTIONS = 8
    private const val MAX_FRAMES = 12
    private val identifier = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,95}")
    private val className = Regex("(?:[A-Za-z_$][A-Za-z0-9_$]*\\.)+[A-Za-z_$][A-Za-z0-9_$]*")
    private val stackPackages = listOf("com.nezhahq.agent.", "io.grpc.", "kotlin.", "kotlinx.", "java.", "javax.", "android.", "okhttp3.")

    fun describe(failure: Throwable): String = runCatching {
        val type = typeName(failure)
        val code = exceptionGraph(failure).firstNotNullOfOrNull { (_, exception) ->
            when (exception) {
                is StatusException -> exception.status.code.name
                is StatusRuntimeException -> exception.status.code.name
                else -> null
            }
        }
        if (code == null) type else "$type, Code=$code"
    }.getOrDefault("Throwable")

    fun display(description: String, failure: Throwable): String = runCatching {
        "${SafeLogText.message(description)}（${describe(failure)}）"
    }.getOrDefault("操作失败（Throwable）")

    /** Local diagnostics contain bounded types and code locations, never messages or file names. */
    internal fun diagnostic(failure: Throwable): String = runCatching {
        buildString {
            for ((relation, exception) in exceptionGraph(failure)) {
                if (isNotEmpty()) append(" | ")
                append(relation).append('=').append(typeName(exception))
                val frames = runCatching { exception.stackTrace }.getOrDefault(emptyArray())
                for (frame in frames.take(MAX_FRAMES)) {
                    val owner = frame.className.take(160)
                    val method = frame.methodName.take(96)
                    if (stackPackages.none { owner.startsWith(it) } || !className.matches(owner) ||
                        !(identifier.matches(method) || method == "<init>" || method == "<clinit>")) continue
                    append(" at ").append(owner).append('#').append(method)
                    if (frame.lineNumber >= 0) append(':').append(frame.lineNumber)
                }
            }
        }
    }.getOrDefault("error=Throwable")

    private fun typeName(failure: Throwable): String = runCatching {
        failure.javaClass.simpleName.takeIf(identifier::matches) ?: "Throwable"
    }.getOrDefault("Throwable")

    private fun exceptionGraph(failure: Throwable): List<Pair<String, Throwable>> {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque<Pair<String, Throwable>>()
        val result = ArrayList<Pair<String, Throwable>>(MAX_EXCEPTIONS)
        pending.addLast("error" to failure)
        while (pending.isNotEmpty() && result.size < MAX_EXCEPTIONS) {
            val next = pending.removeFirst()
            if (!seen.add(next.second)) continue
            result += next
            runCatching { next.second.cause }.getOrNull()?.let { pending.addLast("cause" to it) }
            runCatching { next.second.suppressed }.getOrDefault(emptyArray()).take(4)
                .forEach { pending.addLast("suppressed" to it) }
        }
        return result
    }
}

/**
 * A final guard for business descriptions. Callers must supply fixed descriptions, types or numeric
 * IDs, not upstream content. This guard never makes an arbitrary exception message safe: those are
 * discarded by [SafeErrorText] before formatting. Sensitive context and its trailing payload are
 * omitted together rather than trying to reconstruct unknown credential/body formats.
 */
internal object SafeLogText {
    const val MAX_MESSAGE_CHARS = 384
    private const val DNS_LABEL = "[\\p{L}\\p{N}\\p{M}_-]+"
    private const val DNS_LAST_LABEL = "[\\p{L}\\p{N}\\p{M}_-]*\\p{L}[\\p{L}\\p{N}\\p{M}_-]*"
    private val sensitiveContext = listOf(
        Regex("(?i)\\b(?:authorization|proxy-authorization|bearer|basic|cookie|set-cookie|(?:client[-_]?)?secret|(?:(?:access|refresh)[-_]?)?token|password|passwd|api[-_]?key|credential|body|payload|headers?|path|file|host|server|address|endpoint|url|uri)\\b[\"']?\\s*[:= ]"),
        Regex("(?:密钥|密码|令牌|请求体|响应体|路径|地址|服务器)\\s*[:=：]"),
        Regex("(?i)\\b[a-z][a-z0-9+.-]*://"),
        // Labels define the sensitive start; no punctuation allowlist is needed after a domain.
        // Unicode letters/marks and the IDNA dot forms also cover internationalized host names.
        // A letter in the final label keeps numeric versions/stages such as P1.5 intact.
        Regex("$DNS_LABEL(?:[.。．｡]$DNS_LABEL)*[.。．｡]$DNS_LAST_LABEL"),
        Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}"),
        Regex("(?i)(?<![a-z0-9])(?:[a-f0-9]{0,4}:){2,}[a-f0-9:.%]*"),
        Regex("[A-Za-z]:[\\\\/]|\\\\\\\\|(?:^|[\\s(（\"'=：:])(?:\\.{1,2}[\\\\/]|/)[^\\s]*"),
        // A relative/home path is sensitive even without a path= key or an absolute prefix.
        Regex("[\\p{L}\\p{N}\\p{M}\\p{S}_~.-]+[\\\\/]")
    )

    fun message(value: String): String {
        val bounded = value.take(MAX_MESSAGE_CHARS).substringBefore('\n').substringBefore('\r')
            .filterNot { it.isISOControl() }
        val sensitiveStart = sensitiveContext.mapNotNull { it.find(bounded)?.range?.first }.minOrNull()
        return if (sensitiveStart == null) bounded else
            bounded.take(sensitiveStart).trimEnd() + " [内容已隐藏]"
    }
}
