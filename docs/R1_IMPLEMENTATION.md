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
| 原发行签名与旧 APK 覆盖安装 | 原发行证书已核验；本地没有发行 keystore，正式签名覆盖验收待进行 |

基线通过只证明上述固定源码在本机构建及 JVM 测试通过，不代表候选代码、真实 Android 后端或面板联调已通过。

## 工作包与完成条件

| 工作包 | 内容 | 状态 |
| --- | --- | --- |
| DEV01 | 基线、协议快照、后端方案与测试条件 | 本地基线与来源确认完成；外部发布验收条件已记录 |
| DEV02 | 必要候选修复、严格 HTTP 证书探测、脱敏 | 实现、两轮审查及本地检查通过；设备回归待后续 |
| DEV03 | 类型化 DTO、严格解析、结果语义、权限、配额与字节预算 | 实现、两轮审查、39/310 单测及 release/lint 通过；尚未接入真实任务 |
| DEV04 | 参数数组、独立输出、stdin、超时取消和有界收尾 | 实现、双审查、39/356 单测及 release/lint 通过；真实 R8 helper 已验证，尚未接 Runtime |
| DEV05 | 有界目录枚举、普通文件分段读取和 fd 校验 | 实现、双审查及构建检查通过；API36 debug/R8各25例、API23读/能力边界通过 |
| DEV06 | Root/Shizuku 适配与明确能力错误 | 实现及需求/质量复审通过；普通/高权 helper 已设备验证，真实 SukiSU 授权链待手测 |
| DEV07 | State.gpus=18、旧 gpu=17 同采样映射 | 实现、需求/质量审查、40/436 单测及 release/R8/lint 通过 |
| DEV08 | Runtime、门禁、测试包及设备/面板/稳定性验证 | 本地开发验收完成：40/470 单测、R8/lint、MCP/API23/100轮及测试包通过；真机/24h/原签名发布验收待完成 |

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
- Dashboard 固定提交的 `cmd/dashboard/rpc/rpc.go` 未覆盖 gRPC 消息大小；其 `go.mod` 固定 grpc-go v1.83.2，`server.go` 的默认接收消息上限为 4 MiB。本轮 3 MiB 上限针对完整序列化 TaskResult，留在该接收上限内；不把 JSON 字符数或原始输出字节数当作最终消息大小。

## DEV04 本地与设备验证

- 冻结树的 core 39 个、app 356 个单测全部通过；`assembleRelease`（R8）与 `lintRelease` 通过。
- 需求和质量复审通过。修复并覆盖启动 timeout 的 lease 交接、旧 PID/PGID 误用、取消同步 flush 阻塞等问题。全局 4 槽进程/清理 ticket 在异步关闭真正完成前不释放，容量耗尽不再启动新进程。
- 原 5 项最小启动环境在 API36 无法引导 VM；有限复制平台 BOOTCLASSPATH、DEX2OATBOOTCLASSPATH、ART/I18N/TZDATA root 及存在时的 ANDROID_RUNTIME_ROOT 后，真实 helper 可启动。目标用户命令不继承这些引导环境或其他父环境凭据。
- API36 / Android16 / 16 KiB 页上，实际 R8 release 代码通过：直接 argv 元字符边界、NUL/换行 stdin 与 EOF、两路输出限额及持续排空、启动失败、cwd 失败、目标环境隔离、正常退出后持 fd 后代回收、超时保留输出、目标 stdin 堵塞时控制 EOF 回收，以及预先独立 session 的 EPERM 身份确认。
- 真实 UID/eUID/proc UID 一致，PID/pgrp/SID 一致；protobuf 高位任务 ID `9223372036854775825` 无损，用户 exit 7 与 helper 外层 137 正确分开。R8 入口及无反射 DTO 路径没有被裁剪破坏。
- Windows adb.exe 文本 stdout 会改变换行；设备测试改用按 AOSP shell v2 规范实现的原始二进制连接，确认帧与 NUL/换行字节保真。这是测试传输适配，不改变产品内部帧协议。
- 此时仍没有 Task15 的 Runtime/gRPC 接线；以上证明独立后端与 helper，不能替代最终面板往返测试。Root/Shizuku 后端接入仍属 DEV06。

## DEV05 本地与设备验证

