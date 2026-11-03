package com.nezhahq.agent.core.config

/** Availability of the encrypted configuration and any remaining migration recovery work. */
enum class StorageStatus {
    READY,

    /**
     * Authoritative configuration is encrypted and usable. An unreadable old encrypted source was
     * preserved because it may contain additional values that could not be imported.
     */
    LEGACY_UNREADABLE,
    /** Encrypted configuration is usable; old preference files still need cleanup. */
    CLEANUP_PENDING,
    UNAVAILABLE;

    val isUsable: Boolean
        get() = this != UNAVAILABLE
}
