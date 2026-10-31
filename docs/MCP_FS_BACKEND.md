# MCP FsList / FsRead 后端（DEV05）

Task16/17 沿用现有 DTO、严格 JSON 解析、`Successful` 语义和每任务 `app_process` 通道。`HelperMain` 注册 Exec、FsList、FsRead；文件处理器只产生 RESULT 帧。`McpFsBackend` 尚未接 Runtime，也未修改旧 unsupported 清单、旧 FileManager 或 Host.version。高权限启动器由 DEV06 接入，所有文件操作只发生在所选 helper 的实际 UID 下。

## 路径与 fd

`McpRequestRules` 在绝对路径/NUL 校验后调用纯词法 `McpFsPath.cleanAbsolute`，规则与 Linux `filepath.Clean` 一致：合并斜杠、消去 `.` 和 `..`、移除非根路径的尾随斜杠；保留段内空格、换行、中文和反斜杠。不调用 canonicalPath。尾随 `/` 会使 Linux 在 nofollow 前解析最终目录软链接，因此清理必须先于 open。

FsRead 用公开 API21 的 `Os.open` 打开，flags 为 `O_RDONLY | O_NOFOLLOW | O_NONBLOCK` 加下述经 UAPI 核验的原子 close-on-exec flag，随后对同一个 fd `fstat`，只允许普通文件；`pread` 不重新打开路径。FIFO、socket、device、目录和最终软链接明确拒绝。这里的 nofollow 只保护最终路径分量，不拒绝祖先目录的软链接，也不引入文件系统沙箱；可访问范围由 helper UID 和 Android 权限决定。

FsList 在 API26+ 先用目录 flag 打开并 `fstat` 校验目录，再通过 `ParcelFileDescriptor.dup` 的 fd 构造 `/proc/self/fd/<fd>` 锚点，核对 dev/ino 后用 `Files.newDirectoryStream` 流式枚举。child `lstat`、`readlink` 也使用这个锚点；原路径在 open 后改名或替换不会切换目标。stream、PFD 和原始 fd 均有独立唯一 owner，关闭和转移失败会释放已获得的资源。无法解析锚点返回 `directory_fd_anchor_unavailable`。

API23–25 的 FsList 返回 `bounded_directory_enumeration_unavailable`；NIO26 实现在独立版本类中，经 SDK guard 后才进入。FsRead 不依赖 API26 Base64/NIO，不新增 JNI 或 native 依赖。

## open flag 来源与最低 API

SDK35 的公开 `OsConstants` 不包含 `O_DIRECTORY`；公开 `O_CLOEXEC` 字段从 API27 才可用。实现只依据 API21 公开的 `O_NOFOLLOW` 匹配本机已核验的 NDK `27.3.13750724` Linux UAPI，在 open 时原子设置 close-on-exec，不反射隐藏常量：

| UAPI | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC | 头文件 |
| --- | ---: | ---: | ---: | --- |
| ARM / ARM64 | 16384 (`040000`) | 32768 (`0100000`) | 524288 (`02000000`) | `arm-linux-androideabi/asm/fcntl.h`、`aarch64-linux-android/asm/fcntl.h` 第 9–13 行，并继承 `asm-generic/fcntl.h` 第 55–56 行 |
| x86 / x86_64 | 65536 (`00200000`) | 131072 (`00400000`) | 524288 (`02000000`) | `i686-linux-android/asm/fcntl.h`、`x86_64-linux-android/asm/fcntl.h` 第 7 行继承 `asm-generic/fcntl.h` 第 46–56 行 |

头文件根目录为 NDK 的 `toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/include`。ARM64 采用 ARM override，不能误用 65536。未知的公开 nofollow 值在打开前返回 `directory_open_flags_unavailable` 或 `file_open_flags_unavailable`。两种映射和未知值都有纯 JVM 测试。

最低 API 还核对了 SDK35 的 `data/api-versions.xml`：`OsConstants` 类自 API21 公开（第 57567 行），`FD_CLOEXEC`、`F_SETFD` 字段无额外 since、继承 API21（第 57734、57745 行）；但 `Os.fcntlInt` 明确为 API30（第 57475 行），所以不能用它给 minSdk23 的 fd 补设置。`O_CLOEXEC` 字段明确为 API27（第 57846 行）。实现不引用这两个较新的公开符号，也不抑制 lint；原子 flag 不增加 open 后的配置失败或 exec 竞争窗口。目录原始 fd 到独立 anchor 的交接抽为可注入的小函数，测试覆盖 pin 失败仍关闭原始 fd、原始 close 失败仍关闭 anchor，以及 anchor close 再失败不掩盖主错误。

