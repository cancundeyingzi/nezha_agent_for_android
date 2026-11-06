package com.nezhahq.agent.simulator

import com.nezhahq.agent.grpc.GrpcConnection
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.SafeErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SimulatorPhase { IDLE, STARTING, RUNNING, STOPPING }

data class SimulatorRunState(
    val phase: SimulatorPhase = SimulatorPhase.IDLE,
    val sessionId: Long? = null,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val activeThreadCount: Int = 0,
    val lastStatus: String = "未启动"
) {
    val running: Boolean get() = phase == SimulatorPhase.RUNNING
    val canStart: Boolean get() = phase == SimulatorPhase.IDLE
}

internal interface SimulatorConnectionOwner {
    val accepting: Boolean
    fun register(connection: GrpcConnection): Boolean
    fun closed(connection: GrpcConnection)
    fun closeFailed(failure: Throwable)
}

internal class SimulatorCleanupException(cause: Throwable) : IllegalStateException("模拟器连接尚未确认退出", cause)

internal data class SimulatorResetToken(val generation: Long, val sessionId: Long?)

/**
 * AppContainer owns this single slot across Activity/ViewModel replacement. A slot is released only
 * after the session Job has completed AND every registered native connection has confirmed close.
 * Neither channel construction/connect nor close/join runs while holding [lock].
 */
internal class SimulatorSessionStore(private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val lock = Any()
    private val cleanupScope = CoroutineScope(SupervisorJob() + io)
    private var nextId = 0L
    private var slot: Session? = null
    private val mutable = MutableStateFlow(SimulatorRunState())
    val state = mutable.asStateFlow()

    internal inner class Session internal constructor(val id: Long) : SimulatorConnectionOwner {
        internal var admission = true
        internal var job: Job? = null
        internal var cleanupJob: Job? = null
        internal var clearPresentationOnExit = false
        internal val cleanupMutex = Mutex()
        internal val connections = LinkedHashSet<GrpcConnection>()
        override val accepting: Boolean get() = synchronized(lock) { slot === this && admission }
        override fun register(connection: GrpcConnection): Boolean = synchronized(lock) {
            if (slot !== this || !admission) false else connections.add(connection)
        }
        override fun closed(connection: GrpcConnection) { synchronized(lock) { connections.remove(connection) } }
        override fun closeFailed(failure: Throwable) {
            synchronized(lock) {
                if (slot !== this) return
                admission = false
                mutable.value = mutable.value.copy(phase = SimulatorPhase.STOPPING,
                    lastStatus = SafeErrorText.display("模拟器连接清理未完成，请重试关闭", failure))
            }
        }
    }

    fun reserve(): Session? = synchronized(lock) {
        if (slot != null) return@synchronized null
        Session(++nextId).also {
            slot = it
            mutable.value = SimulatorRunState(SimulatorPhase.STARTING, it.id, lastStatus = "正在保存模拟器配置…")
        }
    }

    fun captureConfigurationReset(): SimulatorResetToken = synchronized(lock) {
        SimulatorResetToken(nextId, slot?.id)
    }

    /** Clears statistics after a verified reset without making an unfinished native session idle. */
    fun configurationResetApplied(token: SimulatorResetToken): Boolean = synchronized(lock) {
        val session = slot
        if (nextId != token.generation || session?.admission == true) return@synchronized false
        if (session != null && session.id != token.sessionId) return@synchronized false
        session?.clearPresentationOnExit = true
        mutable.value = mutable.value.copy(successCount = 0, failureCount = 0, activeThreadCount = 0,
            lastStatus = if (session == null) "未启动" else mutable.value.lastStatus)
        true
    }

    fun start(session: Session, threadCount: Int, run: suspend (Session) -> Unit): Boolean {
        val job = synchronized(lock) {
            if (slot !== session || !session.admission || session.job != null) return false
            cleanupScope.launch(start = CoroutineStart.LAZY) {
                try {
                    run(session)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    session.closeFailed(failure)
                    runCatching { Logger.e("Simulator: 模拟器异常停止", failure) }
                }
            }.also {
                session.job = it
                mutable.value = mutable.value.copy(phase = SimulatorPhase.RUNNING, activeThreadCount = threadCount,
                    lastStatus = "模拟器已开启，$threadCount 个并发线程正在上报随机设备...")
            }
        }
        job.invokeOnCompletion { requestStop(session.id) }
        job.start()
        return true
    }

    fun success(id: Long) = synchronized(lock) {
        if (slot?.id != id || slot?.admission != true) return@synchronized
        val count = mutable.value.successCount + 1
        mutable.value = mutable.value.copy(successCount = count, lastStatus = "上次成功：第 $count 台设备已收到状态回执")
    }

    fun failure(id: Long, failure: Throwable) = synchronized(lock) {
        if (slot?.id != id || slot?.admission != true) return@synchronized
        mutable.value = mutable.value.copy(failureCount = mutable.value.failureCount + 1,
            lastStatus = SafeErrorText.display("上次失败", failure))
    }

    /** Synchronous admission closure; asynchronous cleanup survives cancellation of the UI scope. */
    fun requestStop(id: Long) {
        val session = synchronized(lock) {
            val current = slot?.takeIf { it.id == id } ?: return
            closeAdmission(current)
            current
        }
        session.job?.cancel()
        synchronized(lock) {
            if (slot !== session || session.cleanupJob?.isActive == true) return
            session.cleanupJob = cleanupScope.launch { stopAndAwaitTermination(id) }
        }
    }

    /** A false result retains the exact connection objects for the next explicit cleanup attempt. */
    suspend fun stopAndAwaitTermination(id: Long): Boolean {
        val session = synchronized(lock) {
            val current = slot ?: return true
            if (current.id != id) return false
            closeAdmission(current)
            current
        }
        return session.cleanupMutex.withLock {
            val job = synchronized(lock) { session.job }
            job?.cancelAndJoin()
            val connections = synchronized(lock) { session.connections.toList() }
            withContext(io) {
                connections.forEach { connection ->
                    try {
                        connection.close()
                        session.closed(connection)
                    } catch (failure: Throwable) {
                        // One failed connection never prevents the remaining workers from closing.
                        session.closeFailed(failure)
                        runCatching { Logger.e("Simulator: 连接清理尚未完成", failure) }
                    }
                }
            }
            synchronized(lock) {
                if (slot !== session) return@synchronized true
                if (session.job?.isCompleted != false && session.connections.isEmpty()) {
                    slot = null
                    mutable.value = mutable.value.copy(phase = SimulatorPhase.IDLE, sessionId = null,
                        activeThreadCount = 0, lastStatus = if (session.clearPresentationOnExit) "未启动"
                        else "模拟器已停止，本次会话成功 ${mutable.value.successCount} 台，失败 ${mutable.value.failureCount} 台")
                    true
                } else false
            }
        }
    }

    private fun closeAdmission(session: Session) {
        session.admission = false
        if (mutable.value.phase != SimulatorPhase.STOPPING) {
            mutable.value = mutable.value.copy(phase = SimulatorPhase.STOPPING, lastStatus = "正在等待模拟器连接退出…")
        }
    }
}
