package com.nezhahq.agent.mcp.transport

import com.nezhahq.agent.core.mcp.McpBusinessError
import com.nezhahq.agent.core.mcp.McpErrorCode
import com.nezhahq.agent.core.mcp.McpLimits
import com.nezhahq.agent.mcp.exec.ExecOutput
import com.nezhahq.agent.mcp.exec.ExecOutputCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import proto.Nezha.TaskResult
import java.util.concurrent.Semaphore

data class HelperCall(val id: Long, val type: Long, val data: String, val elapsedBeforeLaunchMillis: Long = 0)

sealed interface HelperExchangeResult {
    data class Completed(val owned: OwnedMcpResult) : HelperExchangeResult
    /** Raw output remains bounded; reserve a result lease BEFORE snapshot/encoding on this path. */
    data class Failed(val error: McpBusinessError?, val timedOut: Boolean, val output: ExecOutputCapture?) : HelperExchangeResult
}

/**
 * Shared Exec/Fs transport. Parent has one protocol IO operation at a time and a separate bounded
 * stderr drainer. The mandatory reservation callback runs after RESULT's validated length header,
 * before its payload allocation/read/parse. Waiting consumes no IO worker or unbounded queue.
 */
class McpHelperTransport {
    private val sessions = Semaphore(4)

