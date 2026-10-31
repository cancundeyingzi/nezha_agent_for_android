# R1 实施与验证记录

用户于 2026-10-31 批准本轮实施计划。范围为必要稳定性修复、Task15 Exec、Task16 FsList、Task17 FsRead，以及 GPU 新旧协议兼容。Task12–14、18–20 继续明确返回 unsupported。

## 固定基线

- Android：`b8e7b20beca115301dab12d2cf226734c6287949`，`A0.10.1 / 385`。
- 候选：`2920716ee7ace6bd134050bc2eca15c66f526fbf`，当前引用为 `origin/review-fixes`。本地旧 `review-fixes` 不作整合依据。
- Go Agent 协议：`c2b65ab6fa8926f3c7ed4ef3a0065fe003410237`。后续上游变化只作差异提示。
- 开发分支：`codex/mcp-r1`。候选按行为整合，禁止覆盖 master 已有采集改进。

## DEV01 基线复现

2026-10-31 在独立的固定提交检出中执行，避免开发中的改动污染结果。

| 条件/检查 | 当前结果 |
| --- | --- |
| JDK | Amazon Corretto 17.0.13 |
| Gradle | Wrapper 对应的 8.7，离线使用现有缓存 |
| Android | compile/target 35，minSdk 23，SDK 位于本机 `D:\Android-sdk` |
| `:core:test` | 22 个测试，0 失败、0 错误、0 跳过 |
| `:app:testDebugUnitTest` | 228 个测试，0 失败、0 错误、0 跳过 |
| `:app:assembleRelease` | 通过，包含 R8；输出未签名 APK |
| `:app:lintRelease` | 通过；保留默认错误阻断，不采用候选的 `abortOnError=false` |
| ADB 设备 | 已创建独立 API36 / 16 KiB 页模拟器，serial `emulator-5562`；用户另有 Android15/17 设备，可手动测试 |
| Dashboard 版本与提交 | 用户要求最新版；2026-10-31 官方最新版为 v2.6.1，提交 `3e7d9ba60361e7968db2b029f28afc7cf32dd69e`；已准备仅回环监听的独立本地测试副本 |
| 原发行签名与旧 APK 覆盖安装 | 待核验 |

基线通过只证明上述固定源码在本机构建及 JVM 测试通过，不代表候选代码、真实 Android 后端或面板联调已通过。

## 工作包与完成条件

| 工作包 | 内容 | 状态 |
| --- | --- | --- |
| DEV01 | 基线、协议快照、后端方案与测试条件 | 进行中 |
| DEV02 | 必要候选修复、严格 HTTP 证书探测、脱敏 | 实现、两轮审查及本地检查通过；设备回归待后续 |
| DEV03 | 类型化 DTO、严格解析、结果语义、权限、配额与字节预算 | 实现、两轮审查、39/310 单测及 release/lint 通过；尚未接入真实任务 |
| DEV04 | 参数数组、独立输出、stdin、超时取消和有界收尾 | 实现、双审查、39/356 单测及 release/lint 通过；真实 R8 helper 已验证，尚未接 Runtime |
| DEV05 | 有界目录枚举、普通文件分段读取和 fd 校验 | 进行中 |
| DEV06 | Root/Shizuku 适配与明确能力错误 | 待实施 |
| DEV07 | State.gpus=18、旧 gpu=17 同采样映射 | 待实施 |
| DEV08 | 单测、lint 门禁、release、设备/面板/稳定性验证 | 待实施 |

## 本轮必须落实的边界

1. 路由与执行前均检查 SHELL/FILE_MANAGER；撤权取消新增活动 MCP 任务，旧终端/NAT/FM 继续沿用原兼容规则。
2. 普通、Root、Shizuku 使用现有授权与后端选择；待授权、服务失效、撤权不能隐式切换到 su。
3. Exec 不进入共享采样 shell，保留独立 stdout/stderr 与真实退出码；正常、失败、超时和取消均有限收尾。
4. 文件读取打开时使用 nofollow/非阻塞保护，并对实际 fd 校验普通文件类型。目录枚举同时限制保留内存、字节量和时间；API23–25 兼容路径必须明确验证或返回能力错误。
5. 请求、队列累计字节、输出保留和最终 Protobuf 消息分别受限；JSON 转义及 Base64 膨胀纳入预算。
6. 所有任务与结果归属当前连接会话；旧任务不向替换后的连接写入，不自动重放 Exec。
7. 新旧 GPU 字段从同一采样生成，显存未知值保持未知。
8. 最新 Dashboard v2.6.1 在调用 MCP 前以 `MCPMinAgentVersion=v2.1.0` 检查 `Host.version`；原 `A0.10.1` 会被判为旧版本。R1 必须显式区分协议兼容版本与 Android 应用版本，保留 Android 身份及应用版本，文档说明局部任务支持范围，不能仅实现处理器后仍被面板入口拒绝。

