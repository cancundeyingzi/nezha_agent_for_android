package com.nezhahq.agent.ui.main

import com.nezhahq.agent.core.model.SimulatedDeviceConfig
import com.nezhahq.agent.core.model.SimulatorDraft
import com.nezhahq.agent.simulator.GrpcSimulatedDeviceReporter
import com.nezhahq.agent.simulator.SimulatedDeviceLoop
import com.nezhahq.agent.simulator.SimulatorConnectionOwner
import com.nezhahq.agent.simulator.SimulatorSessionStore
import com.nezhahq.agent.simulator.SimulatorResetToken
import com.nezhahq.agent.ui.UiEvents
import com.nezhahq.agent.util.Logger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

internal class SimulatorController(
    private val configuration: UiConfigurationCoordinator,
    private val sessions: SimulatorSessionStore,
    private val events: UiEvents,
    private val loopFactory: (SimulatorConnectionOwner) -> SimulatedDeviceLoop = {
        SimulatedDeviceLoop(GrpcSimulatedDeviceReporter(it))
    }
) {
    val state = sessions.state
    private var ownedSessionId: Long? = null
    private var cleared = false

    fun serverChanged(value: String) = edit { it.copy(server = value) }
    fun portChanged(value: String) = edit { it.copy(port = value) }
    fun secretChanged(value: String) = edit { it.copy(secret = value) }
    fun threadCountChanged(value: String) = edit { it.copy(threadCount = value) }
    fun tlsChanged(value: Boolean) = edit { it.copy(useTls = value) }

    fun start() {
        if (cleared) return
        if (!configuration.requireStorage("启动模拟器")) return
        val fields = configuration.state.value.simulator
        val server = fields.server.trim()
        val secret = fields.secret.trim()
        SimulatedDeviceConfig.validationError(server, fields.port, secret, fields.threadCount)?.let { events.send(it); return }
        // Reserve BEFORE asynchronous persistence, so Stop can invalidate this exact startup.
        val session = sessions.reserve() ?: run { events.send("模拟器仍在运行或等待连接退出，请先关闭后重试"); return }
        ownedSessionId = session.id
        val config = SimulatedDeviceConfig(server, fields.port.trim().toInt(), secret, fields.useTls, fields.threadCount.trim().toInt())
        configuration.saveSimulator(SimulatorDraft(config.server, config.port, config.secret, config.useTls, config.threadCount),
            onSuccess = {
                if (sessions.start(session, config.threadCount) { owner ->
                    currentCoroutineContext().ensureActive()
                    val context = currentCoroutineContext()
                    loopFactory(owner).run(config,
                        shouldContinue = { context.isActive && owner.accepting },
                        onSuccess = { sessions.success(owner.id) },
                        onFailure = { failure -> Logger.e("Simulator: 模拟设备上报失败", failure); sessions.failure(owner.id, failure) }
                    )
                }) events.send("娱乐模拟设备已开启")
            }, onFailure = { sessions.requestStop(session.id) }
        )
    }

    /** The click binds the session it displayed. A queued old click cannot select a newer slot. */
    fun requestStop(expectedSessionId: Long?) {
        if (cleared) return
        if (expectedSessionId == null) { events.send("娱乐模拟设备未开启"); return }
        sessions.requestStop(expectedSessionId)
    }

    suspend fun stopAndAwaitTermination(expectedSessionId: Long?): Boolean {
        if (cleared) return false
        return expectedSessionId?.let { sessions.stopAndAwaitTermination(it) } ?: state.value.canStart
    }

    fun captureConfigurationReset(): SimulatorResetToken = sessions.captureConfigurationReset()
    fun configurationResetApplied(token: SimulatorResetToken) { sessions.configurationResetApplied(token) }

    /** Destruction requests durable cleanup; it does not claim the channels have already exited. */
    fun onCleared() {
        cleared = true
        ownedSessionId?.let(sessions::requestStop)
    }

    private fun edit(transform: (SimulatorFields) -> SimulatorFields) {
        if (!cleared) configuration.editSimulator(transform)
    }
}
