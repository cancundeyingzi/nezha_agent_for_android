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
    private val downloadSourceOverride: DownloadFileSource?,
    /** 单调时钟，仅为让"中止后静默期"可被单测驱动而注入；生产走 [System.nanoTime]。 */
    private val nanoTime: () -> Long = System::nanoTime
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

    /**
     * 入站帧的解释方式。
     *
     * 之前是一个 `uploadSession: UploadSession?`，null 同时表达了两件事：
     * "空闲，按操作码解释" 和 "刚刚中止"。后者被当成前者，正是残余文件内容被
     * 逐块当指令执行的原因，所以这里把两种含义拆成显式状态。
     */
    private sealed interface UploadState {
        /** 空闲：入站帧按操作码解释。 */
        data object Idle : UploadState

        /** 上传进行中：入站帧一律是文件内容。 */
        data class Active(val session: UploadSession) : UploadState

        /**
         * 上传已中止，正在排空面板尚未发完的残余内容。
         *
         * [lastFrameNanos] 是最近一次收到（并丢弃）帧的时刻，用于判断静默期，
         * 详见 [UploadAbortRecovery]。
         */
        data class Aborted(val lastFrameNanos: Long) : UploadState
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
                Logger.i("FileManager: 已发送 StreamID 握手帧 (StreamID=$streamId)")

                launch { keepAliveLoop() }

                openIoStream(outputFlow()).collect { ioData ->
                    val bytes = ioData.data.toByteArray()
                    if (bytes.isEmpty()) return@collect

                    val opcode = bytes[0].toInt() and 0xFF
                    when (val state = uploadState) {
                        is UploadState.Active -> {
                            handleUploadChunk(state.session, bytes)
                            return@collect
                        }

                        is UploadState.Aborted -> {
                            val now = nanoTime()
                            if (!UploadAbortRecovery.isNewCommandFrame(
                                    opcode,
                                    now - state.lastFrameNanos
                                )
                            ) {
                                // 消息刻意不含长度等可变内容，交给 Logger 的去重折叠成一条 ×N
                                Logger.i("FileUpload: 上传已中止，丢弃面板残余数据 (StreamID=$streamId)")
                                uploadState = UploadState.Aborted(now)
                                return@collect
                            }
                            Logger.i("FileUpload: 静默期后收到新指令，恢复正常分发 (StreamID=$streamId)")
                            uploadState = UploadState.Idle
                        }

                        UploadState.Idle -> Unit
                    }

                    when (opcode) {
                        0x00 -> {
                            val dirPath = String(bytes, 1, bytes.size - 1, Charsets.UTF_8)
                            Logger.i("FileManager: 收到列目录请求: $dirPath (StreamID=$streamId)")
                            listDir(dirPath)
                        }
                        0x01 -> {
                            val filePath = String(bytes, 1, bytes.size - 1, Charsets.UTF_8)
                            Logger.i("FileManager: 收到下载请求: $filePath (StreamID=$streamId)")
                            withContext(Dispatchers.IO) { download(filePath) }
                        }
                        0x02 -> {
                            if (bytes.size < UPLOAD_MIN_REQUEST_BYTES) {
                                sendError("上传请求数据无效（数据长度不足 9 字节）")
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
                            Logger.i("FileUpload: 收到上传请求: $targetPath (size=$fileSize) (StreamID=$streamId)")

                            // 建会话失败也必须进入排空状态：面板不等 ack 就把整个文件推进了
                            // gRPC 缓冲区，而此刻 uploadState 还是 Idle。若停留在 Idle，后续每
                            // 一块文件内容都会按操作码解释——首字节命中 0x00/0x01/0x02 的概率约
                            // 1.2%，几千块下来就是几十次以二进制垃圾为路径的列目录/下载/建会话。
                            // 超过 100 MiB 的上传（UploadSession.validateDeclaredSize 拒绝）正是
                            // 走这条分支，也正是本状态机要消灭的场景。
                            val session = try {
                                beginUpload(targetPath, fileSize)
                            } catch (e: IllegalArgumentException) {
                                Logger.e("FileUpload: 上传请求被拒绝: ${e.message}")
                                abortUploadSession()
                                sendError(e.message ?: "上传请求无效")
                                return@collect
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Logger.e("FileUpload: 无法创建临时缓存文件: $targetPath (StreamID=$streamId)", e)
                                abortUploadSession()
                                sendError("无法创建临时缓存文件: ${e.message}")
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
            Logger.i("FileManager: 文件管理器会话结束 (StreamID=$streamId): ${e.message}")
        }
    }

    private suspend fun beginUpload(
        targetPath: String,
        declaredSize: Long
    ): UploadSession = withContext(Dispatchers.IO) {
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

        // ── 策略 3：非 Root 模式下 Java IO 失败，不再尝试 RootShell 兜底 ─────
        // [安全修复] 原实现在 rootMode=false 时仍会回退到 RootShell/su 提权，
        // 导致用户以为关闭了高权限模式，文件管理器仍可能获得高权限。
        if (!isRootMode) {
            Logger.i("FileManager: Java IO listFiles() 返回 null/空 (dir=$dir, exists=${javaFile.exists()}, canRead=${javaFile.canRead()})，rootMode=false，不尝试提权兜底")
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
                        Logger.e("FileManager: 关闭下载流失败: $filePath (StreamID=$streamId)", e)
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
                    Logger.i("FileManager: 文件下载完成: $filePath (StreamID=$streamId)")
                } finally {
                    cancellationCloser.cancel()
                    withContext(NonCancellable + Dispatchers.IO) {
                        closeInputStream()
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e("FileManager: 下载失败: $filePath (StreamID=$streamId)", e)
            sendError("下载失败: ${e.message}")
        }
    }

    private suspend fun handleUploadChunk(session: UploadSession, data: ByteArray) {
        try {
            when (val result = withContext(Dispatchers.IO) { session.writeChunk(data) }) {
                UploadWriteResult.AwaitingMore -> return
                UploadWriteResult.Complete -> completeUpload(session)
                is UploadWriteResult.Rejected -> {
                    Logger.e("FileUpload: 上传校验失败: ${result.message} (StreamID=$streamId)")
                    sendError(result.message)
                    abortUploadSession()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("FileUpload: 写入缓存文件失败: ${session.targetPath} (StreamID=$streamId)", e)
            sendError("写入文件失败: ${e.message}")
            abortUploadSession()
        }
    }

    private suspend fun completeUpload(session: UploadSession) {
        try {
            withContext(Dispatchers.IO) {
                session.close()
            }
            Logger.i("FileUpload: 文件传输到缓存完成，正在移动到目标路径: ${session.targetPath} (StreamID=$streamId)")
            if (moveFileToTarget(session.cacheFile, session.targetPath)) {
                sendData(COMPLETE_IDENTIFIER)
                Logger.i("FileUpload: 文件上传完成: ${session.targetPath} (StreamID=$streamId)")
            } else {
                sendError("文件保存失败，目标路径权限不足: ${session.targetPath}")
                Logger.e("FileUpload: 无法将缓存文件移动到目标路径: ${session.targetPath}")
            }
        } finally {
            // 正常收尾：回到 Idle，而不是 Aborted——没有残余数据需要排空
            uploadState = UploadState.Idle
            try { session.cacheFile.delete() } catch (_: Exception) {}
        }
    }

    /** 中止当前上传，并进入排空残余数据的状态（见 [UploadAbortRecovery]）。 */
    private fun abortUploadSession() {
        releaseActiveSession()
        uploadState = UploadState.Aborted(lastFrameNanos = nanoTime())
    }

    /** 关闭并删除进行中上传的缓存文件（若有）；不改变状态。 */
    private fun releaseActiveSession() {
        (uploadState as? UploadState.Active)?.session?.abort()
    }

    private suspend fun moveFileToTarget(sourceFile: File, targetPath: String): Boolean {
        val isRootMode = RootShell.isAuthorized()

        // 第一次尝试：Java API
        try {
            val target = File(targetPath)
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
                Logger.i("FileManager: 使用集中授权的 Root/Shizuku Shell 读取文件: $path")
                return privilegedInput
            }
            Logger.i("FileManager: 高权限读取不可用，回退到 Java IO: $path")
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
        Logger.e("FileManager: 发送错误: $message (StreamID=$streamId)")
        val errorBytes = ERROR_IDENTIFIER + message.toByteArray(Charsets.UTF_8)
        sendData(errorBytes)
    }

    private fun close() {
        if (closed.getAndSet(true)) return
        Logger.i("FileManager: 正在关闭文件管理器会话 (StreamID=$streamId)")
        // 会话到此为止，不需要排空残余数据：直接回到 Idle
        releaseActiveSession()
        uploadState = UploadState.Idle
        outputChannel.close()
    }


}

internal interface DownloadFileSource {
    suspend fun size(path: String): Long?
    suspend fun open(path: String): InputStream?
}

/**
 * 上传中止后，如何重新与面板对齐。
 *
 * ## 为什么需要它
 * 分发逻辑靠"当前有没有上传会话"来决定把一帧当文件内容还是当操作码。上传被拒
 * （超量）或写盘失败会中止会话，而面板往往已经把整个文件推进了 gRPC 发送缓冲区——
 * 这些残余字节于是被逐块当成操作码，产生大量错误响应和以二进制垃圾为路径的下载请求。
 *
 * ## 判据
 * 协议里没有"这一帧是新指令"的标记，只能靠两个信号一起判断：
 * 1. 首字节是已知操作码；
 * 2. 距上一帧的静默时间超过 [QUIET_PERIOD_NANOS]。
 *
 * 残余块是连续突发，帧间几乎没有空档；真正的新指令来自用户的下一次点击，中间必然有
 * 明显停顿。两个条件同时满足才恢复解释指令，否则继续丢弃。最保险的恢复方式依然是
 * 重建流——那会新建一个 [FileManager]，状态从 Idle 重新开始。
 */
internal object UploadAbortRecovery {

    /**
     * 恢复解释指令所需的静默时长。
     *
     * 3 秒足以跨过残余突发内部的抖动（同一条 gRPC 流上的连续帧不会隔这么久），
     * 又不至于让用户在同一条流上重试时长时间没有反应。
     */
    const val QUIET_PERIOD_NANOS = 3_000_000_000L

    /** 与 [FileManager] 分发分支一一对应：列目录 / 下载 / 新上传。 */
    private val COMMAND_OPCODES = setOf(0x00, 0x01, 0x02)

    fun isNewCommandFrame(
        opcode: Int,
        idleNanos: Long,
        quietPeriodNanos: Long = QUIET_PERIOD_NANOS
    ): Boolean = opcode in COMMAND_OPCODES && idleNanos >= quietPeriodNanos
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