## 后端方案冻结

新增 MCP 采用每任务一个短命 `app_process` helper，由当前 Runtime/任务流拥有，不新增 Service、常驻 Agent 或配置仓库。helper 内建立并校验独立 session/process group，在实际执行 UID 下完成普通文件 fd 校验和参数数组执行。控制通道 EOF、单调期限及最终结果后的进程组清理共同回收任务；启动器 stdout/stderr 不与用户命令输出混淆。

该方案避免复用会合并输出的旧 `RootShell.executeIsolated`，同时把可能阻塞的文件系统调用隔离出应用进程。Root/Shizuku 仍通过既有授权与固定后端边界启动；helper 握手、UID 或独立会话校验失败返回明确能力错误。

API26+ 的 FsList 使用已打开目录 fd 的 `/proc/self/fd` 锚定路径进行流式枚举。API23–25 缺少公开的有界目录枚举接口，本轮不引入 JNI，FsList 明确返回能力错误；Exec 和 FsRead 仍保留 minSdk23 实现路径。未经过设备验证的后端不能写为已验证支持。

## 候选整合决策

| 候选 | 处理要求 |
| --- | --- |
| 0f21921 | 采用任务流独立恢复、取消等待、退避抖动；健康状态流和已有长会话不受闲置超时影响 |
| 636c923 TCP/NAT | 按块整合域名、IPv4、括号 IPv6 解析 |
| 636c923 上传 | 不采用静默窗口恢复启发式；拒绝或写入失败后中止当前文件会话，残包持续丢弃，要求重新建流 |
| 636c923 HTTP | 采用默认严格验签；证书错误直接失败，不自动使用不安全客户端重试 |
| 0ec059b | 按需采用脱敏，不记录完整命令、env、stdin、文件正文或凭据 |
| 0bdaee3 / 2920716 | 采用异步配置就绪与 UI I/O 修复，读取失败能退出加载状态 |
| a0e9fee | 不覆盖当前 CPU、流量来源切换、计数器、电池及 GPU 缓存逻辑 |
| 6ff5330 | 不顺带升级依赖；以实际存量基线建立阻断 lint 门禁 |

## 发布验收待办

- 确认 Dashboard 版本/提交、消息限额及协议 fixture 往返结果。
- 记录 API23–25、API26+、较新 Android、普通/Root/Shizuku 授权与撤权设备结果。
- 至少 24 小时后台运行、100 次受控连接重建与任务取消循环，并记录 PSS/fd/任务/进程趋势。
- 原签名覆盖安装、UUID/配置/权限保留与递增 versionCode 修复版本恢复。

这些条目必须有真实证据才能勾选；本地测试构建不能替代正式发布验收。

## DEV02 本地验证

2026-10-31，修复两轮审查发现的问题后重新执行：

- core：30 个测试通过，0 失败、错误或跳过。
- app：284 个 JVM 测试通过，0 失败、错误或跳过。
- `:app:assembleRelease :app:lintRelease`：通过，包含 R8 路径。
- 需求符合性审查和代码质量审查：通过。曾发现的上传最终保存失败状态、NAT 错误反馈、清理异常覆盖取消、初始化失败后重置恢复、异步粘贴与启动竞态均已修复并补回归。
- 真实 TLS 测试使用标准 `SSLServerSocket`，不新增测试依赖、不放宽未模拟 Android API 的单测模式。
- 本地最新版 Dashboard `v2.6.1` 的 MCP initialize 已通过；只读临时测试令牌在同一次验证中撤销，JWT/PAT 未写入文件。

## 设备预检与签名条件

