# R1 任务级 helper 与 Exec 后端（DEV04）

此包提供可独立调用的普通应用 Exec 后端，尚未注册到 `AgentRuntime`，不修改任务支持清单、Host.version、配置或 UI。每个任务启动一个短命 `/system/bin/app_process`，CLASSPATH 指向当前安装 APK，普通模式使用应用 UID；没有 Service、常驻代理和自动重试。

## 调用与所有权

```kotlin
class McpExecBackend(launcher: HelperLauncher, /* 可注入 transport/encoder/clock */) {
    suspend fun execute(
        task: Task,
        capabilities: RemoteCapabilities,
        deadline: HelperDeadline? = null,
        reserveResult: suspend (serializedBytes: Long) -> AutoCloseable
    ): OwnedMcpResult
}

class OwnedMcpResult(val result: TaskResult, reservation: AutoCloseable) : AutoCloseable
```

调用方传入当前权限快照。后端在启动前执行权限检查、严格解析、`McpRequestRules.prepare` 和进程参数检查；权限拒绝、业务参数错误和解析错误继续使用现有 encoder 的语义。调用方仍负责入口和执行前的两次实时权限检查、`McpTaskAdmission`、明确选择后端、任务取消以及最终发送/丢弃。后端不会主动申请 Root/Shizuku 权限。

调用方必须把 `OwnedMcpResult` 本身保留到最终发送或丢弃之后，再 `close()`；关闭幂等。不要在中间流中只取出 `result` 就释放 lease，也不要添加会提前接收多份结果的默认 64 槽缓冲。取消以 `CancellationException` 继续传播，清理异常不会覆盖取消。

`reserveResult` 在读取 RESULT 的有界帧头后，**读取/分配其 payload 之前**按声明的 protobuf 字节数调用。等待预算不占用阻塞 IO 线程；预算拒绝直接传播，不重试。回调自身须正确处理“取得预算同时取消”的所有权竞争。传输成功后检查返回体 id/type 的完整 Long 位模式、实际 protobuf 大小与帧长度，且最终 envelope 不超过 3 MiB。

握手失败、超时或传输失败没有可用的完整终态时，Exec 后端先预留 3 MiB 硬上限，再将已收到的原始输出转换为 UTF-8、编码最终结果。纯解析/权限错误只预留 1024 字节。正常结果使用精确声明大小；较小 fallback 可能暂时持有保守预算。应用内渐进输出每路另有 1 MiB 原始字节上限，受任务并发配额约束，不会先制造多份未计费的 3 MiB JSON。

## DEV06 启动器边界

```kotlin
interface HelperLauncher {
    val backend: HelperBackend // APPLICATION / ROOT / SHIZUKU
    suspend fun launch(spec: HelperLaunchSpec): HelperProcessLease
}
data class HelperLaunchSpec(val nonce: ByteArray, val lifetimeMillis: Int)

interface HelperProcessLease : AutoCloseable {
    val backend: HelperBackend
    val expectedUid: Int
    val stdout: InputStream
    val stdin: OutputStream
    val stderr: InputStream
    fun acceptIdentity(hello: HelperProtocol.Hello)
    override fun close()
}
```

nonce 由程序用 SecureRandom 生成。安装 APK 路径、普通模式 files/cache 目录和有限生命周期参数来自本地应用；远端 cmd/args/env/cwd/stdin 不进入启动脚本或 argv。普通启动器直接使用 ProcessBuilder，不经过 shell。

共享的 `HelperBootstrapEnvironment.create(apkPath, temporaryPath, platformEnvironment)` 只用于 helper VM 引导，可供后续高权限启动器复用。环境先清空并固定 CLASSPATH、PATH、ANDROID_ROOT、ANDROID_DATA、TMPDIR，再从本地平台环境复制 BOOTCLASSPATH、DEX2OATBOOTCLASSPATH、ANDROID_ART_ROOT、ANDROID_I18N_ROOT、ANDROID_TZDATA_ROOT；旧平台的 ANDROID_RUNTIME_ROOT 有值才复制。其他变量（包括 HOME、凭据、LD_PRELOAD、JAVA_TOOL_OPTIONS 及旧 CLASSPATH）不透传。该接口不接 Task.data 或请求 env，目标 Exec 环境仍由独立 `ExecEnvironment` 重建。

DEV06 须为所选后端提供实际 expected UID、独立 stdout/stderr 和可撤销的进程/授权登记 lease；`acceptIdentity` 在握手通过后登记自有进程组。`close()` 必须幂等、有界地撤销回调、终止该组并关闭流。授权/启动等待在调用方整体 deadline 内，取消后才取得的进程也须由启动器立即撤销。只销毁 su/远端包装 Process 或调用合并输出的 `RootShell.executeIsolated` 不满足该接口。Root/Shizuku 失败返回对应能力错误，不换为普通应用模式。

