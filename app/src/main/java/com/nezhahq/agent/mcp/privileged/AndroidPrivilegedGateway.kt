package com.nezhahq.agent.mcp.privileged

import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.nezhahq.agent.util.RootShell
import moe.shizuku.server.IShizukuService
import moe.shizuku.server.IRemoteProcess
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Thin Android adapter. Ordinary mode never reaches this object. No permission requests here. */
internal object AndroidPrivilegedGateway : PrivilegedGateway {
    private val listening = AtomicBoolean(false)
    private val received = Shizuku.OnBinderReceivedListener { RootShell.mcpAuthority.serviceChanged() }
    private val dead = Shizuku.OnBinderDeadListener { RootShell.mcpAuthority.serviceChanged() }

    override fun serviceToken(): Any? = Shizuku.getBinder()?.takeIf { it.isBinderAlive }

    override fun service(): PrivilegedService? {
        if (listening.compareAndSet(false, true)) {
            Shizuku.addBinderReceivedListener(received)
            Shizuku.addBinderDeadListener(dead)
        }
        return capturePrivilegedService(Shizuku::getBinder, { it.isBinderAlive }, Shizuku::getUid, ::CapturedService)
    }

    override fun executableSu(): String? = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
        .asSequence().filter { it.isNotBlank() && File(it).isAbsolute }.map { File(it, "su") }
        .firstOrNull { it.isFile && it.canExecute() }?.canonicalPath

    override fun launchSu(path: String, command: String, environment: Map<String, String>, directory: String): Process =
        RootShell.startMcpSuRaw(path, command, environment, directory)

    private class CapturedService(private val binder: IBinder, override val uid: Int) : PrivilegedService {
        override val token: Any get() = binder
        private val service = IShizukuService.Stub.asInterface(binder)
        override fun isAlive(): Boolean = binder.isBinderAlive
        // Deliberately bypass Shizuku.checkSelfPermission and checkRemotePermission caches.
        override fun checkPermission(): Boolean = service.checkSelfPermission()
        override fun launch(argv: Array<String>, environment: Array<String>, directory: String): Process =
            RootShell.startMcpShizukuRaw(binder, service, argv, environment, directory)
    }
}

/** No SDK static process cache; pipe retrieval is owned exclusively by the launch worker. */
internal class McpRemoteProcess(private val remote: IRemoteProcess) : Process() {
    override fun getOutputStream() = ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream)
    override fun getInputStream() = ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream)
    override fun getErrorStream() = ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream)
    override fun waitFor(): Int = remote.waitFor()
    override fun exitValue(): Int {
        if (remote.alive()) throw IllegalThreadStateException("Remote MCP proxy is still running")
        return remote.exitValue()
    }
    override fun destroy() = remote.destroy()
}