    suspend fun exchange(
        launcher: HelperLauncher,
        call: HelperCall,
        deadline: HelperDeadline,
        outputLimit: Int = 0,
        reserveResult: suspend (serializedBytes: Long) -> AutoCloseable
    ): HelperExchangeResult {
        if (!sessions.tryAcquire()) return HelperExchangeResult.Failed(
            McpBusinessError(McpErrorCode.BUSY, "MCP helper task limit reached"), false, null
        )
        val capture = if (outputLimit == 0) null else ExecOutputCapture(outputLimit)
        var lease: HelperProcessLease? = null
        var reservation: AutoCloseable? = null
        var verified = false
        var terminalStarted = false
        try {
            val nonce = HelperProtocol.nonce()
            val ownedProcess = within(deadline) {
                launcher.launch(HelperLaunchSpec(nonce, deadline.remainingMillis.toInt().coerceAtLeast(1)))
                    // Register before crossing withTimeout's cancellable return boundary. A
                    // successful launch can otherwise be discarded by timeout before assignment.
                    .also { lease = it }
            }
            if (ownedProcess.backend != launcher.backend || ownedProcess.expectedUid < 0) throw capabilityFailure()
            HelperIoPools.diagnostic.discard {
                // Startup diagnostics can contain platform details. Retain/log nothing; bound total
                // work as well as memory, then close the diagnostic pipe independently of stdout.
                ownedProcess.stderr.use { input ->
                    val buffer = ByteArray(1024)
                    var remaining = 64 * 1024
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (count <= 0) break
                        remaining -= count
                    }
                }
            }
            val reader = HelperProtocol.Reader(ownedProcess.stdout)
            val writer = HelperProtocol.Writer(ownedProcess.stdin)
            val hello = within(deadline) {
                HelperIoPools.protocol.run {
                    val header = reader.header(setOf(HelperProtocol.Kind.HELLO, HelperProtocol.Kind.UNAVAILABLE))
                    val bytes = reader.payload(header)
                    if (header.kind != HelperProtocol.Kind.HELLO) throw capabilityFailure()
                    HelperProtocol.decodeHello(bytes)
                }
            }
            if (!hello.verified(nonce, ownedProcess.expectedUid)) throw capabilityFailure()
            ownedProcess.acceptIdentity(hello)
            verified = true
            within(deadline) {
                HelperIoPools.protocol.run {
                    val remaining = deadline.remainingMillis
                    if (remaining == 0L) throw DeadlineExceeded()
                    writer.frame(HelperProtocol.Kind.REQUEST, HelperProtocol.encodeRequest(HelperProtocol.Request(
                        call.id, call.type, remaining.toInt(),
                        (call.elapsedBeforeLaunchMillis + deadline.elapsedMillis).coerceAtMost(
                            HelperProtocol.MAX_LIFETIME_MILLIS.toLong()
                        ).toInt(), call.data
                    )))
                }
            }
            val allowed = if (capture == null) setOf(HelperProtocol.Kind.RESULT) else setOf(
                HelperProtocol.Kind.STDOUT, HelperProtocol.Kind.STDERR,
                HelperProtocol.Kind.TRUNCATED, HelperProtocol.Kind.RESULT
            )
            while (true) {
                val header = within(deadline) { HelperIoPools.protocol.run { reader.header(allowed) } }
                if (header.kind == HelperProtocol.Kind.RESULT) {
                    // No read/ByteArray(header.length) has occurred yet. Callers retain this lease
                    // through their final send/drop, even after this helper has been destroyed.
                    terminalStarted = true
                    val resultReservation = try { reserveResult(header.length.toLong()) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { throw ReservationRefused(failure) }
                    reservation = resultReservation
                    val payload = within(HelperDeadline.afterMillis(RESULT_TRANSFER_MILLIS)) {
                        HelperIoPools.protocol.run { reader.payload(header) }
                    }
                    currentCoroutineContext().ensureActive()
                    val result = TaskResult.parseFrom(payload)
                    if (result.id != call.id || result.type != call.type ||
                        result.serializedSize != header.length || result.serializedSize > McpLimits.MAX_TASK_RESULT_BYTES) {
                        throw ProtocolFailure()
                    }
                    currentCoroutineContext().ensureActive()
                    val owned = OwnedMcpResult(result, resultReservation)
                    reservation = null
                    return HelperExchangeResult.Completed(owned)
                }
                val bytes = within(deadline) { HelperIoPools.protocol.run { reader.payload(header) } }
                when (header.kind) {
                    HelperProtocol.Kind.STDOUT -> capture!!.accept(ExecOutput.Bytes(false, bytes))
                    HelperProtocol.Kind.STDERR -> capture!!.accept(ExecOutput.Bytes(true, bytes))
                    HelperProtocol.Kind.TRUNCATED -> when (bytes[0].toInt()) {
                        1 -> capture!!.accept(ExecOutput.Truncated(false))
                        2 -> capture!!.accept(ExecOutput.Truncated(true))
                        else -> throw ProtocolFailure()
                    }
                    else -> throw ProtocolFailure()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ReservationRefused) {
            // Budget policy belongs to the caller. Do not retry reservation or turn its refusal
            // into a second, larger allocation attempt.
            throw failure.refusal
        } catch (_: DeadlineExceeded) {
            return if (terminalStarted) HelperExchangeResult.Failed(
                McpBusinessError(McpErrorCode.INTERNAL, "MCP helper result transfer failed"), false, capture
            ) else HelperExchangeResult.Failed(null, true, capture)
        } catch (failure: HelperFailure) {
            return HelperExchangeResult.Failed(failure.error, false, capture)
        } catch (_: Exception) {
            return HelperExchangeResult.Failed(
                if (verified) McpBusinessError(McpErrorCode.INTERNAL, "MCP helper transport failed")
                    else capabilityFailure().error,
                false, capture
            )
        } finally {
            // Preserve cancellation even if a remote lease/reservation has broken cleanup.
            runCatching { reservation?.close() }
            runCatching { lease?.close() }
            sessions.release()
        }
    }

    private suspend fun <T : Any> within(deadline: HelperDeadline, block: suspend () -> T): T {
        val remaining = deadline.remainingMillis
        if (remaining == 0L) throw DeadlineExceeded()
        return withTimeoutOrNull(remaining) { block() } ?: throw DeadlineExceeded()
    }

    private fun capabilityFailure() = HelperFailure(McpBusinessError(
        McpErrorCode.CAPABILITY_UNAVAILABLE, "Selected MCP helper cannot establish a verified isolated session"
    ))

    private class DeadlineExceeded : Exception()
    private class ReservationRefused(val refusal: Exception) : Exception()

    companion object { private const val RESULT_TRANSFER_MILLIS = 2_000L }
}
