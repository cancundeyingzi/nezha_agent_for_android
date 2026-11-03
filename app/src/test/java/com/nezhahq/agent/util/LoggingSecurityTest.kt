package com.nezhahq.agent.util

import com.nezhahq.agent.SilentLoggerRule
import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.grpc.GrpcManagedChannelFactory
import com.nezhahq.agent.grpc.ManagedGrpcConnection
import io.grpc.Status
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LoggingSecurityTest {
    @get:Rule val logs = SilentLoggerRule()

    @Test
    fun errorAndInfoProtectEveryDefaultOutputIncludingUnstructuredUpstreamBodies() {
        val body = "unlabelled-private-upstream-body-7319"
        val failure = IOException(body, IllegalStateException("cause-secret-7319")).apply {
            addSuppressed(IllegalArgumentException("suppressed-secret-7319"))
            stackTrace = arrayOf(StackTraceElement("com.nezhahq.agent.Private", "diagnosticOnlyFrame", "/private/source-7319", 8))
        }

        Logger.e("日志安全测试：连接失败", failure)
        Logger.i("日志安全测试：回退读取", failure)

        assertDefaultOutputsExclude(body, "cause-secret-7319", "suppressed-secret-7319", "diagnosticOnlyFrame", "/private/source-7319")
        assertTrue(logs.messages.any { "连接失败" in it && "IOException" in it })
        assertTrue(logs.messages.any { "回退读取" in it && "IOException" in it })
    }

    @Test
    fun rawMessageAndToStringAreNeverEvaluatedByLoggingOrUiConversion() {
        val failure = object : RuntimeException() {
            override val message: String get() = error("raw message getter must never be called")
            override val cause: Throwable? get() = error("broken exception cause getter")
            override fun toString(): String = error("raw toString must never be called")
        }

        Logger.e("日志安全测试：未知异常", failure)
        Logger.i("日志安全测试：读取失败", failure)
        listOf("上次失败", "模拟器异常停止", "采集失败", "Shizuku 检测失败").forEach {
            assertEquals("$it（Throwable）", SafeErrorText.display(it, failure))
        }
    }

    @Test
    fun infoContextGuardCoversCredentialsAddressesPathsAndBodiesAtAllOutputs() {
        sensitiveLogContextCases("7319").forEach { (input, marker) ->
            Logger.i("日志安全测试：受限上下文 $input")
            assertDefaultOutputsExclude(marker)
            assertTrue(logs.messages.last().startsWith("日志安全测试：受限上下文"))
            assertTrue(logs.messages.last().contains("[内容已隐藏]"))
        }
    }

    @Test
    fun fixedBusinessDescriptionAndGrpcCodeSurviveEveryDefaultOutput() {
        val description = "日志安全测试：Root 或 Shizuku 指标读取失败"
        val failure = Status.PERMISSION_DENIED.withDescription("private-status-body-7319").asRuntimeException()
        Logger.e(description, failure)
        val outputs = listOf(Logger.logs.value.last().text, Logger.getLogString(), logs.messages.last())
        outputs.forEach {
            assertTrue(it.contains(description))
            assertTrue(it.contains("Error=StatusRuntimeException, Code=PERMISSION_DENIED"))
        }
        assertEquals("$description（StatusRuntimeException, Code=PERMISSION_DENIED）", SafeErrorText.display(description, failure))
        assertDefaultOutputsExclude("private-status-body-7319")
        val stageDescription = "GpuCollector: P1.5 动态扫描命中"
        Logger.i(stageDescription)
        assertEquals(stageDescription, logs.messages.last())
        assertTrue(Logger.logs.value.last().text.contains(stageDescription))
        assertTrue(Logger.getLogString().contains(stageDescription))
    }

    @Test
    fun realConnectionFailureRetainsTransportAndCodeWithoutEndpointOrStatusDescription() {
        val endpoint = "connection-private-7319.example"
        val failure = Status.UNAVAILABLE.withDescription("grpc-private-upstream-body-7319")
            .withCause(IOException("credential-7319 /data/user/0/private-7319"))
            .asRuntimeException()
        val connection = ManagedGrpcConnection(
            AgentConfig(endpoint, 8008, "connection-secret-7319", "connection-uuid-7319", useTls = true, rootMode = false),
            channelFactory = GrpcManagedChannelFactory { _, _ -> throw failure }
        )

        assertSame(failure, runCatching { connection.connect() }.exceptionOrNull())

        assertTrue(logs.messages.any { "TLS" in it && "UNAVAILABLE" in it })
        assertTrue(SafeErrorText.display("上次失败", failure).contains("UNAVAILABLE"))
        assertDefaultOutputsExclude(endpoint, "connection-secret-7319", "connection-uuid-7319",
            "grpc-private-upstream-body-7319", "credential-7319", "/data/user/0/private-7319")
    }

    @Test
    fun platformSinkFailureCannotEscapeEitherLevel() {
        val previous = Logger.platformSink
        Logger.platformSink = object : PlatformLogSink {
            override fun info(message: String) = error("broken platform")
            override fun error(message: String) = throw IOException("broken platform")
        }
        try {
            Logger.i("日志安全测试：平台失败仍继续")
            Logger.e("日志安全测试：平台失败仍继续", IOException("private-7319"))
            assertTrue(Logger.getLogString().contains("平台失败仍继续"))
        } finally { Logger.platformSink = previous }
    }

    @Test
    fun concurrentPublicationRetainsLatestSnapshotMonotonicIdsAndBoundedBuffer() {
        val executor = Executors.newFixedThreadPool(6)
        val start = CountDownLatch(1)
        try {
            val writers = (0 until 6).map { worker -> executor.submit {
                assertTrue(start.await(2, TimeUnit.SECONDS))
                repeat(120) { Logger.i("日志安全测试：并发 worker=$worker iteration=$it") }
            } }
            start.countDown()
            writers.forEach { it.get(5, TimeUnit.SECONDS) }
            val snapshot = Logger.logs.value
            assertEquals(200, snapshot.size)
            assertTrue(snapshot.zipWithNext().all { (a, b) -> a.id < b.id })
            assertEquals(snapshot.joinToString("\n") { it.text }, Logger.getLogString())
            Logger.i("日志安全测试：去重")
            val id = Logger.logs.value.last().id
            Logger.i("日志安全测试：去重")
            assertEquals(id, Logger.logs.value.last().id)
            assertTrue(Logger.logs.value.last().text.endsWith("×2"))
        } finally { start.countDown(); executor.shutdownNow() }
    }

    private fun assertDefaultOutputsExclude(vararg markers: String) {
        val outputs = listOf(Logger.logs.value.joinToString("\n") { it.text }, Logger.getLogString(), logs.messages.joinToString("\n"))
        markers.forEach { marker -> outputs.forEach { assertFalse("default output leaked $marker", marker in it) } }
    }
}

