package com.nezhahq.agent.executor

import android.content.Context
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import com.nezhahq.agent.util.shellEscape
import com.google.protobuf.ByteString
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import proto.Nezha
import proto.NezhaServiceGrpcKt.NezhaServiceCoroutineStub
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Nezha 文件管理器（TaskType 11）。
 */
class FileManager internal constructor(
    private val context: Context,
    private val streamId: String,
    private val openIoStream: (Flow<Nezha.IOStreamData>) -> Flow<Nezha.IOStreamData>,
    private val downloadSourceOverride: DownloadFileSource?
) {
    constructor(
        context: Context,
        stub: NezhaServiceCoroutineStub,
        streamId: String
    ) : this(
        context = context,
        streamId = streamId,
        openIoStream = { requests -> stub.iOStream(requests) },
        downloadSourceOverride = null
    )

    private companion object {
        /** IOStream StreamID 魔术头（协议规定） */
        val STREAM_MAGIC = byteArrayOf(0xFF.toByte(), 0x05, 0xFF.toByte(), 0x05)

        // ── 二进制协议标识符 ──────────────────────────────────────────────
        val FILE_NAME_IDENTIFIER = byteArrayOf(0x4E, 0x5A, 0x46, 0x4E)
        val FILE_DATA_IDENTIFIER = byteArrayOf(0x4E, 0x5A, 0x54, 0x44)
        val ERROR_IDENTIFIER = byteArrayOf(0x4E, 0x45, 0x52, 0x52)
        val COMPLETE_IDENTIFIER = byteArrayOf(0x4E, 0x5A, 0x55, 0x50)

        const val BUFFER_SIZE = 1024 * 1024
        const val OUTPUT_BUFFER_CAPACITY = 2
        const val DEFAULT_HOME = "/sdcard/"
        const val UPLOAD_HEADER_OFFSET = 1
        const val UPLOAD_SIZE_BYTES = 8
        const val UPLOAD_PATH_OFFSET = UPLOAD_HEADER_OFFSET + UPLOAD_SIZE_BYTES
        const val UPLOAD_MIN_REQUEST_BYTES = UPLOAD_PATH_OFFSET

        /** 目录项名字的长度在协议里只占一个字节，所以名字最多 255 字节。 */
        const val MAX_ENTRY_NAME_BYTES = 255
    }

    private val outputChannel = Channel<Nezha.IOStreamData>(OUTPUT_BUFFER_CAPACITY)
    private val closed = AtomicBoolean(false)

    private sealed interface UploadState {
        data object Idle : UploadState
        data class Active(val session: UploadSession) : UploadState
        // The protocol has no frame marker separating residual content from a new opcode. A
        // failed upload therefore drains this stream permanently; recovery requires a new session.
        data object Aborted : UploadState
    }

    @Volatile private var uploadState: UploadState = UploadState.Idle

    suspend fun run() {
        try {
            resourceSessionScope(::close) session@{
                val header = STREAM_MAGIC + streamId.toByteArray(Charsets.UTF_8)
                val headerMsg = Nezha.IOStreamData.newBuilder()
                    .setData(ByteString.copyFrom(header))
                    .build()
                outputChannel.send(headerMsg)
                Logger.i("FileManager: 已发送会话握手帧")

                launch { keepAliveLoop() }

                openIoStream(outputFlow()).collect { ioData ->
                    val bytes = ioData.data.toByteArray()
                    if (bytes.isEmpty()) return@collect

                    when (val state = uploadState) {
                        is UploadState.Active -> {
                            handleUploadChunk(state.session, bytes)
                            return@collect
                        }
                        UploadState.Aborted -> return@collect
                        UploadState.Idle -> Unit
                    }

                    when (bytes[0].toInt() and 0xFF) {
                        0x00 -> {
                            val dirPath = String(bytes, 1, bytes.size - 1, Charsets.UTF_8)
                            Logger.i("FileManager: 收到列目录请求")
                            listDir(dirPath)
                        }
                        0x01 -> {
                            val filePath = String(bytes, 1, bytes.size - 1, Charsets.UTF_8)
                            Logger.i("FileManager: 收到下载请求")
                            withContext(Dispatchers.IO) { download(filePath) }
                        }
                        0x02 -> {
                            if (bytes.size < UPLOAD_MIN_REQUEST_BYTES) {
                                abortUploadSession()
                                sendError("上传请求数据无效（数据长度不足 9 字节）；请重新打开文件管理会话")
                                return@collect
                            }
                            val fileSize = ByteBuffer.wrap(bytes, UPLOAD_HEADER_OFFSET, UPLOAD_SIZE_BYTES)
                                .order(ByteOrder.BIG_ENDIAN).long
                            val targetPath = String(
                                bytes,
                                UPLOAD_PATH_OFFSET,
                                bytes.size - UPLOAD_PATH_OFFSET,
                                Charsets.UTF_8
                            )
                            Logger.i("FileUpload: 收到上传请求")

                            // 建会话失败也必须进入排空状态：面板不等 ack 就把整个文件推进了
                            // gRPC 缓冲区，而此刻 uploadState 还是 Idle。若停留在 Idle，后续每
                            // 一块文件内容都会按操作码解释——首字节命中 0x00/0x01/0x02 的概率约
                            // 1.2%，几千块下来就是几十次以二进制垃圾为路径的列目录/下载/建会话。
                            // 超过 100 MiB 的上传（UploadSession.validateDeclaredSize 拒绝）正是
                            // 走这条分支，也正是本状态机要消灭的场景。
                            val session = try {
                                beginUpload(targetPath, fileSize)
                            } catch (e: IllegalArgumentException) {
                                abortUploadSession()
                                Logger.e("FileUpload: 上传请求被拒绝 (Error=${e.javaClass.simpleName})")
                                sendError("${e.message ?: "上传请求无效"}；请重新打开文件管理会话")
                                return@collect
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                abortUploadSession()
                                Logger.e("FileUpload: 无法创建临时缓存文件 (Error=${e.javaClass.simpleName})")
                                sendError("无法创建临时缓存文件: ${e.message}；请重新打开文件管理会话")
                                return@collect
                            }

                            if (session.isComplete) {
                                completeUpload(session)
                            }
                        }
                    }
                }
                this@session.cancel()
            }
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
        } catch (e: Exception) {
            Logger.i("FileManager: 文件管理器会话结束 (Error=${e.javaClass.simpleName})")
        }
    }

    private suspend fun beginUpload(
        targetPath: String,
        declaredSize: Long
    ): UploadSession = withContext(Dispatchers.IO) {
        UploadSession.validateDeclaredSize(declaredSize)
        val cacheFile = File.createTempFile("nezha_upload_", ".tmp", context.cacheDir)
        try {
            UploadSession.create(targetPath, declaredSize, cacheFile).also {
                uploadState = UploadState.Active(it)
            }
        } catch (e: Exception) {
            try {
                cacheFile.delete()
            } catch (_: Exception) {
            }
            throw e
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
        val isRootMode = RootShell.isAuthorized()

        // ── 策略 1：Root/Shizuku 模式优先使用 ls 命令 ──────────────────────
        // 关键：Android 11+ FUSE 会过滤 File.listFiles()，导致只能看到文件夹，
        // ls 命令直接通过内核读取 inode，不受 FUSE 过滤影响。
        if (isRootMode) {
            val shellEntries = listDirViaShell(dir)
            if (shellEntries != null) {
                if (!dir.endsWith("/")) dir += "/"
                val response = buildListDirResponse(dir, shellEntries)
                Logger.i("FileManager: 高权限列目录成功")
                sendData(response)
                return
            }
            Logger.i("FileManager: 高权限列目录失败，回退到 Java IO")
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
            Logger.i("FileManager: Java IO 列目录成功")
            val response = buildListDirResponse(dir, entries)
            sendData(response)
            return
        }

        // ── 策略 3：非 Root 模式下 Java IO 失败，不再尝试 RootShell 兜底 ─────
        // [安全修复] 原实现在 rootMode=false 时仍会回退到 RootShell/su 提权，
        // 导致用户以为关闭了高权限模式，文件管理器仍可能获得高权限。
        if (!isRootMode) {
            Logger.i("FileManager: Java IO 列目录失败或为空，未授权高权限回退")
        }

        // ── 最终兜底：回退到 DEFAULT_HOME ───────────────────────────────────
        Logger.i("FileManager: 无法访问目录，回退到默认目录")
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
            val sourceOverride = downloadSourceOverride
            val fileSize = if (sourceOverride == null) {
                getFileSize(filePath)
            } else {
                sourceOverride.size(filePath)
            }
            if (fileSize == null) {
                sendError("无法获取文件信息: $filePath")
                return
            }
            if (fileSize <= 0L) {
                sendError("请求的文件为空")
                return
            }

            val inputStream = if (sourceOverride == null) {
                openInputStreamForPath(filePath)
            } else {
                sourceOverride.open(filePath)
            }
            if (inputStream == null) {
                sendError("无法打开文件: $filePath（权限不足）")
                return
            }

            val closeLock = Any()
            var streamClosed = false
            fun closeInputStream() {
                synchronized(closeLock) {
                    if (streamClosed) return
                    streamClosed = true
                    try {
                        inputStream.close()
                    } catch (e: Exception) {
                        Logger.e("FileManager: 关闭下载流失败 (Error=${e.javaClass.simpleName})")
                    }
                }
            }

            coroutineScope {
                val cancellationCloser = launch(
                    context = Dispatchers.IO,
                    start = CoroutineStart.UNDISPATCHED
                ) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            closeInputStream()
                        }
                    }
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
                    Logger.i("FileManager: 文件下载完成")
                } finally {
                    cancellationCloser.cancel()
                    withContext(NonCancellable + Dispatchers.IO) {
                        closeInputStream()
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e("FileManager: 下载失败 (Error=${e.javaClass.simpleName})")
            sendError("下载失败: ${e.message}")
        }
    }

    private suspend fun handleUploadChunk(session: UploadSession, data: ByteArray) {
        try {
            when (val result = withContext(Dispatchers.IO) { session.writeChunk(data) }) {
                UploadWriteResult.AwaitingMore -> return
                UploadWriteResult.Complete -> completeUpload(session)
                is UploadWriteResult.Rejected -> {
                    abortUploadSession()
                    Logger.e("FileUpload: 上传校验失败 (Error=DeclaredSizeExceeded)")
                    sendError("${result.message}；请重新打开文件管理会话")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            abortUploadSession()
            Logger.e("FileUpload: 写入缓存文件失败 (Error=${e.javaClass.simpleName})")
            sendError("写入文件失败: ${e.message}；请重新打开文件管理会话")
        }
    }

    private suspend fun completeUpload(session: UploadSession) {
        var saved = false
        try {
            withContext(Dispatchers.IO) {
                session.close()
            }
            Logger.i("FileUpload: 缓存完成，正在保存")
            if (moveFileToTarget(session.cacheFile, session.targetPath)) {
                saved = true
                sendData(COMPLETE_IDENTIFIER)
                Logger.i("FileUpload: 文件上传完成")
            } else {
                uploadState = UploadState.Aborted
                sendError("文件保存失败，请检查目标类型、权限和剩余空间: ${session.targetPath}；请重新打开文件管理会话")
                Logger.e("FileUpload: 无法保存到目标路径")
            }
        } finally {
            uploadState = if (saved) UploadState.Idle else UploadState.Aborted
            session.abort()
        }
    }

    /** 中止上传；本会话后续所有入站帧只排空，重新打开文件管理器后才能接收新指令。 */
    private fun abortUploadSession() {
        val session = (uploadState as? UploadState.Active)?.session
        uploadState = UploadState.Aborted
        session?.abort()
    }

    private suspend fun moveFileToTarget(sourceFile: File, targetPath: String): Boolean {
        val isRootMode = RootShell.isAuthorized()

        // 第一次尝试：Java API
        try {
            val target = File(targetPath)
            if (target.isDirectory) return false
            target.parentFile?.mkdirs()
            withContext(Dispatchers.IO) {
                sourceFile.copyTo(target, overwrite = true)
            }
            if (target.exists() && target.length() == sourceFile.length()) return true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}

        // [安全修复] 仅在 rootMode=true 时才尝试 RootShell 提权写入
        if (!isRootMode) {
            Logger.i("FileManager: Java API 写入失败且 rootMode=false，不尝试提权兜底")
            return false
        }

        // 第二次尝试：RootShell（内部统一处理 su/Shizuku 回退和超时）
        try {
            val parentDir = File(targetPath).parent ?: ""
            withContext(Dispatchers.IO) {
                val command = buildList {
                    if (parentDir.isNotEmpty()) add("mkdir -p ${shellEscape(parentDir)}")
                    add("cp ${shellEscape(sourceFile.absolutePath)} ${shellEscape(targetPath)}")
                    // Owner-only: the copy runs as root, and 666 would leave anything uploaded
                    // into a private directory writable by every app on the device.
                    add("chmod 600 ${shellEscape(targetPath)}")
                }.joinToString(" && ")
                // Isolated: copying a large file takes far longer than the dashboard's
                // state-report timeout, and holding the shared shell that long stalls the metrics
                // stream until the dashboard drops the connection carrying this very transfer.
                RootShell.executeIsolated(command, timeoutMs = 120_000)
            }
            if (withContext(Dispatchers.IO) { getFileSize(targetPath) } == sourceFile.length()) {
                return true
            }
        } catch (e: CancellationException) {
            throw e
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

    /**
     * 打开文件的 InputStream。
     *
     * ## 策略优先级（与 listDir 一致的 Root-first 策略）
     * Root/Shizuku 模式下优先通过 [RootShell] 打开流式 `cat` 命令，
     * 防止 FUSE 层因 Scoped Storage 拦截文件读取。RootShell 会再次强制校验权限。
     *
     * 1. Root 模式：优先使用集中授权的高权限输入流
     * 2. Java FileInputStream（非 Root 或 su 失败时）
     */
    private suspend fun openInputStreamForPath(path: String): InputStream? {
        val isRootMode = RootShell.isAuthorized()

        // ── 策略 1：Root 模式优先使用集中授权的流式命令 ───────────────────
        if (isRootMode) {
            val privilegedInput = withContext(Dispatchers.IO) {
                RootShell.openCommandInputStream("cat ${shellEscape(path)}")
            }
            if (privilegedInput != null) {
                Logger.i("FileManager: 使用已授权高权限读取文件")
                return privilegedInput
            }
            Logger.i("FileManager: 高权限读取不可用，回退到 Java IO")
        }

        // ── 策略 2：Java FileInputStream ────────────────────────────────
        try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                return FileInputStream(file)
            }
        } catch (_: Exception) {}

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
            val nameBytes = truncateUtf8(entry.name, MAX_ENTRY_NAME_BYTES)
            bos.write(if (entry.isDir) 1 else 0)
            bos.write(nameBytes.size)
            bos.write(nameBytes, 0, nameBytes.size)
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
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {}
    }

    private suspend fun sendError(message: String) {
        Logger.e("FileManager: 向面板报告操作失败")
        val errorBytes = ERROR_IDENTIFIER + message.toByteArray(Charsets.UTF_8)
        sendData(errorBytes)
    }

    private fun close() {
        if (closed.getAndSet(true)) return
        Logger.i("FileManager: 正在关闭文件管理会话")
        abortUploadSession()
        outputChannel.close()
    }


}

internal interface DownloadFileSource {
    suspend fun size(path: String): Long?
    suspend fun open(path: String): InputStream?
}

/**
 * 按 UTF-8 字节上限截断 [value]，且不切开多字节序列。
 *
 * 原实现是 `nameBytes.size.coerceAtMost(255)`：一个 3 字节的汉字正好跨过上限时会被截掉
 * 一半，面板收到的是无法解码的字节，整个名字都变成乱码。上限本身是协议要求的
 * （长度只占一个字节），所以只能在字节层面逼近它，不能改成按字符数截断。
 *
 * UTF-8 的续接字节固定是 `10xxxxxx`，从上限处向前回退到第一个非续接字节，
 * 就是离上限最近的合法字符边界。
 */
internal fun truncateUtf8(value: String, maxBytes: Int): ByteArray {
    val bytes = value.toByteArray(Charsets.UTF_8)
    if (bytes.size <= maxBytes) return bytes
    if (maxBytes <= 0) return ByteArray(0)

    var end = maxBytes
    while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
    return bytes.copyOf(end)
}
