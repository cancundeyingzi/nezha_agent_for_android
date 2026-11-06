package com.nezhahq.agent.device

import android.app.Application
import android.app.Activity
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import com.nezhahq.agent.AppContainer
import com.nezhahq.agent.NezhaAgentApplication
import com.nezhahq.agent.core.config.ConfigRepository
import com.nezhahq.agent.core.config.StorageStatus
import com.nezhahq.agent.core.model.AgentConfig
import com.nezhahq.agent.core.model.AutoStartState
import com.nezhahq.agent.core.model.ConnectionDraft
import com.nezhahq.agent.core.model.KeepAliveSettings
import com.nezhahq.agent.core.model.RemoteCapabilities
import com.nezhahq.agent.core.model.RemoteCapability
import com.nezhahq.agent.core.model.SimulatorDraft
import com.nezhahq.agent.grpc.ConnectionStateHolder
import com.nezhahq.agent.grpc.ManagedGrpcConnection
import com.nezhahq.agent.service.AgentRuntime
import com.nezhahq.agent.service.AgentRuntimeHandle
import com.nezhahq.agent.service.AgentServiceRuntimeFactory
import com.nezhahq.agent.service.AndroidRuntimeNetworkRegistrar
import com.nezhahq.agent.service.RuntimeNetworkRegistrar
import com.nezhahq.agent.service.RuntimeNetworkRegistration
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** This runner can only target the separately signed/installable regression application. */
class DeviceTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        check(context.packageName.endsWith(".r1test")) { "Device tests require the isolated r1test package" }
        return super.newApplication(cl, DeviceTestApplication::class.java.name, context)
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        val output = results ?: Bundle()
        var outcome = resultCode
        try {
            (targetContext.applicationContext as? DeviceTestApplication)?.closeFixtureIfInitialized()
        } catch (failure: Throwable) {
            Log.e("DeviceRegression", "Fixture cleanup failed", failure)
            output.putString("deviceFixtureCleanupFailure", failure.javaClass.name)
            if (!output.containsKey("shortMsg")) output.putString("shortMsg", "Device fixture cleanup failed")
            outcome = Activity.RESULT_CANCELED
        } finally {
            // Application.onCreate can fail before the fixture exists. Always let instrumentation
            // publish the original result instead of replacing it with an uninitialized-property crash.
            super.finish(outcome, output)
        }
    }
}

class DeviceTestApplication : NezhaAgentApplication() {
    internal lateinit var runtimeFixture: DeviceRuntimeFixture
        private set

    override fun createContainer(): AppContainer {
        runtimeFixture = DeviceRuntimeFixture(this)
        return AppContainer(this, FixtureConfigRepository(runtimeFixture.port), runtimeFixture)
    }

    internal fun closeFixtureIfInitialized() {
        if (::runtimeFixture.isInitialized) runtimeFixture.close()
    }
}

internal val deviceApplication: DeviceTestApplication
    get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DeviceTestApplication

/** Fails with the condition name and a bounded deadline; no fixed settling sleeps in test bodies. */
internal fun awaitCondition(label: String, timeoutMillis: Long = 15_000, condition: () -> Boolean) {
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    while (!condition()) {
        check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for $label" }
        SystemClock.sleep(20)
    }
}

class DeviceEvidenceRule : TestWatcher() {
    override fun starting(description: Description) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageName.endsWith(".r1test"))
        assertEquals("R8 target must not be debuggable", 0, context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val evidence = "api=${Build.VERSION.SDK_INT}\npackage=${context.packageName}\ndebuggable=false\ntest=${description.displayName}\n"
        evidenceDirectory().resolve("device.txt").writeText(evidence)
        Log.i("DeviceRegression", evidence.replace('\n', ' '))
    }

    override fun failed(e: Throwable, description: Description) {
        val name = (description.className + "-" + description.methodName).replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val directory = evidenceDirectory()
        directory.resolve("$name.txt").writeText(e.stackTraceToString())
        runCatching {
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            if (bitmap != null) {
                directory.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }

    private fun evidenceDirectory(): File = File(
        checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)), "device-evidence"
    ).apply { check(isDirectory || mkdirs()) }
}

