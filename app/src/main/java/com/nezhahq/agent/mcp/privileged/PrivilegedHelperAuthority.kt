package com.nezhahq.agent.mcp.privileged

import com.nezhahq.agent.core.mcp.McpBusinessError
import com.nezhahq.agent.core.mcp.McpErrorCode
import com.nezhahq.agent.mcp.transport.HelperBackend
import com.nezhahq.agent.mcp.transport.HelperFailure
import com.nezhahq.agent.util.ShellBackend
import com.nezhahq.agent.util.ShellBackendPolicy
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeoutException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** A captured Binder, not the SDK's mutable current service or cached permission. */
internal interface PrivilegedService {
    val token: Any
    val uid: Int
    fun isAlive(): Boolean
    fun checkPermission(): Boolean
    fun launch(argv: Array<String>, environment: Array<String>, directory: String): Process
}

internal interface PrivilegedGateway {
    fun service(): PrivilegedService?
    /** Local Binder identity only; Android implementation never performs a service RPC here. */
    fun serviceToken(): Any? = service()?.token
    fun executableSu(): String?
    fun launchSu(path: String, command: String, environment: Map<String, String>, directory: String): Process
}

/**
 * Pure coordination boundary. State locks never contain Binder, Process, pipe or callback work.
 * At most one permission RPC exists, including one that ignored its 1.5-second deadline.
 */