父端在 deadline 块内部、`launch` 返回后立刻登记 lease，之后才跨越 `withTimeoutOrNull` 的取消返回边界；启动线程的 continuation 另有 abandoned 释放回调。两者分别覆盖原生启动完成但 continuation 被取消，以及内层成功返回后外层 timeout 丢弃返回值的交接。其他 deadline 操作仅返回普通帧字节/元数据，不传递需要 close 的资源；结果预算不经过该 timeout 包装，回调自身的取得/取消竞争仍由回调负责。

普通模式通过 `ApplicationHelperLease.start` 在原生启动之前取得 `HelperProcessResources.cleanup` 的全局 ticket，最多保留 4 个正在启动、运行或清理的进程。无 ticket 则返回 busy，原生 launch 不执行。尚未启动就失败时立即释放；已取得进程后，无论正常完成、取消、迟返回或异常，都把 ticket 交给 lease 的唯一清理所有者。DEV06 同样需要维持这一进程资源所有权，不能在异步关闭尚未结束时释放容量。

helper 在接用户请求前先调用公开 `Os.setsid()`，验证返回 SID 等于真实 PID，并验证 `kill(-pid, 0)`。仅在 setsid 报 EPERM 时，才允许启动器已经建立独立 session：有界读取最多 4096 字节 `/proc/self/stat`，按 comm 最后一个 `)` 后的字段定位，严格核对文件 PID、pgrp、sid 均为自身 PID。其他失败不接用户请求。无需隐藏 getsid/getpgid API、Process.pid 反射或 JNI，minSdk 23。

## 内部帧协议 v1

所有多字节整数采用 big endian。固定 9 字节帧头为：`magic:int32 = 0x4e5a4d48 (NZMH)`、`kind:uint8`、`length:int32`。先验证 magic、允许的类型和长度区间，再分配 payload；负长度、超长、时序错误立即拒绝。每条管道只有一个读/写所有者。

| kind | 名称 | payload |
| --- | --- | --- |
| 1 | HELLO | 固定 52 B：version:int32、nonce:32 B、uid/euid/pid/sid 各 int32 |
| 2 | REQUEST | id:int64、type:int64、remaining_ms:int32、elapsed_ms:int32，后接原始 Task.data UTF-8（最多 1 MiB）；无 JSON 二次转义 |
| 3 | STDOUT | 1..8192 B 已保留的原始 stdout 数据 |
| 4 | STDERR | 1..8192 B 已保留的原始 stderr 数据 |
| 5 | TRUNCATED | 固定 1 B，1 表示 stdout，2 表示 stderr |
| 6 | RESULT | 1..3 MiB 的完整 protobuf TaskResult；同时为后续 Fs 结果提供传输 |
| 7 | UNAVAILABLE | 0 B，仅在不能建立 helper 隔离时使用 |

应用端先接 HELLO，验证版本、全部 nonce、真实/有效 UID 均等于启动器 expected UID、PID > 1、SID == PID；通过后才发送唯一 REQUEST。helper 重新运行严格 parser 和 prepare。REQUEST 之后应用端保持控制 stdin 开放，不再发送字节；EOF、额外字节或读取失败都触发组清理。目标 stdin 由独立 writer 写入请求中的字符串后关闭，空字符串也关闭。目标不读 stdin 不会卡住控制 EOF 或 watchdog。

helper 的协议 stdout 使用独立 FileDescriptor writer；System.out/err 的偶然打印被丢弃。目标进程使用自己的两个 ProcessBuilder 管道，输出不会被识别为协议帧。启动器 stderr 由独立线程最多排空 64 KiB 后关闭，不保留、不写日志。应用阻塞桥的 worker 和队列均有限（启动 2/2、协议 4/4、诊断 4/4），另有专用 4 worker/4 槽清理池；transport 实例最多接受 4 个会话，进程资源另受全局 4 ticket 限制。结果预算等待期间没有阻塞 IO 任务。

## Exec 行为与清理

ProcessBuilder 接收 `cmd + args` 原始列表，`redirectErrorStream(false)`；仅用户显式请求 shell（如 cmd=sh、args=-c）时才由用户选择的 shell 解释语法。cwd 在 helper 中验证为可读、可搜索目录，缺省为普通应用 files 目录；相对路径以该目录为基准。cmd、args、cwd、环境值拒绝 NUL，env 键使用 `[A-Za-z_][A-Za-z0-9_]*`；stdin 可含 NUL。错误仅报告稳定字段原因，不回显值。

目标环境先清空，再加入固定 Android PATH、ANDROID_ROOT、ANDROID_DATA、本地 TMPDIR，最后应用显式 env。不继承应用凭据、CLASSPATH、HOME 或其他环境。文件/目录访问只发生在隔离 helper；后续 Fs 同样使用该隔离层，不能把普通阻塞文件 IO 的 withTimeout 当成硬中断。

两路输出并发读取、分别默认保留 64 KiB，最大各 1 MiB，达到限额后仍继续排空。helper 主线程是唯一帧 writer，两个输出 reader 通过 4 槽、每块至多 8192 B 的队列提交数据。两端都在最后按同一 UTF-8 replacement 策略解码原始字节。主线程同时轮询真实进程退出与输出事件；主进程退出后最多给 350 ms 尾流时间，后代持有 fd 不会钉住完成。尾流未结束的流标记截断。

