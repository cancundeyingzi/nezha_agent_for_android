package com.nezhahq.agent.executor

import android.content.Context
import android.content.pm.PackageManager
import com.nezhahq.agent.util.ConfigStore
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import com.google.protobuf.ByteString
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import proto.Nezha
import proto.NezhaServiceGrpcKt.NezhaServiceCoroutineStub
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Nezha 文件管理器（TaskType 11）。
 */
class FileManager(
    private val context: Context,
    private val stub: NezhaServiceCoroutineStub,
    private val streamId: String
) {
    private companion object {
        /** IOStream StreamID 魔术头（协议规定） */
        val STREAM_MAGIC = byteArrayOf(0xFF.toByte(), 0x05, 0xFF.toByte(), 0x05)

        // ── 二进制协议标识符 ──────────────────────────────────────────────
        val FILE_NAME_IDENTIFIER = byteArrayOf(0x4E, 0x5A, 0x46, 0x4E)
        val FILE_DATA_IDENTIFIER = byteArrayOf(0x4E, 0x5A, 0x54, 0x44)
        val ERROR_IDENTIFIER = byteArrayOf(0x4E, 0x45, 0x52, 0x52)
        val COMPLETE_IDENTIFIER = byteArrayOf(0x4E, 0x5A, 0x55, 0x50)

        const val BUFFER_SIZE = 1024 * 1024
        const val DEFAULT_HOME = "/sdcard/"
    }

    private val outputChannel = Channel<Nezha.IOStreamData>(Channel.BUFFERED)
    private val closed = AtomicBoolean(false)

    // 上传状态变量
    @Volatile private var uploadStarted = false
    private var pendingUploadPath = ""
    private var pendingUploadSize = 0UL
    private var pendingUploadReceived = 0UL
    private var pendingUploadStream: FileOutputStream? = null
    // 上传的离线缓存文件，写入完毕后再移动到最终目录，避开 SELinux
    private var pendingUploadCacheFile: File? = null

    suspend fun run() {
        try {
            coroutineScope {
                val header = STREAM_MAGIC + streamId.toByteArray(Charsets.UTF_8)
                val headerMsg = Nezha.IOStreamData.newBuilder()
                    .setData(ByteString.copyFrom(header))
                    .build()
                outputChannel.send(headerMsg)
                Logger.i("FileManager: 已发送 StreamID 握手帧 (StreamID=$streamId)")

                launch { keepAliveLoop() }

                stub.iOStream(outputFlow()).collect { ioData ->
                    val bytes = ioData.data.toByteArray()
                    if (bytes.isEmpty()) return@collect

                    if (uploadStarted) {
                        handleUploadChunk(bytes)
                        return@collect
                    }

                    when (bytes[0].toInt() and 0xFF) {
                        0x00 -> {
                            val dirPath = String(bytes, 1, bytes.size - 1, Charsets.UTF_8)
                            Logger.i("FileManager: 收到列目录请求: $dirPath (StreamID=$streamId)")
                            listDir(dirPath)
                        }
                        0x01 -> {
                            val filePath = String(bytes, 1, bytes.size - 1, Charsets.UTF_8)
                            Logger.i("FileManager: 收到下载请求: $filePath (StreamID=$streamId)")
                            launch(Dispatchers.IO) { download(filePath) }
                        }
                        0x02 -> {
                            if (bytes.size < 9) {
                                sendError("上传请求数据无效（数据长度不足 9 字节）")
                                return@collect
                            }
                            val fileSize = ByteBuffer.wrap(bytes, 1, 8)
                                .order(ByteOrder.BIG_ENDIAN).long.toULong()
                            val targetPath = String(bytes, 9, bytes.size - 9, Charsets.UTF_8)
                            Logger.i("FileManager: 收到上传请求: $targetPath (size=$fileSize) (StreamID=$streamId)")
                            
                            uploadStarted = true
                            pendingUploadPath = targetPath
                            pendingUploadSize = fileSize
                            pendingUploadReceived = 0UL
                            
                            // 总是使用缓存文件进行传输，解决 Root 路径直接 IO 写失败问题
                            pendingUploadCacheFile = File(context.cacheDir, "nezha_upload_${System.currentTimeMillis()}.tmp")
                            try {
                                pendingUploadStream = withContext(Dispatchers.IO) { 
                                    FileOutputStream(pendingUploadCacheFile) 
                                }
                            } catch (e: Exception) {
                                sendError("无法创建临时缓存文件: ${e.message}")
                                uploadStarted = false
                            }
                        }
                    }
                }
                this@coroutineScope.cancel()
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Logger.i("FileManager: 文件管理器会话结束 (StreamID=$streamId): ${e.message}")
            }
        } finally {
            close()
        }
    }

    /**
     * 列出指定目录的内容并发送给 Dashboard。
     *
     * ## 策略优先级（关键修复）
     * Android 11+ 的 Scoped Storage FUSE 层会过滤 File.listFiles() 的结果——
     * 即使返回非 null，结果中也可能**只包含目录而过滤掉所有文件**。
     * 因此当 Root/Shizuku 可用时，**优先使用 RootShell ls** 绕过 FUSE 限制。
     *
     * 1. Root/Shizuku 模式：优先 `ls -1Ap`（绕过 FUSE，看到完整文件列表）
     * 2. Java IO：普通模式或 Root ls 失败时使用 `File.listFiles()`
     * 3. 兜底：回退到 DEFAULT_HOME
     */
    private suspend fun listDir(requestedDir: String) {
        // 空路径处理（Dashboard 初次连接可能发送空字符串）
        var dir = requestedDir.ifBlank { DEFAULT_HOME }
        val isRootMode = ConfigStore.getRootMode(context)

        // ── 策略 1：Root/Shizuku 模式优先使用 ls 命令 ──────────────────────
        // 关键：Android 11+ FUSE 会过滤 File.listFiles()，导致只能看到文件夹，
        // ls 命令直接通过内核读取 inode，不受 FUSE 过滤影响。
        if (isRootMode) {
            val shellEntries = listDirViaShell(dir)
            if (shellEntries != null) {
                if (!dir.endsWith("/")) dir += "/"
                val response = buildListDirResponse(dir, shellEntries)
                Logger.i("FileManager: RootShell ls 列目录成功: $dir, 共 ${shellEntries.size} 条目 (StreamID=$streamId)")
                sendData(response)
                return
            }
            Logger.i("FileManager: RootShell ls 无法访问 $dir，回退到 Java IO...")
        }

        // ── 策略 2：Java IO（普通模式或 Root ls 失败时）───────────────────
        val javaFile = File(dir)
        val javaEntries = javaFile.listFiles()
        if (javaEntries != null && javaEntries.isNotEmpty()) {
            val entries = javaEntries.map { file ->
                // 使用 try-catch 保护 isDirectory 调用，
                // 防止符号链接/FUSE 解析失败导致异常
                val isDir = try { file.isDirectory } catch (_: Exception) { false }
                FileEntry(file.name, isDir)
            }
            if (!dir.endsWith("/")) dir += "/"
            Logger.i("FileManager: Java IO 列目录成功: $dir, 共 ${entries.size} 条目 (StreamID=$streamId)")
            val response = buildListDirResponse(dir, entries)
            sendData(response)
            return
        }

        // ── 策略 3：非 Root 模式下 Java IO 失败，尝试 RootShell 兜底 ─────
        if (!isRootMode) {
            Logger.i("FileManager: Java IO listFiles() 返回 null/空 (dir=$dir, exists=${javaFile.exists()}, canRead=${javaFile.canRead()})，尝试 RootShell 回退...")
            val shellEntries = listDirViaShell(dir)
            if (shellEntries != null) {
                if (!dir.endsWith("/")) dir += "/"
                val response = buildListDirResponse(dir, shellEntries)
                sendData(response)
                return
            }
        }

        // ── 最终兜底：回退到 DEFAULT_HOME ───────────────────────────────────
        Logger.i("FileManager: 所有方式均无法访问 $dir，回退到 $DEFAULT_HOME")
        dir = DEFAULT_HOME
        // 兜底路径也优先 Root ls
        if (isRootMode) {
            val shellEntries = listDirViaShell(dir)
            if (shellEntries != null) {
                val response = buildListDirResponse(dir, shellEntries)
                sendData(response)
                return
            }
        }
        val fallbackEntries = File(dir).listFiles()
        if (fallbackEntries != null) {
            val response = buildListDirResponse(dir, fallbackEntries.map {
                val isDir = try { it.isDirectory } catch (_: Exception) { false }
                FileEntry(it.name, isDir)
            })
            sendData(response)
        } else {
            sendError("无法访问目录: $requestedDir（也无法回退到 $DEFAULT_HOME）")
        }
    }

    /**
     * 通过 RootShell 的 ls 命令列出目录内容。
     *
     * 使用 `ls -1Ap`：
     * - `-1`：每行一个条目
     * - `-A`：显示隐藏文件（除 . 和 ..）
     * - `-p`：目录末尾追加 `/`
     *
     * @return 文件条目列表，失败返回 null
     */
    private suspend fun listDirViaShell(dir: String): List<FileEntry>? {
        val shellResult = withContext(Dispatchers.IO) {
            RootShell.execute("ls -1Ap ${shellEscape(dir)}")
        }
        if (shellResult.isBlank()) return null

        val entries = shellResult.lines()
            .filter { it.isNotBlank() }
            .map { line ->
                if (line.endsWith("/")) {
                    FileEntry(line.dropLast(1), isDir = true)
                } else {
                    FileEntry(line, isDir = false)
                }
            }
        return if (entries.isNotEmpty()) entries else null
    }

    private suspend fun download(filePath: String) {
        try {
            val fileSize = getFileSize(filePath)
            if (fileSize == null) {
                sendError("无法获取文件信息: $filePath")
                return
            }
            if (fileSize <= 0L) {
                sendError("请求的文件为空")
                return
            }

            val inputStream = openInputStreamForPath(filePath)
            if (inputStream == null) {
                sendError("无法打开文件: $filePath（权限不足）")
                return
            }

            try {
                val headerBuf = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
                headerBuf.put(FILE_DATA_IDENTIFIER)
                headerBuf.putLong(fileSize)
                sendData(headerBuf.array())

                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val bytesRead = withContext(Dispatchers.IO) {
                        inputStream.read(buffer)
                    }
                    if (bytesRead == -1) break
                    if (bytesRead > 0) {
                        sendData(buffer.copyOf(bytesRead))
                    }
                }
                Logger.i("FileManager: 文件下载完成: $filePath (StreamID=$streamId)")
            } finally {
                withContext(Dispatchers.IO) {
                    try { inputStream.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                Logger.e("FileManager: 下载失败: $filePath (StreamID=$streamId)", e)
                sendError("下载失败: ${e.message}")
            }
        }
    }

    private suspend fun handleUploadChunk(data: ByteArray) {
        val stream = pendingUploadStream ?: return
        try {
            withContext(Dispatchers.IO) {
                stream.write(data)
            }
            pendingUploadReceived += data.size.toULong()

            if (pendingUploadReceived >= pendingUploadSize) {
                withContext(Dispatchers.IO) {
                    stream.flush()
                    stream.close()
                }
                Logger.i("FileManager: 文件传输到缓存完成，正在移动到目标路径: $pendingUploadPath (StreamID=$streamId)")
                
                val sourceFile = pendingUploadCacheFile
                if (sourceFile != null && moveFileToTarget(sourceFile, pendingUploadPath)) {
                    sendData(COMPLETE_IDENTIFIER)
                    Logger.i("FileManager: 文件上传完全成功")
                } else {
                    sendError("文件保存失败，目标路径权限不足: $pendingUploadPath")
                    Logger.e("FileManager: 无法将缓存文件移动到目标路径")
                }
                resetUploadState()
            }
        } catch (e: Exception) {
            Logger.e("FileManager: 写入缓存文件失败: $pendingUploadPath (StreamID=$streamId)", e)
            sendError("写入文件失败: ${e.message}")
            withContext(Dispatchers.IO) {
                try { stream.close() } catch (_: Exception) {}
            }
            resetUploadState()
        }
    }

    private fun resetUploadState() {
        uploadStarted = false
        pendingUploadPath = ""
        pendingUploadSize = 0UL
        pendingUploadReceived = 0UL
        pendingUploadStream = null
        try { pendingUploadCacheFile?.delete() } catch (_: Exception) {}
        pendingUploadCacheFile = null
    }

    private suspend fun moveFileToTarget(sourceFile: File, targetPath: String): Boolean {
        // 第一次尝试：Java API
        try {
            val target = File(targetPath)
            target.parentFile?.mkdirs()
            withContext(Dispatchers.IO) {
                sourceFile.copyTo(target, overwrite = true)
            }
            if (target.exists() && target.length() == sourceFile.length()) return true
        } catch (_: Exception) {}

        // 第二次尝试：RootShell (sucp)
        try {
            val parentDir = File(targetPath).parent ?: ""
            withContext(Dispatchers.IO) {
                if (parentDir.isNotEmpty()) RootShell.execute("mkdir -p ${shellEscape(parentDir)}")
                RootShell.execute("cp ${shellEscape(sourceFile.absolutePath)} ${shellEscape(targetPath)}")
                RootShell.execute("chmod 666 ${shellEscape(targetPath)}")
            }
            if (withContext(Dispatchers.IO) { getFileSize(targetPath) } == sourceFile.length()) {
                return true
            }
        } catch (_: Exception) {}

        // 第三次尝试：Shizuku (sh cp)
        try {
            if (isShizukuAvailable()) {
                @Suppress("DEPRECATION")
                val method = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                method.isAccessible = true
                val p = method.invoke(
                    null,
                    arrayOf("sh", "-c", "cp ${shellEscape(sourceFile.absolutePath)} ${shellEscape(targetPath)} && chmod 666 ${shellEscape(targetPath)}"),
                    null, null
                ) as Process
                withContext(Dispatchers.IO) { p.waitFor() }
                if (withContext(Dispatchers.IO) { getFileSize(targetPath) } == sourceFile.length()) {
                    return true
                }
            }
        } catch (_: Exception) {}

        return false
    }

    private suspend fun getFileSize(path: String): Long? {
        val file = File(path)
        if (file.exists() && file.canRead()) {
            return file.length()
        }
        val result = withContext(Dispatchers.IO) {
            RootShell.execute("stat -c %s ${shellEscape(path)}")
        }
        return result.trim().toLongOrNull()
    }

    private class ProcessInputStream(private val process: Process) : InputStream() {
        private val root = process.inputStream
        override fun read(): Int = root.read()
        override fun read(b: ByteArray): Int = root.read(b)
        override fun read(b: ByteArray, off: Int, len: Int): Int = root.read(b, off, len)
        override fun available(): Int = root.available()
        override fun close() {
            super.close()
            try { root.close() } catch (_: Exception) {}
            try { process.destroy() } catch (_: Exception) {}
        }
    }

    /**
     * 打开文件的 InputStream。
     *
     * ## 策略优先级（与 listDir 一致的 Root-first 策略）
     * Root/Shizuku 模式下优先使用 `su -c cat` 或 Shizuku 读取，
     * 防止 FUSE 层因 Scoped Storage 拦截文件读取。
     *
     * 1. Root 模式：优先 `su -c cat`（绕过 FUSE）
     * 2. Java FileInputStream（非 Root 或 su 失败时）
     * 3. Shizuku（Java IO 也失败时的最后手段）
     */
    private suspend fun openInputStreamForPath(path: String): InputStream? {
        val isRootMode = ConfigStore.getRootMode(context)

        // ── 策略 1：Root 模式优先使用 su -c cat ──────────────────────────
        if (isRootMode) {
            try {
                val p = withContext(Dispatchers.IO) {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "cat ${shellEscape(path)}"))
                }
                // 不再使用 delay+exitValue 的脆弱检测，直接返回 ProcessInputStream
                // 如果文件不存在或权限不足，read() 时自然会返回 EOF 或抛异常
                Logger.i("FileManager: 使用 Root (su) 读取文件: $path")
                return ProcessInputStream(p)
            } catch (e: Exception) {
                Logger.i("FileManager: Root (su) 读取文件失败，回退到 Java IO: $path (${e.message})")
            }
        }

        // ── 策略 2：Java FileInputStream ────────────────────────────────
        try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                return FileInputStream(file)
            }
        } catch (_: Exception) {}

        // ── 策略 3：非 Root 模式下尝试 su（用户可能有 Root 但未开启 Root 模式开关）
        if (!isRootMode) {
            try {
                val p = withContext(Dispatchers.IO) {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "cat ${shellEscape(path)}"))
                }
                delay(200)
                val alive = try { p.exitValue(); false } catch (_: IllegalThreadStateException) { true }
                if (alive) {
                    Logger.i("FileManager: 使用 Root (su) 读取文件: $path")
                    return ProcessInputStream(p)
                }
                try { p.destroy() } catch (_: Exception) {}
            } catch (_: Exception) {}
        }

        // ── 策略 4：Shizuku ─────────────────────────────────────────────
        try {
            if (isShizukuAvailable()) {
                @Suppress("DEPRECATION")
                val method = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                method.isAccessible = true
                val p = method.invoke(
                    null,
                    arrayOf("sh", "-c", "cat ${shellEscape(path)}"),
                    null, null
                ) as Process
                Logger.i("FileManager: 使用 Shizuku 读取文件: $path")
                return ProcessInputStream(p)
            }
        } catch (e: Exception) {
            Logger.e("FileManager: Shizuku 读取文件失败: $path", e)
        }

        return null
    }

    private data class FileEntry(val name: String, val isDir: Boolean)

    private fun buildListDirResponse(dir: String, entries: List<FileEntry>): ByteArray {
        val bos = ByteArrayOutputStream(1024)
        val pathBytes = dir.toByteArray(Charsets.UTF_8)
        bos.write(FILE_NAME_IDENTIFIER)
        val pathLenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        pathLenBuf.putInt(pathBytes.size)
        bos.write(pathLenBuf.array())
        bos.write(pathBytes)

        for (entry in entries) {
            val nameBytes = entry.name.toByteArray(Charsets.UTF_8)
            val nameLen = nameBytes.size.coerceAtMost(255)
            bos.write(if (entry.isDir) 1 else 0)
            bos.write(nameLen)
            bos.write(nameBytes, 0, nameLen)
        }
        return bos.toByteArray()
    }

    private fun outputFlow(): Flow<Nezha.IOStreamData> = flow {
        for (data in outputChannel) { emit(data) }
    }

    private suspend fun keepAliveLoop() {
        try {
            while (!closed.get()) {
                delay(30_000)
                if (closed.get()) break
                outputChannel.send(
                    Nezha.IOStreamData.newBuilder()
                        .setData(ByteString.EMPTY)
                        .build()
                )
            }
        } catch (_: Exception) {}
    }

    private suspend fun sendData(data: ByteArray) {
        if (closed.get()) return
        try {
            outputChannel.send(
                Nezha.IOStreamData.newBuilder()
                    .setData(ByteString.copyFrom(data))
                    .build()
            )
        } catch (_: Exception) {}
    }

    private suspend fun sendError(message: String) {
        Logger.e("FileManager: 发送错误: $message (StreamID=$streamId)")
        val errorBytes = ERROR_IDENTIFIER + message.toByteArray(Charsets.UTF_8)
        sendData(errorBytes)
    }

    private fun close() {
        if (closed.getAndSet(true)) return
        Logger.i("FileManager: 正在关闭文件管理器会话 (StreamID=$streamId)")
        try { pendingUploadStream?.close() } catch (_: Exception) {}
        try { pendingUploadCacheFile?.delete() } catch (_: Exception) {}
        pendingUploadStream = null
        pendingUploadCacheFile = null
        outputChannel.close()
    }

    private fun shellEscape(input: String): String {
        return "'" + input.replace("'", "'\\''") + "'"
    }

    private fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
                    && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }
}
