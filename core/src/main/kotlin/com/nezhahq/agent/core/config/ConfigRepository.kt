package com.nezhahq.agent.core.config

import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.AutoStartState
import com.nezhahq.agent.core.model.ConnectionDraft
import com.nezhahq.agent.core.model.KeepAliveSettings
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.core.model.RemoteCapability
import com.nezhahq.agent.core.model.SimulatorDraft

/**
 * The single way in and out of persisted configuration.
 *
 * Both the editing UI and the service go through here, so validation and write ordering have one
 * home. Reads come in two shapes: [loadAgentConfig] yields a validated snapshot for the runtime,
 * while the `load*Draft` calls yield raw values the UI can render even when nothing is filled in.
 */
interface ConfigRepository {
    /** Explicit blocking IO boundary; ordinary reads only access an already verified snapshot. */
    fun initializeStorage(retry: Boolean = false): StorageStatus = storageStatus()

    fun storageStatus(): StorageStatus

    /** Clears every stored value and recreates a verified-empty store. */
    fun resetStorage(): Boolean

    /** Fails when the stored connection is incomplete; the runtime cannot use a partial one. */
    fun loadAgentConfig(): Result<AgentConfig>

    /** True when a stored connection is complete enough to start the agent unattended. */
    fun hasCompleteConnection(): Boolean

    fun loadConnectionDraft(): ConnectionDraft

    fun loadToolSettings(): KeepAliveSettings

    fun loadRemoteCapabilities(): RemoteCapabilities

    fun loadAutoStartState(): AutoStartState

    fun loadSimulatorDraft(): SimulatorDraft

    /** Writes every connection field, including the root-mode grant, in one commit. */
    fun saveConnection(draft: ConnectionDraft): Result<Unit>

    fun saveToolSettings(settings: KeepAliveSettings): Result<Unit>

    fun saveRemoteCapability(capability: RemoteCapability, enabled: Boolean): Result<Unit>

    fun saveAutoStart(enabled: Boolean): Result<Unit>

    /** Records the first-run prompt answer and marks the prompt as shown. */
    fun saveAutoStartPromptResult(enabled: Boolean): Result<Unit>

    fun saveSimulator(draft: SimulatorDraft): Result<Unit>
}