timeout 默认 30 s，最大 300 s；使用单调时钟。父端整体 deadline 包含解析、启动、握手和未来授权等待；helper 使用剩余预算，并有独立的“启动预算 + 2 s”最终 watchdog，覆盖隔离检查、解析、文件 IO、stdin、输出和终态写入。终态帧头已到达后的 payload 传输另有 2 s 界限，传输超时是业务传输错误，不伪称用户命令超时。

真实非零用户退出仍是正常结果，`successful=true`。用户主动提前关闭 stdin 产生的 broken pipe 不替代真实退出码。超时带 `timed_out=true` 和已收到输出，不额外强制 error；启动/能力/传输/其他 IO 错误通过现有 encoder 返回 `true + error`。helper 正常完成先完整写入并 flush TaskResult，再对自身已验证进程组发 SIGKILL，因此 helper 的外层 137 不是用户 exit_code。父端取消/超时/异常时关闭控制并回收活跃的自有组，没有命令重试。

普通 lease 接受握手时同时记录有界 `/proc/$pid/stat` 的 PID/pgrp/sid/starttime 和 proc 目录 UID。收尾先调用 API 23 已公开的 `Process.exitValue()`：已退出时绝不再对缓存 PID 发组信号或 destroy。仍活跃时重新核对 UID、PID、pgrp、sid、starttime 与非僵尸状态，紧邻 `Os.kill(-pid, SIGKILL)` 前再检查一次退出状态。若进程已退出、身份变化或状态不可验证，只异步关闭原有控制/输出管道，避免把旧整数 PID 当成当前所有权。活跃但不能安全复核/发信号时，由原控制 EOF 和独立 watchdog 驱动 helper 自清理，不通过 destroy 提前杀死已接受请求的 helper。

`close()` 不等待 BufferedOutputStream flush、写锁或管道关闭。组信号之后，三路管道关闭，以及尚未接受任何请求时可能伴随关闭管道的 `Process.destroy()`，都交给专用清理池。ticket 一直保留到三路关闭尝试完成；被写锁卡住的任务持续占用容量，容量耗尽时后续启动 busy，不会随着取消/重连无限积压线程、关闭任务或进程。清理调度异常也不提前释放 ticket。进程正常自杀或独立 watchdog 终止后会让原生管道解除阻塞，清理完成后再恢复容量。

API 23 没有这里可用的公开 pidfd 原子进程句柄；`exitValue`、proc 启动标识复核与信号之间仍存在检查间隙。这些保护避免对长时间预算等待后已退出的 helper 盲发旧 PGID，不宣称检查与信号已经原子化。DEV06 也必须保持活跃身份的有效性，不能无条件重用缓存数值 PGID。

所有这些清理只覆盖自有 session/process group。主动 setsid、迁出组、UID 改变或额外权限导致信号不可达的后代属于平台限制，不承诺将其全部回收。

## 验证边界

纯 JVM 测试覆盖非法类型/长度、nonce/UID/eUID/session 拒绝且不发请求、原始 JSON/Long 位模式、终态分配前预算、预算拒绝与取消、reservation 重复释放、任务 deadline、启动 lease 在 timeout/取消返回边界的交接、已退出/已复用/身份变化 PID 不发信号、活跃组信号与管道关闭顺序、close/accept 交接、REQUEST 持有 BufferedOutputStream 写锁时身份不可复核/信号失败仍及时返回、清理 ticket 持有与容量耗尽不启动/解除阻塞后恢复、原生启动失败释放 ticket、bootstrap 白名单及拒绝其他环境变量、目标不继承 bootstrap、阻塞控制/输出、非零退出/启动失败、独立双路输出、超额继续排空、空/NUL stdin EOF、不读 stdin、提前关闭 stdin、后代持 fd 的固定尾限、UTF-8 替换和正常终态先于清理。真实 JVM 子进程无需 Robolectric/mock 框架；没有放宽 Android stub 的严格行为。

主代理已在 API 36 / 16 KiB 模拟器验证真实 HelperMain：仅固定 5 项或只额外加入 BOOTCLASSPATH 都无法得到 HELLO；上述完整平台 bootstrap 白名单能够握手，UID/eUID/proc UID 均为应用 UID，PID=pgrp=sid，双输出独立、真实用户 exit=7、外层 helper=137，64 位高位 id 原样返回。这是启动 VM 所需环境的设备证据，不将 bootstrap 变量继承给目标进程。

主代理仍需使用 release APK 做设备验证：普通 UID/eUID、父端 proc UID/starttime 读取、setsid 和 EPERM 已独立 session 的回退、Android 目标 argv/环境/cwd、控制 EOF 与阻塞 stdin 的独立性、timeout/取消后的整组回收、后代持 fd 的尾限、已退出 helper 在结果预算等待后不再发组信号、终态外层 137、超大双输出以及 R8 后的精确 main 入口。此包未执行完整 Gradle/release/lint 或模拟器验证，由主代理统一进行。