- 独立 API36 / Android16 模拟器（16 KiB 系统页）已安装固定 DEV02 调试 APK，冷启动配置页面可用，加载状态能够退出；本次启动未产生 AndroidRuntime 崩溃日志。
- 在同一测试应用 UID 下运行最小 `app_process` 预检，真实与有效 UID 均为 10213，`Os.setsid()` 返回值等于自身 PID，向自有进程组发送信号 0 的权限验证通过。这是后端机制预检，不代表完整 MCP helper、Root 或 Shizuku 已验证。
- 上一正式 APK `0.10.1` 已核验为同包名 `com.nezhahq.agent`、versionCode 385、minSdk23、targetSdk35。发行证书 CN 为 `ccdyz`，SHA-256 为 `fb9e852d94adac85b299bb6ca560fde92f45f075feeade5b5c0bea929a559957`。
- 本机调试证书 CN 为 `Android Debug`，与正式发行证书不同；项目内未找到发行 keystore。当前尚不能证明正式签名可用，也不能宣称调试 APK 可覆盖正式安装并保留配置。
- 独立面板已登记此模拟器为测试节点 1，现有 Host.version 为 `A0.10.1`；仅用于本地验证，不使用生产节点。
- 官方 Shizuku v13.6.0 已仅在隔离模拟器安装并按管理器显示的原生 starter 命令启动；尚未授权给 Agent，也未把它记为 MCP 适配通过。
- 文件 fd 方案预检通过：目录改名后 `/proc/self/fd` 锚点仍枚举原目录，换行/中文名保持完整；同 fd `pread` 读取 17 原始字节，EOF 外读取返回 0；FIFO 能非阻塞打开后按类型拒绝；最终软链接由 nofollow 拒绝。
- 预检还确认两个需在 Fs 后端落实的细节：尾随 `/` 会使最终目录软链接先被解析，打开前必须按上游词法清理路径；SDK35 未公开 `OsConstants.O_DIRECTORY`。已从安装的 NDK27 UAPI 核验 ARM/ARM64 的目录/nofollow 值为 16384/32768、x86/x86_64 的值为 65536/131072，可按公开 nofollow 常量匹配经验证的目录 flag，未知 ABI 明确能力错误，不用隐藏 API 或 JNI。

## DEV03 本地验证

- 冻结树的 core 39 个、app 310 个单测全部通过（相较 DEV02 新增 9/26 个）。
- `:app:assembleRelease :app:lintRelease` 通过。新解析器/编码器未接运行时，不能用此时 R8 输出测量实际依赖体积成本。
- 需求与代码质量审查通过；Gson 严格模式残留、1024 字符数字缓冲限制、原始请求计费和最终 Protobuf 字节预算均有明确适配与回归。
- 实际支持开关及旧 unsupported 清单保持原状，直到后续任务处理器接线后才对外启用。

## DEV04 本地与设备验证

- 冻结树的 core 39 个、app 356 个单测全部通过；`assembleRelease`（R8）与 `lintRelease` 通过。
- 需求和质量复审通过。修复并覆盖启动 timeout 的 lease 交接、旧 PID/PGID 误用、取消同步 flush 阻塞等问题。全局 4 槽进程/清理 ticket 在异步关闭真正完成前不释放，容量耗尽不再启动新进程。
- 原 5 项最小启动环境在 API36 无法引导 VM；有限复制平台 BOOTCLASSPATH、DEX2OATBOOTCLASSPATH、ART/I18N/TZDATA root 及存在时的 ANDROID_RUNTIME_ROOT 后，真实 helper 可启动。目标用户命令不继承这些引导环境或其他父环境凭据。
- API36 / Android16 / 16 KiB 页上，实际 R8 release 代码通过：直接 argv 元字符边界、NUL/换行 stdin 与 EOF、两路输出限额及持续排空、启动失败、cwd 失败、目标环境隔离、正常退出后持 fd 后代回收、超时保留输出、目标 stdin 堵塞时控制 EOF 回收，以及预先独立 session 的 EPERM 身份确认。
- 真实 UID/eUID/proc UID 一致，PID/pgrp/SID 一致；protobuf 高位任务 ID `9223372036854775825` 无损，用户 exit 7 与 helper 外层 137 正确分开。R8 入口及无反射 DTO 路径没有被裁剪破坏。
- Windows adb.exe 文本 stdout 会改变换行；设备测试改用按 AOSP shell v2 规范实现的原始二进制连接，确认帧与 NUL/换行字节保真。这是测试传输适配，不改变产品内部帧协议。
- 此时仍没有 Task15 的 Runtime/gRPC 接线；以上证明独立后端与 helper，不能替代最终面板往返测试。Root/Shizuku 后端接入仍属 DEV06。