## 限额和结果

文件任务默认总时间预算集中为 `McpLimits.DEFAULT_FILE_TIMEOUT_SECONDS = 30`，包含父端解析、启动和握手。helper 按单调 deadline 持续检查；可能卡住的原生 IO、目录流或 close 由独立 helper watchdog、控制 EOF 和父端 lease 回收处理，不依赖 coroutine timeout 硬中断普通文件 IO。

FsList 隐藏过滤后最多保留 5000 项，继续流式计数但不再读取后续元数据。`total` 是完整的过滤后枚举数，超出保留量则 `truncated=true`。child 元数据来自 lstat，权限字符串等价于 Go `%#o` 的 `Mode().Perm()`，mtime 为 Unix 秒；软链接包含自身大小和目标原文，不递归。

每个待保留 entry 与最终编码器共用 JsonWriter 字段写法，逐项计量实际转义后的 UTF-8 字节；计量不保留 JSON，也不重新编码已收集列表。保留项合计最多 `3 MiB - 512` 字节，另留固定 envelope/total 余量；最终编码器继续校验完整 Protobuf 的 3 MiB 上限。若计量、目录枚举、lstat/readlink 或期限失败，返回同族结构化 error，丢弃 partial entries，不提供尚未数完的 total。

FsRead 的默认/零 length 为 1 MiB，正值超限限幅，负值业务拒绝；offset 全程精确 Long。只读取返回段，按块 pread，位置加法受 signed off_t 上限约束。`size` 与实际 SHA-256 均来自返回段原始字节；UTF-8 采用与 Exec 相同的 replacement 策略，Base64 为标准字母表、带 padding、无换行。空文件、EOF 和 EOF 外偏移均返回空段的实际 SHA-256。`truncated` 用一次 fstat 的固定大小和安全减法判断，文件并发变化不会触发第二次 stat。

权限、路径不存在、最终软链接、非目录、非普通文件、IO、超时和能力问题只返回固定错误消息，不拼接用户路径或平台异常。父端在分配终态 payload 前强制 `reserveResult`；固定错误 fallback 也先预留 1024 字节。`OwnedMcpResult` 将预算持有到最终发送或丢弃。

## 与固定 Go 参考的差异与验证边界

- 保留字段名、默认值、5000/1 MiB 限额、原始段 hash 和隐藏过滤计数。JSON/类型解析失败是 `Successful=false`；业务拒绝、IO 和能力错误是 `Successful=true + error`。
- Android 对负 length 明确拒绝；UTF-8 使用与 Exec 一致的替换规则；错误不泄露路径。目录 metadata/readlink 失败明确返回 error，避免 Go 参考中跳过失败项后看似成功。
- FsList 额外限制编码字节、总时间和 API 可用性。未完成枚举不返回虚构 total。对零 total/false truncated 继续使用既有 encoder 的省略规则。
- fd 校验与锚点操作补齐路径替换保护；不会在父 App 以低权限 File 另做回退，也不尝试切换后端。

JVM 机制测试覆盖 lazy 超 5000 项、隐藏过滤、特殊文件名、symlink 元数据、逐项字节预算、期限内外、异常关闭、非普通 fd 拒绝且不 read、分段/空/EOF 外/精确 Long 偏移、Unicode 截断、Base64/hash、打开句柄后文件名替换、API23–25 guard、ABI flag、权限拒绝不启动、RESULT 帧和预算释放。测试不假调用 Android Os、不放宽 Android stub，也不引入 Robolectric/mock 库。

主代理统一运行 Gradle、release/R8、lint、双审查和真实设备验证。真实 Android 必须检查目录改名后的锚点、文件/目录最终软链接（含 `/`、`/.`）、FIFO 非阻塞后拒绝、设备权限错误、5000 截断与完整 total、巨大 target 的编码预算、1 MiB 分段 hash/Base64、EOF 外偏移、debug/R8 的文件 RESULT、取消/超时后的 fd/helper 回收。API23–25 和高权限后端的设备证据仍需单独记录。
