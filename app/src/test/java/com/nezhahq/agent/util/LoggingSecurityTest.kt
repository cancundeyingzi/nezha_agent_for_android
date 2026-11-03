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
        val inputs = listOf(
            "Authorization: Bearer credential-7319" to "credential-7319",
            "token=token-7319" to "token-7319",
            "{\"access_token\":\"json-secret-7319\"}" to "json-secret-7319",
            "https://private-7319.example/api?auth=7319" to "private-7319.example",
            "192.0.2.19:8008" to "192.0.2.19",
            "[2001:db8:7319::1]:8008" to "2001:db8:7319",
            "private-7319.example" to "private-7319.example",
            "/data/user/0/private-7319/file" to "private-7319",
            "C:\\Users\\private-7319\\file" to "private-7319",
            "路径=relative/private-7319" to "private-7319",
            "body={private-response-7319}" to "private-response-7319"
        )
        inputs.forEach { (input, marker) ->
            Logger.i("日志安全测试：受限上下文 $input")
            assertDefaultOutputsExclude(marker)
        }
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
                repeat(120) { Logger.i("日志安全测试：并发 $worker/$it") }
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
