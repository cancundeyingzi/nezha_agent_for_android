package com.nezhahq.agent.mcp.privileged

/** Absence is only a null FIRST lookup. Once observed, failure/change/death is not absence. */
internal class PrivilegedServiceDiscoveryFailure : Exception()
/** Raw gateway rejected before any native process call; its maintenance slot remains unused. */
internal class PrivilegedLaunchNotStarted : Exception()

internal fun <T : Any> capturePrivilegedService(
    token: () -> T?, alive: (T) -> Boolean, uid: () -> Int,
    capture: (T, Int) -> PrivilegedService
): PrivilegedService? {
    val observed = token() ?: return null
    if (!alive(observed)) throw PrivilegedServiceDiscoveryFailure()
    val observedUid = uid()
    if (token() !== observed || !alive(observed)) throw PrivilegedServiceDiscoveryFailure()
    return capture(observed, observedUid)
}
