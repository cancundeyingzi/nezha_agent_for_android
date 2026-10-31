package com.nezhahq.agent.mcp.transport

import com.nezhahq.agent.core.mcp.McpBusinessError
import com.nezhahq.agent.core.mcp.McpErrorCode
import kotlinx.coroutines.suspendCancellableCoroutine
import proto.Nezha.TaskResult
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resumeWithException

enum class HelperBackend { APPLICATION, ROOT, SHIZUKU }

/** Only program-generated nonce/lifetime and trusted local installation paths reach launch argv. */
data class HelperLaunchSpec(val nonce: ByteArray, val lifetimeMillis: Int)

/**
 * DEV06 implements this boundary for an explicitly selected high privilege backend. No fallback.
 * launch must be cancellable; a process obtained after cancellation must be revoked by its owner.
 */
interface HelperLauncher {
    val backend: HelperBackend
    suspend fun launch(spec: HelperLaunchSpec): HelperProcessLease
}

/**
 * The lease owns all three pipes and the launch/authorization registration. revoke/close are
 * idempotent, bounded and must terminate the live verified group, then close pipes/revoke callbacks.
 * A normally exited helper already cleaned its group; do not signal a cached, reusable integer PID.
 * Merely destroying an su/Binder wrapper does not implement this contract. Before verification,
 * no user request has been sent and only the helper itself may exist.
 */
interface HelperProcessLease : AutoCloseable {
    val backend: HelperBackend
    val expectedUid: Int
    val stdout: InputStream
    val stdin: OutputStream
    val stderr: InputStream
    fun acceptIdentity(hello: HelperProtocol.Hello)
    override fun close()
}

class HelperFailure(val error: McpBusinessError) : Exception(error.message)

/** The caller owns this through final send or discard, including queued results. */
class OwnedMcpResult(val result: TaskResult, reservation: AutoCloseable) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val reservation = reservation
    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { reservation.close() }
    }
}

/** Monotonic deadline. Never use wall-clock time or Process.pid()/API-26 Process methods. */
class HelperDeadline private constructor(
    private val startedNanos: Long,
    private val durationMillis: Long,
    private val nanoTime: () -> Long
) {
    val elapsedMillis: Long get() = ((nanoTime() - startedNanos).coerceAtLeast(0) / 1_000_000)
    val remainingMillis: Long get() = (durationMillis - elapsedMillis).coerceAtLeast(0)

    fun limitedTo(millis: Long): HelperDeadline =
        afterMillis(minOf(remainingMillis, millis), nanoTime)

    companion object {
        fun afterMillis(millis: Long, nanoTime: () -> Long = System::nanoTime): HelperDeadline {
            require(millis in 0..HelperProtocol.MAX_LIFETIME_MILLIS.toLong())
            return HelperDeadline(nanoTime(), millis, nanoTime)
        }
    }
}

/**
 * Cancellable bridge for native pipe IO. Cancellation releases the coroutine immediately; its
 * enclosing owner must close the process lease to unblock native IO. Both workers and queue are
 * fixed-size, so even a broken remote pipe cannot create an unbounded thread/queue backlog.
 */
internal class HelperBlockingPool(name: String, workers: Int) {
    private val sequence = AtomicInteger()
    private val executor = ThreadPoolExecutor(
        workers, workers, 30, TimeUnit.SECONDS, ArrayBlockingQueue(workers),
        ThreadFactory { runnable -> Thread(runnable, "$name-${sequence.incrementAndGet()}").apply { isDaemon = true } }
    ).apply { allowCoreThreadTimeOut(true) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    suspend fun <T> run(abandoned: (T) -> Unit = {}, block: () -> T): T =
        suspendCancellableCoroutine { continuation ->
            try {
                executor.execute {
                    if (!continuation.isActive) return@execute
                    try {
                        val result = block()
                        continuation.resume(result) { runCatching { abandoned(result) } }
                    } catch (failure: Throwable) {
                        continuation.resumeWithException(failure)
                    }
                }
            } catch (_: RejectedExecutionException) {
                continuation.resumeWithException(busy())
            }
        }

    fun discard(block: () -> Unit) {
        try { executor.execute { try { block() } catch (_: Exception) { /* No sensitive logs. */ } } }
        catch (_: RejectedExecutionException) { throw busy() }
    }

    private fun busy() = HelperFailure(McpBusinessError(McpErrorCode.BUSY, "MCP helper IO workers are busy"))
}

internal object HelperIoPools {
    val launch = HelperBlockingPool("mcp-helper-launch", 2)
    val protocol = HelperBlockingPool("mcp-helper-protocol", 4)
    val diagnostic = HelperBlockingPool("mcp-helper-diagnostic", 4)
}
