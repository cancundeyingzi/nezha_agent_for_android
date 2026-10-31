package com.nezhahq.agent.core.task

/**
 * Task type IDs are part of the Dashboard/Agent wire protocol.
 * Keep this list aligned with upstream ordering so routing changes stay reviewable.
 */
object TaskTypes {
    const val HTTP_GET = 1L
    const val ICMP_PING = 2L
    const val TCP_PING = 3L
    const val COMMAND = 4L
    const val TERMINAL_LEGACY = 5L
    const val UPGRADE = 6L
    const val KEEPALIVE = 7L
    const val TERMINAL = 8L
    const val NAT = 9L
    const val REPORT_HOST_INFO = 10L
    const val FILE_MANAGER = 11L
    const val REPORT_CONFIG = 12L
    const val APPLY_CONFIG = 13L
    const val SERVER_TRANSFER_APPLY = 14L
    const val EXEC = 15L
    const val FS_LIST = 16L
    const val FS_READ = 17L
    const val FS_WRITE = 18L
    const val FS_DELETE = 19L
    const val FS_TRANSFER = 20L

    val STREAM_TASKS: Set<Long> = setOf(TERMINAL, NAT, FILE_MANAGER)

    /**
     * Built once, like [STREAM_TASKS]. Task dispatch asks this question for every task the
     * dashboard sends, and building the set inside the function allocated it — plus a boxed Long
     * per entry — on each of those calls.
     */
    private val UNSUPPORTED_ON_ANDROID: Set<Long> = setOf(
        TERMINAL_LEGACY,
        UPGRADE,
        REPORT_HOST_INFO,
        REPORT_CONFIG,
        APPLY_CONFIG,
        SERVER_TRANSFER_APPLY,
        EXEC,
        FS_LIST,
        FS_READ,
        FS_WRITE,
        FS_DELETE,
        FS_TRANSFER
    )

    fun isKnownUnsupportedOnAndroid(type: Long): Boolean = type in UNSUPPORTED_ON_ANDROID

    fun unsupportedMessage(type: Long): String =
        "Task type $type is not supported by Android Agent yet."
}
