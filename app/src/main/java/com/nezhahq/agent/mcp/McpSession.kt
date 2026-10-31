package com.nezhahq.agent.mcp

import com.nezhahq.agent.core.mcp.McpAdmissionResult
import com.nezhahq.agent.core.mcp.McpBusinessError
import com.nezhahq.agent.core.mcp.McpErrorCode
import com.nezhahq.agent.core.mcp.McpLease
import com.nezhahq.agent.core.mcp.McpLimits
import com.nezhahq.agent.core.mcp.McpPolicy
import com.nezhahq.agent.core.mcp.McpTaskAdmission
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.mcp.transport.OwnedMcpResult
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.selectUnbiased
import kotlinx.coroutines.supervisorScope
import proto.Nezha.Task
import proto.Nezha.TaskResult

internal fun interface McpTaskBackend {
    suspend fun execute(
        task: Task,
        capabilities: RemoteCapabilities,
        reserveResult: suspend (Long) -> AutoCloseable
    ): OwnedMcpResult
}

/** All requests, children, results and waiters belong to one RequestTask registration. */
internal class McpSession(
    streamScope: CoroutineScope,
    private val capabilities: () -> RemoteCapabilities,
    private val backend: McpTaskBackend,
    internal val admission: McpTaskAdmission = McpTaskAdmission(),
    private val encoder: McpResultEncoder = McpResultEncoder()
) : AutoCloseable {
    private val lock = Any()
    private val sessionJob = SupervisorJob(streamScope.coroutineContext[Job])
    private val scope = CoroutineScope(streamScope.coroutineContext + sessionJob)
    private val reservations = McpResultReservations(admission)
    private val outgoing = Channel<OwnedMcpResult>(RESULT_CHANNEL_CAPACITY, onUndeliveredElement = { it.close() })
    private val entries = mutableSetOf<Entry>()
    private var closed = false

    /** No request queue: either acquire now or send a small, budgeted rejection in the intake job. */
    suspend fun handle(task: Task) {
        currentCoroutineContext().ensureActive()
        val family = McpPolicy.family(task.type)
        if (family == null) {
            sendOwned(small { encoder.unsupported(task.id, task.type) })
            return
        }
        McpPolicy.denial(task.type, capabilities())?.let {
            sendOwned(small { encoder.businessError(task.id, task.type, it) })
            return
        }
        val requestBytes = utf8BytesUpTo(task.data, McpLimits.MAX_REQUEST_BYTES)
        if (requestBytes > McpLimits.MAX_REQUEST_BYTES) {
            sendOwned(small {
                encoder.parseFailure(task.id, task.type, McpParseIssue(McpParseCategory.REQUEST_TOO_LARGE))
            })
            return
        }
        val intake = synchronized(lock) {
            if (closed || !sessionJob.isActive) return
            // Registration and revocation linearize under this lock, using the live grant.
            val denial = McpPolicy.denial(task.type, capabilities())
            if (denial != null) Intake.Rejected(denial)
            else when (val acquired = admission.tryAcquire(family, requestBytes)) {
                is McpAdmissionResult.Admitted -> {
                    val entry = Entry(task, acquired.lease)
                    entries += entry
                    val coordinator = scope.launch(start = CoroutineStart.LAZY) { coordinate(entry) }
                    coordinator.invokeOnCompletion {
                        entry.requestLease.close()
                        entry.result.getAndSet(null)?.close()
                        synchronized(lock) { entries.remove(entry) }
                    }
                    Intake.Accepted(coordinator)
                }
                is McpAdmissionResult.Busy -> Intake.Rejected(
                    McpBusinessError(McpErrorCode.BUSY, "MCP task capacity is busy")
                )
                McpAdmissionResult.InvalidRequestSize -> error("MCP request size was already checked")
            }
        }
        when (intake) {
            is Intake.Accepted -> intake.coordinator.start()
            is Intake.Rejected -> sendOwned(small { encoder.businessError(task.id, task.type, intake.error) })
        }
    }

    /** Cancel only disabled backend children. Never wait for result budgets or the network here. */
    suspend fun revokeDisabled(updated: RemoteCapabilities) {
        val children = synchronized(lock) {
            entries.mapNotNull { entry ->
                if (entry.published) return@mapNotNull null
                McpPolicy.denial(entry.task.type, updated)?.let { denial ->
                    if (entry.revoked == null) entry.revoked = denial
                    entry.child
                }
            }
        }
        children.forEach(Job::cancel)
        children.joinAll()
    }

    private suspend fun coordinate(entry: Entry) {
        try {
            supervisorScope {
                val child = synchronized(lock) {
                    if (closed || !sessionJob.isActive) return@supervisorScope
                    McpPolicy.denial(entry.task.type, capabilities())?.let {
                        if (entry.revoked == null) entry.revoked = it
                    }
                    if (entry.revoked != null) null else {
                        launch(start = CoroutineStart.LAZY) {
                            // Unit-returning child: a cancelled await can never orphan an Owned result.
                            try {
                                val snapshot = synchronized(lock) {
                                    if (closed || entry.revoked != null) return@launch
                                    val latest = capabilities()
                                    McpPolicy.denial(entry.task.type, latest)?.let {
                                        entry.revoked = it
                                        return@launch
                                    }
                                    latest
                                }
                                val owned = backend.execute(entry.task, snapshot, reservations::reserve)
                                entry.result.set(owned)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Throwable) {
                                // The coordinator emits the fixed, false internal failure after cleanup.
                            }
                        }.also { entry.child = it }
                    }
                }
                child?.start()
                child?.join()
            }
            currentCoroutineContext().ensureActive()
            publish(entry, entry.result.getAndSet(null))
        } finally {
            entry.result.getAndSet(null)?.close()
        }
    }

    private suspend fun publish(entry: Entry, initial: OwnedMcpResult?) {
        var owned = initial
        var denialResult = false
        try {
            while (true) {
                if (owned == null) {
                    val denial = synchronized(lock) {
                        McpPolicy.denial(entry.task.type, capabilities())?.let {
                            if (entry.revoked == null) entry.revoked = it
                        }
                        entry.revoked
                    }
                    owned = small {
                        if (denial == null) encoder.internalFailure(entry.task.id, entry.task.type)
                        else encoder.businessError(entry.task.id, entry.task.type, denial)
                    }
                    denialResult = denial != null
                }
                val decision = synchronized(lock) {
                    if (closed || !sessionJob.isActive) Publish.Drop
                    else {
                        McpPolicy.denial(entry.task.type, capabilities())?.let {
                            if (entry.revoked == null) entry.revoked = it
                        }
                        val denial = entry.revoked
                        if (denial != null && !denialResult) Publish.Deny(denial)
                        else {
                            entry.published = true
                            Publish.Send
                        }
                    }
                }
                when (decision) {
                    Publish.Drop -> return
                    is Publish.Deny -> {
                        owned.close()
                        owned = small { encoder.businessError(entry.task.id, entry.task.type, decision.error) }
                        denialResult = true
                    }
                    Publish.Send -> {
                        val sending = owned
                        owned = null
                        sendOwned(sending)
                        return
                    }
                }
            }
        } finally {
            owned?.close()
        }
    }

    private suspend fun small(encode: () -> TaskResult): OwnedMcpResult {
        val reservation = reservations.reserve(SMALL_RESULT_BYTES)
        try {
            currentCoroutineContext().ensureActive()
            val result = encode()
            check(result.serializedSize <= SMALL_RESULT_BYTES)
            currentCoroutineContext().ensureActive()
            return OwnedMcpResult(result, reservation)
        } catch (failure: Throwable) {
            reservation.close()
            throw failure
        }
    }

    private suspend fun sendOwned(owned: OwnedMcpResult) {
        var handedOff = false
        try {
            currentCoroutineContext().ensureActive()
            handedOff = true
            // From invocation onward Channel owns delivery/drop, including prompt cancellation.
            outgoing.send(owned)
        } finally {
            if (!handedOff) owned.close()
        }
    }

    /** Direct collector handoff: no merge/buffer/channelFlow between Owned and requestTask. */
    fun outgoingWith(legacy: ReceiveChannel<TaskResult>): Flow<TaskResult> = flow {
        var legacyOpen = true
        var mcpOpen = true
        while (legacyOpen || mcpOpen) {
            selectUnbiased<Unit> {
                if (legacyOpen) legacy.onReceiveCatching { received ->
                    if (received.isClosed) legacyOpen = false else emit(received.getOrThrow())
                }
                if (mcpOpen) outgoing.onReceiveCatching { received ->
                    if (received.isClosed) mcpOpen = false else {
                        val owned = received.getOrThrow()
                        try { emit(owned.result) } finally { owned.close() }
                    }
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        sessionJob.cancel()
        reservations.close()
        outgoing.cancel()
    }

    internal suspend fun join() = sessionJob.join()

    private class Entry(val task: Task, val requestLease: McpLease) {
        var revoked: McpBusinessError? = null
        var published = false
        var child: Job? = null
        val result = AtomicReference<OwnedMcpResult?>()
    }

    private sealed interface Intake {
        class Accepted(val coordinator: Job) : Intake
        class Rejected(val error: McpBusinessError) : Intake
    }

    private sealed interface Publish {
        data object Drop : Publish
        data object Send : Publish
        class Deny(val error: McpBusinessError) : Publish
    }

    private companion object {
        const val RESULT_CHANNEL_CAPACITY = 4
        const val SMALL_RESULT_BYTES = 1024L
    }
}
