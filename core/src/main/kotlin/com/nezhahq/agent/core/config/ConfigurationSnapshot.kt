package com.nezhahq.agent.core.config

import com.nezhahq.agent.core.model.AutoStartState
import com.nezhahq.agent.core.model.ConnectionDraft
import com.nezhahq.agent.core.model.KeepAliveSettings
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.core.model.SimulatedDeviceConfig
import com.nezhahq.agent.core.model.SimulatorDraft

/**
 * Configuration fields and availability rendered by the editing screens.
 *
 * Initial loading waits for the storage readiness signal, then combines the repository's cached
 * values for a single UI update. This type itself never opens storage or touches the Keystore.
 */
data class ConfigurationSnapshot(
    val connection: ConnectionDraft,
    val tools: KeepAliveSettings,
    val capabilities: RemoteCapabilities,
    val autoStart: AutoStartState,
    val simulator: SimulatorDraft,
    /** Availability observed *after* the values above were read; see [loadConfigurationSnapshot]. */
    val status: StorageStatus
) {
    companion object {
        /** Mirrors the storage layer's own read default for the agent and simulator ports. */
        private const val DEFAULT_PORT = 5555

        /**
         * What to show before — or instead of — a successful read.
         *
         * Every value matches what storage returns for a missing key, which lets this constant do
         * double duty: it seeds a form that has nothing to display yet, and comparing a field
         * against it is how [resolveEditedValue] tells an untouched field from one the user has
         * already typed into. [status] is UNAVAILABLE because no read has yet proven otherwise,
         * which is also what makes this the right fallback when a read fails outright.
         */
        val DEFAULTS = ConfigurationSnapshot(
            connection = ConnectionDraft(
                server = "",
                port = DEFAULT_PORT,
                secret = "",
                uuid = "",
                useTls = true,
                rootMode = false
            ),
            tools = KeepAliveSettings(),
            capabilities = RemoteCapabilities(),
            autoStart = AutoStartState(enabled = false, promptShown = false),
            simulator = SimulatorDraft(
                server = "",
                port = DEFAULT_PORT,
                secret = "",
                useTls = true,
                threadCount = SimulatedDeviceConfig.DEFAULT_THREAD_COUNT
            ),
            status = StorageStatus.UNAVAILABLE
        )
    }
}

/**
 * Reads every value the editing screens show, and only then the resulting storage status.
 *
 * Status is sampled last so a repository that becomes unavailable while reading cannot expose
 * fallback values alongside an earlier READY status.
 */
fun ConfigRepository.loadConfigurationSnapshot(): ConfigurationSnapshot {
    val connection = loadConnectionDraft()
    val tools = loadToolSettings()
    val capabilities = loadRemoteCapabilities()
    val autoStart = loadAutoStartState()
    val simulator = loadSimulatorDraft()
    return ConfigurationSnapshot(
        connection = connection,
        tools = tools,
        capabilities = capabilities,
        autoStart = autoStart,
        simulator = simulator,
        status = storageStatus()
    )
}

/**
 * Chooses between what a screen already shows and what storage has just produced.
 *
 * A screen that renders before storage has opened shows [ConfigurationSnapshot.DEFAULTS]. When the
 * read finally lands, a field still holding its default is one nobody reached, so it takes [loaded];
 * anything else is what the user typed while waiting and has to survive.
 *
 * The rule cannot see an edit that put a field back to its default value. It is the second line of
 * defence behind disabling the inputs for the duration of the load, not a replacement for it.
 */
fun <T> resolveEditedValue(current: T, loaded: T, untouched: T): T =
    if (current == untouched) loaded else current