internal class PrivilegedHelperAuthority(
    private val gateway: PrivilegedGateway,
    private val backendPolicy: ShellBackendPolicy,
    private val rpcTimeoutMillis: Long = 1_500,
    private val onLiveService: (Permit) -> Unit = {}
) : AutoCloseable {
    private val lock = Any()
    private var enabled = false
    private var epoch = 0L
    private var serviceEpoch = 0L
    private var captured: PrivilegedService? = null
    private val permits = mutableSetOf<Permit>()
    private var inFlight: Flight? = null
    private val rpc = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1)) { task ->
        Thread(task, "mcp-shizuku-permission").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }
    private val monitor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "mcp-shizuku-watch").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    init {
        monitor.scheduleWithFixedDelay({ monitorActive() }, 500, 500, TimeUnit.MILLISECONDS)
    }

    fun configure(value: Boolean) {
        configureDeferred(value).invoke()
    }

    /** Lets RootShell linearize local state and detach cleanup without callbacks under its lock. */
    fun configureDeferred(value: Boolean): () -> Unit {
        val callbacks = synchronized(lock) {
            if (enabled == value) emptyList() else {
                enabled = value
                epoch++
                invalidateLocked()
            }
        }
        return { callbacks.forEach { runCatching(it) } }
    }

    /** shutdown invalidates old work but retains the process's persisted authorization choice. */
    fun revoke() {
        revokeDeferred().invoke()
    }

    fun revokeDeferred(): () -> Unit {
        val callbacks = synchronized(lock) { epoch++; invalidateLocked() }
        return { callbacks.forEach { runCatching(it) } }
    }

    override fun close() {
        configure(false)
        monitor.shutdownNow()
        rpc.shutdownNow()
    }

    /** Process-level Binder received/dead notifications invalidate captured service permits. */
    fun serviceChanged() {
        val presence = runCatching { gateway.serviceToken() }
        val token = presence.getOrNull()
        val callbacks = synchronized(lock) {
            if (enabled && (token != null || presence.isFailure)) {
                rememberShizukuLocked(backendPolicy.epoch())
            }
            if (captured?.token === token) emptyList() else {
                captured = null
                serviceEpoch++
                revokeShizukuLocked()
            }
        }
        callbacks.forEach { runCatching(it) }
    }

    fun acquire(): Permit {
        val before = synchronized(lock) { if (!enabled) throw unavailable(); epoch to serviceEpoch }
        val localEpoch = before.first
        val policyEpoch = backendPolicy.epoch()
        val service = try { gateway.service() } catch (_: Exception) {
            synchronized(lock) {
                if (enabled && epoch == localEpoch) rememberShizukuLocked(policyEpoch)
            }
            throw unavailable()
        }
        replaceService(service, localEpoch, before.second)
        // Only trusted application PATH is inspected, before launch clears its environment.
        val suPath = gateway.executableSu()
        val selected = backendPolicy.select(
            shizukuAuthorized = service != null, shizukuRunning = service != null,
            suInstalled = suPath != null, expectedEpoch = policyEpoch
        ) ?: throw unavailable()
        val permit = synchronized(lock) {
            if (!enabled || epoch != localEpoch) throw unavailable()
            val backend = if (selected == ShellBackend.SHIZUKU) HelperBackend.SHIZUKU else HelperBackend.ROOT
            if (backend == HelperBackend.SHIZUKU && (service == null || service.uid !in setOf(0, 2000))) {
                throw unavailable()
            }
            if (backend == HelperBackend.ROOT && suPath == null) throw unavailable()
            Permit(this, epoch, serviceEpoch, policyEpoch, backend, service, suPath).also { permits += it }
        }
        try { permit.requireLive(); return permit }
        catch (failure: Throwable) { permit.close(); throw failure }
    }

    /** Pure policy state only. Lock order is authority -> policy; no reverse nested acquisition. */
    private fun rememberShizukuLocked(policyEpoch: Long) {
        backendPolicy.select(false, true, false, expectedEpoch = policyEpoch)
    }

    private fun replaceService(next: PrivilegedService?, localEpoch: Long, observedServiceEpoch: Long) {
        val callbacks = synchronized(lock) {
            if (!enabled || epoch != localEpoch ||
                (serviceEpoch != observedServiceEpoch && captured?.token !== next?.token)) throw unavailable()
            if (captured?.token === next?.token) return
            captured = next
            serviceEpoch++
            revokeShizukuLocked()
        }
        callbacks.forEach { runCatching(it) }
    }

    private fun requireLive(permit: Permit) {
        permit.requireFast()
        if (permit.backend == HelperBackend.ROOT) { onLiveService(permit); return }
        val flight = requestCheck(permit) ?: throw unavailable()
        val granted = try { flight.result.get(rpcTimeoutMillis, TimeUnit.MILLISECONDS) }
            catch (_: Exception) { rejectFlight(flight); false }
        if (!granted) throw unavailable()
        permit.requireFast()
        onLiveService(permit)
    }

    private fun requestCheck(permit: Permit): Flight? {
        val flight: Flight
        val created: Boolean
        synchronized(lock) {
            if (!validLocked(permit)) return null
            val existing = inFlight
            if (existing != null) {
                // A hung old Binder must not generate another worker or authorize a new epoch.
                return if (existing.epoch == permit.epoch && existing.serviceEpoch == permit.serviceEpoch &&
                    existing.service.token === permit.service?.token) existing else null
            }
            flight = Flight(permit.epoch, permit.serviceEpoch, permit.service ?: return null)
            inFlight = flight
            created = true
        }
        if (created) {
            try {
                rpc.execute {
                    val granted = runCatching { flight.service.isAlive() && flight.service.checkPermission() }.getOrDefault(false)
                    finishCheck(flight, granted)
                }
                monitor.schedule({ if (!flight.result.isDone) rejectFlight(flight) }, rpcTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: Exception) { rejectFlight(flight) }
        }
        return flight
    }

    private fun finishCheck(flight: Flight, granted: Boolean) {
        val callbacks = synchronized(lock) {
            if (inFlight === flight) inFlight = null
            val current = enabled && epoch == flight.epoch && serviceEpoch == flight.serviceEpoch &&
                captured?.token === flight.service.token
            if (!current || flight.result.isDone) {
                flight.result.complete(false)
                emptyList()
            } else if (!granted) {
                serviceEpoch++
                val revoked = revokeShizukuLocked()
                // Wake waiters only after the fast write gate is already closed. Callbacks stay
                // outside the lock and may complete after requireLive reports the denial.
                flight.result.complete(false)
                revoked
            } else {
                flight.result.complete(true)
                emptyList()
            }
        }
        callbacks.forEach { runCatching(it) }
    }

    private fun rejectFlight(flight: Flight) {
        val callbacks = synchronized(lock) {
            if (flight.result.isDone) return
            val revoked = if (epoch != flight.epoch || serviceEpoch != flight.serviceEpoch) emptyList() else {
                serviceEpoch++
                revokeShizukuLocked()
            }
            flight.result.complete(false)
            revoked
        }
        callbacks.forEach { runCatching(it) }
    }

    private fun monitorActive() {
        val active = synchronized(lock) { permits.firstOrNull { it.backend == HelperBackend.SHIZUKU && !it.revoked.get() } }
        if (active != null) requestCheck(active)
    }

    private fun validLocked(permit: Permit): Boolean = enabled && epoch == permit.epoch && !permit.revoked.get() &&
        (permit.backend != HelperBackend.SHIZUKU || (serviceEpoch == permit.serviceEpoch && captured?.token === permit.service?.token))

    private fun invalidateLocked(): List<() -> Unit> = permits.toList().flatMap { revokeLocked(it) }
    private fun revokeShizukuLocked(): List<() -> Unit> = permits.toList().filter {
        it.backend == HelperBackend.SHIZUKU
    }.flatMap { revokeLocked(it) }

    private fun revokeLocked(permit: Permit): List<() -> Unit> {
        permit.revoked.set(true)
        permits.remove(permit)
        return permit.callbacks.toList().also { permit.callbacks.clear() }
    }

    internal class Permit internal constructor(
        private val owner: PrivilegedHelperAuthority,
        internal val epoch: Long,
        internal val serviceEpoch: Long,
        internal val policyEpoch: Long,
        val backend: HelperBackend,
        val service: PrivilegedService?,
        val suPath: String?
    ) : AutoCloseable {
        internal val revoked = AtomicBoolean(false)
        internal val callbacks = mutableSetOf<() -> Unit>()
        val expectedUid: Int = if (backend == HelperBackend.ROOT) 0 else service!!.uid
        fun requireFast() { if (revoked.get()) throw unavailable() }
        fun requireLive() = owner.requireLive(this)
        fun requireLaunch() {
            requireLive()
            if (backend == HelperBackend.ROOT && !owner.backendPolicy.suAllowed(policyEpoch)) throw unavailable()
        }
        /** Only the fixed maintenance program uses this; the caller owns native launch cleanup. */
        fun launchProbe(
            program: TrustedHelperProgram, identity: com.nezhahq.agent.mcp.transport.HelperProtocol.Hello?,
            nativeCall: () -> Unit
        ): Process {
            requireFast()
            val argv = program.probe(identity, expectedUid)
            nativeCall()
            return if (backend == HelperBackend.SHIZUKU) {
                service!!.launch(argv, program.environment.entries.map { "${it.key}=${it.value}" }.toTypedArray(),
                    TrustedHelperProgram.DIRECTORY)
            } else owner.gateway.launchSu(suPath!!, argv.joinToString(" ", transform = TrustedHelperProgram::quote),
                program.environment, TrustedHelperProgram.DIRECTORY)
        }
        fun register(callback: () -> Unit): AutoCloseable {
            val accepted = synchronized(owner.lock) {
                if (!owner.validLocked(this)) false else { callbacks += callback; true }
            }
            if (!accepted) { runCatching(callback); throw unavailable() }
            return AutoCloseable { synchronized(owner.lock) { callbacks -= callback } }
        }
        fun rejectRoot() {
            // An old launch failure cannot suppress su in the next enable epoch.
            synchronized(owner.lock) {
                if (backend == HelperBackend.ROOT && owner.validLocked(this)) {
                    owner.backendPolicy.rejectSu(policyEpoch)
                }
            }
        }
        fun fork(): Permit = synchronized(owner.lock) {
            if (!owner.validLocked(this)) throw unavailable()
            Permit(owner, epoch, serviceEpoch, policyEpoch, backend, service, suPath).also { owner.permits += it }
        }
        override fun close() {
            val callbacks = synchronized(owner.lock) { owner.revokeLocked(this) }
            callbacks.forEach { runCatching(it) }
        }
    }

    private class Flight(val epoch: Long, val serviceEpoch: Long, val service: PrivilegedService) {
        val result = PermissionResult()
    }

    /** CompletableFuture is API 24; this deliberately uses only the API-23 Java surface. */
    private class PermissionResult {
        private val value = AtomicReference<Boolean?>(null)
        private val done = CountDownLatch(1)
        val isDone get() = value.get() != null
        fun complete(granted: Boolean) {
            if (value.compareAndSet(null, granted)) done.countDown()
        }
        fun get(timeout: Long, unit: TimeUnit): Boolean {
            if (!done.await(timeout, unit)) throw TimeoutException()
            return value.get() == true
        }
    }

    companion object {
        fun unavailable() = HelperFailure(McpBusinessError(
            McpErrorCode.CAPABILITY_UNAVAILABLE, "Selected privileged MCP backend is not currently authorized"
        ))
    }
}