/** Real gRPC TCP/channel and Android network registration; only GeoIP's external fetch is replaced. */
internal class DeviceRuntimeFixture(private val context: Context) : AgentServiceRuntimeFactory, AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    val starts = AtomicInteger()
    val stops = AtomicInteger()
    val liveRuntimes = AtomicInteger()
    val registrations = AtomicInteger()
    val unregistrations = AtomicInteger()
    val networkCallbacks = AtomicInteger()
    val geoIpFetches = AtomicInteger()
    val acceptedConnections = AtomicInteger()
    val closedConnections = AtomicInteger()
    val events = Collections.synchronizedList(mutableListOf<String>())
    private val sockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "device-loopback-fixture").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean()
    private val nextRuntime = AtomicInteger()

    init {
        server.soTimeout = 250
        executor.execute {
            while (!closed.get()) {
                val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                catch (_: Exception) { break }
                sockets += socket
                acceptedConnections.incrementAndGet()
                executor.execute {
                    try {
                        // Consume the HTTP/2 preface without responding. Cancelling the actual gRPC
                        // channel must close this peer; no fixture signal can make that assertion pass.
                        val buffer = ByteArray(1024)
                        while (socket.getInputStream().read(buffer) != -1) Unit
                    } catch (_: IOException) {
                        // A peer reset or fixture shutdown also terminates the real connection.
                        // Neither is an uncaught test-process failure; release is counted below.
                    } finally {
                        socket.close()
                        sockets -= socket
                        closedConnections.incrementAndGet()
                    }
                }
            }
        }
    }

    override fun create(config: AgentConfig, connectionState: ConnectionStateHolder, statusSink: (String) -> Unit): AgentRuntimeHandle {
        check(config.server == "127.0.0.1" && config.port == port && !config.rootMode)
        val id = nextRuntime.incrementAndGet()
        val platformNetworks = AndroidRuntimeNetworkRegistrar(context)
        val runtime = AgentRuntime(
            context = context,
            config = config,
            statusSink = { state, text -> connectionState.updateState(state); statusSink(text) },
            grpcConnection = ManagedGrpcConnection(config, connectionState::updateState),
            networkRegistrar = RuntimeNetworkRegistrar { onAvailable ->
                val registration = platformNetworks.register {
                    networkCallbacks.incrementAndGet()
                    onAvailable()
                }
                registrations.incrementAndGet()
                val released = AtomicBoolean()
                RuntimeNetworkRegistration {
                    registration.close()
                    if (released.compareAndSet(false, true)) unregistrations.incrementAndGet()
                }
            },
            fetchGeoIp = { geoIpFetches.incrementAndGet(); null }
        )
        return object : AgentRuntimeHandle {
            private val stopped = AtomicBoolean()
            override suspend fun start() {
                runtime.start()
                check(liveRuntimes.incrementAndGet() == 1) { "Overlapping Android runtimes" }
                events += "start:$id"
                starts.incrementAndGet()
            }
            override suspend fun stop() {
                runtime.stop()
                if (stopped.compareAndSet(false, true)) {
                    liveRuntimes.decrementAndGet()
                    events += "stop:$id"
                    stops.incrementAndGet()
                }
            }
            override suspend fun updateCapabilities(capabilities: RemoteCapabilities) = runtime.updateCapabilities(capabilities)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.close()
        synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
        executor.shutdownNow()
        executor.awaitTermination(3, TimeUnit.SECONDS)
    }
}

/** No device configuration is read, even during Application.onCreate or Activity recreation. */
private class FixtureConfigRepository(port: Int) : ConfigRepository {
    private val connection = ConnectionDraft("127.0.0.1", port, "synthetic-device-secret", "device-regression-uuid", false, false)
    override fun storageStatus() = StorageStatus.READY
    override fun initializeStorage(retry: Boolean) = StorageStatus.READY
    override fun resetStorage() = false
    override fun loadAgentConfig() = Result.success(AgentConfig(
        connection.server, connection.port, connection.secret, connection.uuid, connection.useTls, connection.rootMode
    ))
    override fun hasCompleteConnection() = true
    override fun loadConnectionDraft() = connection
    override fun loadToolSettings() = KeepAliveSettings()
    override fun loadRemoteCapabilities() = RemoteCapabilities()
    override fun loadAutoStartState() = AutoStartState(enabled = false, promptShown = true)
    override fun loadSimulatorDraft() = SimulatorDraft("127.0.0.1", connection.port, "synthetic", false, 1)
    override fun saveConnection(draft: ConnectionDraft) = rejected()
    override fun saveToolSettings(settings: KeepAliveSettings) = rejected()
    override fun saveRemoteCapability(capability: RemoteCapability, enabled: Boolean) = rejected()
    override fun saveAutoStart(enabled: Boolean) = rejected()
    override fun saveAutoStartPromptResult(enabled: Boolean) = rejected()
    override fun saveSimulator(draft: SimulatorDraft) = rejected()
    private fun rejected(): Result<Unit> = Result.failure(UnsupportedOperationException("Fixture configuration is immutable"))
}