/** Reused against the UI, clipboard, platform sink and explicitly enabled diagnostic files. */
internal fun sensitiveLogContextCases(marker: String): List<Pair<String, String>> {
    val domain = "private-$marker.example"
    val privatePath = "private-$marker"
    return listOf(
        "Authorization: Bearer credential-$marker" to "credential-$marker",
        "token=token-$marker" to "token-$marker",
        "{\"access_token\":\"json-secret-$marker\"}" to "json-secret-$marker",
        "https://$domain/api?auth=$marker" to domain,
        "192.0.2.19:8008" to "192.0.2.19",
        "[2001:db8:7319::1]:8008" to "2001:db8:7319",
        domain to domain,
        "\"$domain\"" to domain,
        "'$domain'" to domain,
        "“$domain”" to domain,
        "‘$domain’" to domain,
        "「$domain」" to domain,
        "$domain." to domain,
        "$domain：8008" to domain,
        "$domain；结束" to domain,
        "$domain！" to domain,
        "private-$marker.xn--fiqs8s" to "private-$marker.xn--fiqs8s",
        "xn--bcher-kva.example" to "xn--bcher-kva.example",
        "私有-$marker.测试" to "私有-$marker.测试",
        "cafe\u0301-$marker.example" to "cafe\u0301-$marker.example",
        "private-$marker。example" to privatePath,
        "私有-$marker．测试" to "私有-$marker",
        "private-$marker｡example" to privatePath,
        "“/$privatePath”" to privatePath,
        "「/$privatePath」" to privatePath,
        "[\\$privatePath]" to privatePath,
        "/data/user/0/$privatePath/file" to privatePath,
        "C:\\Users\\$privatePath\\file" to privatePath,
        "relative/$privatePath/file" to privatePath,
        "~/$privatePath/file" to privatePath,
        "./$privatePath/file" to privatePath,
        "../$privatePath/file" to privatePath,
        "relative\\$privatePath\\file" to privatePath,
        "目录/私有-$marker/文件" to "私有-$marker",
        "📁/$privatePath" to privatePath,
        "路径=relative/$privatePath" to privatePath,
        "body={private-response-$marker}" to "private-response-$marker"
    )
}
