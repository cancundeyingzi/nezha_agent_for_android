package com.nezhahq.agent.core.config

import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.AutoStartState
import com.nezhahq.agent.core.model.ConnectionDraft
import com.nezhahq.agent.core.model.KeepAliveSettings
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.core.model.RemoteCapability
import com.nezhahq.agent.core.model.SimulatedDeviceConfig
import com.nezhahq.agent.core.model.SimulatorDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationSnapshotTest {

    @Test
    fun snapshotCarriesEveryStoredValueTheFormRenders() {
        val repository = RecordingConfigRepository()

        val snapshot = repository.loadConfigurationSnapshot()

        assertEquals(repository.connection, snapshot.connection)
        assertEquals(repository.tools, snapshot.tools)
        assertEquals(repository.capabilities, snapshot.capabilities)
        assertEquals(repository.autoStart, snapshot.autoStart)
        assertEquals(repository.simulator, snapshot.simulator)
        assertEquals(StorageStatus.READY, snapshot.status)
    }

    @Test
    fun statusIsSampledOnlyAfterEveryValueHasBeenRead() {
        val repository = RecordingConfigRepository()

        repository.loadConfigurationSnapshot()

        assertEquals("status", repository.calls.last())
    }

    @Test
    fun readThatDegradesTheStoreIsReflectedInTheSnapshotStatus() {
        // 这正是「status 最后读」要防的事：任何一次取值失败都会把存储标记为不可用，先读
        // status 会给一份其实全是默认值的快照盖上 READY。
        val repository = RecordingConfigRepository(degradesAfter = "simulator")

        val snapshot = repository.loadConfigurationSnapshot()

        assertEquals(StorageStatus.UNAVAILABLE, snapshot.status)
    }

    @Test
    fun defaultsMatchWhatStorageReturnsForMissingKeys() {
        val defaults = ConfigurationSnapshot.DEFAULTS

        assertEquals("", defaults.connection.server)
        assertEquals(5555, defaults.connection.port)
        assertEquals("", defaults.connection.secret)
        assertEquals("", defaults.connection.uuid)
        assertTrue(defaults.connection.useTls)
        assertFalse(defaults.connection.rootMode)
        assertEquals(KeepAliveSettings(), defaults.tools)
        assertEquals(RemoteCapabilities(), defaults.capabilities)
        assertEquals(AutoStartState(enabled = false, promptShown = false), defaults.autoStart)
        assertEquals(5555, defaults.simulator.port)
        assertTrue(defaults.simulator.useTls)
        assertEquals(
            SimulatedDeviceConfig.DEFAULT_THREAD_COUNT,
            defaults.simulator.threadCount
        )
    }

    @Test
    fun defaultsReportStorageAsUnavailable() {
        // A read that fails falls back to this whole constant, so its status has to be the honest
        // "nothing has been read" answer rather than an optimistic READY.
        assertEquals(StorageStatus.UNAVAILABLE, ConfigurationSnapshot.DEFAULTS.status)
    }

    @Test
    fun untouchedFieldTakesTheLoadedValue() {
        assertEquals(
            "panel.example",
            resolveEditedValue(current = "", loaded = "panel.example", untouched = "")
        )
    }

    @Test
    fun valueTypedWhileLoadingSurvivesTheLoadThatLandsUnderIt() {
        assertEquals(
            "typed.example",
            resolveEditedValue(
                current = "typed.example",
                loaded = "stored.example",
                untouched = ""
            )
        )
    }

    @Test
    fun editThatRestoresTheDefaultCannotBeDistinguishedAndLoses() {
        // 已知局限，写成测试是为了让它保持「已知」：改回默认值与从未改过在值上是同一件事。
        // 这就是加载期同时还要禁用输入控件的原因。
        assertFalse(resolveEditedValue(current = true, loaded = false, untouched = true))
    }

    /**
     * Records read order and can turn the store unusable partway through, the way a failing read
     * does inside the real coordinator.
     */
    private class RecordingConfigRepository(
        private val degradesAfter: String? = null
    ) : ConfigRepository {
        val calls = mutableListOf<String>()

        val connection = ConnectionDraft(
            server = "panel.example",
            port = 8443,
            secret = "secret",
            uuid = "uuid",
            useTls = false,
            rootMode = true
        )
        val tools = KeepAliveSettings(audio = true, overlay = true, vpn = false)
        val capabilities = RemoteCapabilities(
            shellEnabled = true,
            fileManagerEnabled = false,
            natEnabled = true
        )
        val autoStart = AutoStartState(enabled = true, promptShown = true)
        val simulator = SimulatorDraft(
            server = "sim.example",
            port = 9443,
            secret = "sim-secret",
            useTls = false,
            threadCount = 7
        )

        private var status = StorageStatus.READY

        override fun storageStatus(): StorageStatus = record("status") { status }

        override fun loadConnectionDraft(): ConnectionDraft = record("connection") { connection }

        override fun loadToolSettings(): KeepAliveSettings = record("tools") { tools }

        override fun loadRemoteCapabilities(): RemoteCapabilities =
            record("capabilities") { capabilities }

        override fun loadAutoStartState(): AutoStartState = record("autoStart") { autoStart }

        override fun loadSimulatorDraft(): SimulatorDraft = record("simulator") { simulator }

        private fun <T> record(name: String, value: () -> T): T {
            calls += name
            val recorded = value()
            if (name == degradesAfter) status = StorageStatus.UNAVAILABLE
            return recorded
        }

        override fun resetStorage(): Boolean = unsupported()

        override fun loadAgentConfig(): Result<AgentConfig> = unsupported()

        override fun hasCompleteConnection(): Boolean = unsupported()

        override fun saveConnection(draft: ConnectionDraft): Result<Unit> = unsupported()

        override fun saveToolSettings(settings: KeepAliveSettings): Result<Unit> = unsupported()

        override fun saveRemoteCapability(
            capability: RemoteCapability,
            enabled: Boolean
        ): Result<Unit> = unsupported()

        override fun saveAutoStart(enabled: Boolean): Result<Unit> = unsupported()

        override fun saveAutoStartPromptResult(enabled: Boolean): Result<Unit> = unsupported()

        override fun saveSimulator(draft: SimulatorDraft): Result<Unit> = unsupported()

        private fun <T> unsupported(): T =
            throw UnsupportedOperationException("Not exercised by these tests.")
    }
}