- 28 项初版/兼容修正测试及后续两项清理异常组合测试通过；工作包全量 core/app 测试通过，最后的 owner 变更又执行受影响测试类。debug、release/R8、lint 检查通过。
- 处理了 `O_CLOEXEC` 字段 API27、`fcntlInt` 方法 API30 的公开 API 边界；使用 NDK27 两组已验证布局的原子 UAPI flag，未知布局明确能力错误，无隐藏反射或 lint 抑制。
- 需求与质量审查通过。无效参数测试会检查零打开次数；pin/原 fd/anchor/stream 多处同时失败时保留原错误和 suppressed，不因清理改写主因。
- API36 / 16 KiB 页，debug 和实际 R8 代码各 25 例通过：空/特殊名/隐藏过滤、5011/5020 项目录截断和完整 total、长 link_target 预算错误、分段读取/Base64/段 hash、1 MiB 限额、UTF-8 边界、空与 EOF 外/精确 Long/Long.MAX 偏移、业务与解析错误、目录/FIFO/device/symlink 拒绝、末尾 `/` 和 `/.` 保护、权限错误及 JSON 膨胀后的空内容错误语义。
- 已从 Google 官方镜像建立全新的 API23 / Android6 x86 隔离设备，现有 SDK/AVD 不变。应用可安装冷启动并持续运行，无 AndroidRuntime 崩溃；debug 和 R8 的 FsRead 实际段/Base64/size/SHA256 正确，FsList 在加载 NIO26 前明确返回 `bounded_directory_enumeration_unavailable`。API23 helper Exec 也已真实通过 UID/session/高位 ID/exit7。
- API23 旧 ADB exec 不提供远端退出码；验证中不把传输 EOF 的 0 当作 helper 实际退出码，用户命令结果仍由 protobuf 内的真实 exit_code 判断。
- 用户提供的手测设备为 Android15/17，均使用 SukiSU；后续 Root 用例按该环境交付。原发行签名仍未提供。

## DEV06 本地与设备验证

- 普通模式 selector 不发现 su/Shizuku；高权按现有 RootShell 授权及固定后端策略启动。捕获 Shizuku Binder 并使用真实权限 RPC，发现失败、撤权和服务死亡均不隐式切换 su。Root 启用/服务 epoch 隔离旧任务和迟到结果。
- 高权 helper 使用保存 fd3 的固定代理脚本；真实 mksh 验证表明直接 `<&0 &` 会得到 `/dev/null`，保存 fd3 后控制输入、EOF 和代理 wait 行为成立。私有 HELLO 升为 v2/60 字节，增加 startTicks；Dashboard 协议没有改变。
- 全局四张用户资源束票与独立单槽短维护资源均保有未知退出的所有权。READY/UID 与 CLEAN 分开判定；无 HELLO 不能凭空扫描释放，本地 su 必须真实退出。失败候选轮转，旧 Root 的本地退出可由其他后端或普通模式满槽重查收敛，不新增 native 或权限请求。
- 所有本轮问题均完成需求审查及代码质量复审。完整 core/app 单测、debug、release/R8、lint 已通过；最后满槽重查又执行受影响的资源池、普通 lease、高权 lease 和维护协调器测试及 debug 构建，均通过。
- API36 / Android16 / 16 KiB 页、实际 App 授权 Shizuku UID2000：debug 与 R8 helper 各通过 UID、stdout/stderr 与 exit7、文件分段/Base64/段 hash、目录枚举以及进程授权关闭后的子进程回收。R8 此处指 helper；完整 Runtime 的 R8 路由仍需 DEV08 验证。
- 官方 Shizuku Manager `13.6.0.r1086.2650830c` 实际撤权会 force-stop 测试 App；已确认 helper、命令 shell 和 sleep 所在组无活进程。撤权后的新请求明确拒绝，重新授权后可恢复。
- 同一 App 进程启动四个高权资源束，在 HELLO/REQUEST 已接纳并实际执行命令后杀死测试 Shizuku 服务；四张未知票先保留，新服务授权后的固定维护协议按需恢复至零。测试只处理隔离模拟器与本地面板。
- API23 上实际 R8 helper 通过 UID0 固定代理执行。该模拟器原本具有 root adbd，此证据证明 Root 执行机制，不代替 SukiSU 的 App 授权链。
- Root/SukiSU 的授权与撤权、Android15/17 真机、24 小时后台以及原发行签名覆盖安装仍需真实验收。长期无法证明退出的维护槽或握手前资源可能需要真正终止并重开 App 进程；停止/启动探针服务不等于重建进程。

## DEV07 本地验证

- 保留旧 `gpu=17`，新增 `gpus=18` 及 `State_GPU`。系统采集与模拟器在原采样位置各只取一次 GPU 样本，同时映射两个字段，未知显存保留 0 MiB。
- 空样本、普通异常和整份非有限样本只省略 GPU，不阻断其他指标；取消仍传播，真实 0% 保留。没有改动 GpuCollector 缓存、采集来源或名称查询。
- 9 项新测试验证同采样、异常边界及固定旧/新 protobuf 字节，包含 packed/unpacked 旧字段和旧读取器跳过新字段；模拟器增加两个字段一致及未知显存断言。
- 需求与质量审查通过；40 项 core、436 项 app 单测全部通过，release/R8、lint 通过。独立 sandbox protoc 的 Windows DLL 启动错误没有在标准 Gradle 构建路径复现。
- 全量检查另暴露旧启动重试测试的观察竞态：onStart 发信号早于 controller 发布 current。测试改为等待同一控制器的串行操作后再断言，保留重试次数/退避序列等全部断言，并保证失败时清理；业务代码未更改。
- Android15/17 SukiSU 的 GPU 来源可读性和实际面板展示仍待手测，不以 wire fixture 代替硬件验证。

