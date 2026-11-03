package com.nezhahq.agent.util

import com.nezhahq.agent.core.config.ConfigRepository
import com.nezhahq.agent.core.config.StorageStatus
import com.nezhahq.agent.core.config.resolveEditedValue

/** The UI keeps an acknowledged reset pending until its resulting snapshot has actually landed. */
internal class ConfigurationRecoveryState {
    @Volatile private var confirmedReset = false

    fun confirmReset() { confirmedReset = true }

    val applyMode: SnapshotApplyMode
        get() = if (confirmedReset) SnapshotApplyMode.RELOAD_AFTER_RESET else SnapshotApplyMode.INITIAL_LOAD

    /** Called on IO. A reset whose intent could not be written still has the user's local consent. */
    fun retryStorage(repository: ConfigRepository): StorageStatus = if (confirmedReset) {
        if (repository.resetStorage()) repository.storageStatus() else StorageStatus.UNAVAILABLE
    } else {
        repository.initializeStorage(retry = true)
    }

    fun snapshotApplied(mode: SnapshotApplyMode) {
        if (mode == SnapshotApplyMode.RELOAD_AFTER_RESET) confirmedReset = false
    }
}

internal enum class SnapshotApplyMode {
    /** First load or ordinary retry preserves fields the user has edited. */
    INITIAL_LOAD,
    /** A confirmed reset replaces every field, including non-default secrets and Root grants. */
    RELOAD_AFTER_RESET;

    fun <T> resolve(current: T, loaded: T, untouched: T): T =
        if (this == INITIAL_LOAD) resolveEditedValue(current, loaded, untouched) else loaded
}