## DEV08a Runtime 接线与首轮联调

- 每次 RequestTask 注册创建独立 McpSession；15–17 在旧任务 worker 前分流，独立执行配额、请求/结果 wire 字节预算、撤权子任务和 Owned 队列。最终 collector 完成发送/丢弃后才归还结果预算，旧会话不会向新注册写 MCP 结果。
- 权限关闭取消对应活动 MCP 后端，等待清理交接后仅回一次同族拒绝；更新不等待网络或结果预算。任务流 idle 改 360 秒，同一期限覆盖 handler 背压及下一次接收；旧 producer/流内任务完成取消 join 后再重建，旧长会话作用域保持。
- 真实节点 Host.version 在上报边界使用 `2.1.0+android.<实际应用版本>`，旧 15–17 unsupported 清单同步移除；12–14、18–20 继续明确拒绝，Task4 旧语义不变。
- 需求与质量审查通过，40 项 core、457 项 app 单测、debug、release/R8、lint 全部通过。
- 本地 Dashboard v2.6.1 与 API36 / 16 KiB 页通过真实 gRPC/MCP：普通 UID、argv 元字符、独立 stdout/stderr 和 exit7、含 NUL 的 stdin/EOF、环境隔离、启动也计入的短期限、超时保留输出、两路截断、JSON 膨胀后的有界错误、特殊名目录、5011 项目录完整 total/5000 条上限、分段 Base64/段 SHA256、最终软链接拒绝，以及两条长 Exec 满额后第三条 busy、文件请求仍可完成。临时只读/执行令牌在本次验证结束即撤销。
- 1 秒期限可能在 helper 启动阶段到期，不能据此强求已经产生 stdout；短期限用例验证有限返回，输出保留用例采用 5 秒，符合总期限包含启动开销的定义。
- 同进程 100 次真实连接重建和命令取消、完整混淆 App、API23 联调及并存测试 APK 的最终结果见下方 DEV08 记录。

## DEV08 设备验收进展

- `A0.11.0-r1 / 386` 的 r1test 已实际检查包名 `.r1test`、中文名称、min23/target35、debuggable 与 v1/v2 签名；与原包并存安装和冷启动通过。正式 release 仍保持原包名及 unsigned 产物，测试签名不冒充原发行证书。
- 完整 minified r1test 与 production release 代码在隔离设备均通过真实 MCP：Exec UID/exit7/双输出、FsRead/FsList、运行中关闭 Shell 后进程组退出、File 权限不受影响、重开后新请求正常、关闭 File 后新读取明确拒绝。production release 设备副本只用调试证书签在隔离环境验证，不能作为正式覆盖包。
- 完整 release 的 Root 路径实际使用 Shizuku UID2000，Exec、文件段读取、不能自动降级读取 App 私有目录，以及 Root 配置重载取消旧组/不重放均通过。
- API23 完整 release R8 应用以 App UID10061 通过真实 gRPC/MCP Exec、exit7/独立输出和 FsRead；FsList 在加载 NIO26 前明确返回兼容能力错误。
- 首次 100 轮资源检查发现重复硬件名称 EGL 探测残留模拟器驱动连接。真实 FD 分类、停止/GC 及仅跳过名称探测的对照确认后，补充 EGL 线程释放与成功硬件信息的进程缓存；不改变 GPU 使用率采样和模式策略。新版本冷 App 真实 EGL 的 100 轮完整通过：同 PID30113，连接 FD91–92、最终90；PSS 首103725 KiB、末100686 KiB、停止100290 KiB；每轮 native 票0、旧组全部退出、无跨会话旧结果。
- 最终整体审查发现并闭环普通 helper 退出证明与被替换共享 Shell 的生命周期边界：普通票要求三流关闭成功和原 Process 退出证明，固定清理 worker 以 250 ms/10 ms 有界观察，未知继续保票；被移出的 PersistentShell 永久 retire，迟调用/迟 factory 不能复活，过期检查不能拆掉新容器。
- 最终全量为 40 项 core、470 项 app 单测通过，debug/release/r1test R8 与 lint 全部通过。所有工作包和整体代码审查通过；最新完整 R8/API23 与测试包命令、文件、撤权路径复验通过。
- 最新代码在 API36 / 16 KiB 页 / 4 GiB 隔离模拟器的完整 100 轮同进程真实重建/取消通过：同 PID3674，连接 FD94–95，最终93；PSS 首94591 KiB、末96253 KiB、停止96173 KiB。每轮原生票0、旧组退出、无旧结果重放，临时令牌已撤销。
- 安装广播后的 Google 组件更新曾在 2 GiB 测试机触发系统 LMK；通过日志确认不是 App 异常堆栈，最终回归使用 4 GiB。结论不包含真机低内存、24 小时后台或原签名覆盖安装。
- 交付和验收范围见 [R1_BUILD.md](R1_BUILD.md)、[R1_VALIDATION.md](R1_VALIDATION.md)、[R1_MANUAL_TEST.md](R1_MANUAL_TEST.md)。
