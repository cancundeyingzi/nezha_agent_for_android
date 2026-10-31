# R1 MCP 公共协议与限额

本文件记录 DEV03 的协议边界。DTO、解析器、编码器与准入规则已提供；本工作包不安装任务处理器，不修改现有 `AgentRuntime`、`TaskExecutor`、`TaskTypes.isKnownUnsupportedOnAndroid`、配置、UI 或采集器。因此当前旧执行入口仍将 15–17 标为 unsupported，不能据此宣称设备已支持 MCP。

固定参考为 Go Agent `c2b65ab6fa8926f3c7ed4ef3a0065fe003410237` 的 [model/task.go](https://github.com/nezhahq/agent/blob/c2b65ab6fa8926f3c7ed4ef3a0065fe003410237/model/task.go)、[mcp_exec.go](https://github.com/nezhahq/agent/blob/c2b65ab6fa8926f3c7ed4ef3a0065fe003410237/cmd/agent/mcp_exec.go)、[mcp_handlers.go](https://github.com/nezhahq/agent/blob/c2b65ab6fa8926f3c7ed4ef3a0065fe003410237/cmd/agent/mcp_handlers.go)，以及 Dashboard [v2.6.1/mcp_capability.go](https://github.com/nezhahq/nezha/blob/v2.6.1/controller/mcp_capability.go)。后续上游变更不自动改变本契约。

## 模块与公共入口

- `core.mcp`：纯 Kotlin/JDK 的 DTO、业务检查、权限映射、版本格式化、非阻塞配额与幂等 lease；不依赖 Android、Gson、gRPC、OkHttp 或协程。
- `app.mcp.McpRequestParser.parse(taskType: Long, data: String): McpParseResult`：只解析 Task15/16/17，返回原始、类型及数值范围已经验证的 DTO 和请求 UTF-8 字节数。随后必须调用 `McpRequestRules.prepare(request)`，获取规范化后的 DTO 或业务错误。
- `app.mcp.McpResultEncoder.encode(id: Long, type: Long, result: McpResult): TaskResult`：显式编码三族结果并限制最终 Protobuf 大小。
- `McpResultEncoder.businessError(...)`、`parseFailure(...)`、`unsupported(...)`：分别构造业务失败、解析失败和未支持任务结果。
- `McpPolicy.family/requiredCapability/denial`：R1 类型识别与实时授权检查，不改变旧路由。
- `McpTaskAdmission.tryAcquire(family, requestBytes)`：非等待式准入；获得的 lease 同时拥有任务名额和请求字节。`tryReserveResult(serializedBytes)` 为最终结果另留字节，直到发送或丢弃。

## 请求字段

| 任务 | 字段与 Kotlin 类型 | 缺省与业务规则 |
| --- | --- | --- |
| 15 Exec | `cmd: String` | 必填、业务非空白；缺失被解析为 `""`，随后返回结构化 `cmd required` |
| 15 Exec | `args: List<String>`、`env: Map<String,String>`、`stdin: String`、`cwd: String?` | 分别为空列表、空映射、空字符串、未指定；显式 JSON null 均拒绝，包括 cwd |
| 15 Exec | `timeout_seconds: Long`，线协议 uint32 | 必须先严格解析 0..4294967295 整数；缺省或 0 为 30，范围内大于 300 才限幅为 300 |
| 15 Exec | `max_output_bytes: Long`，线协议 uint32 | 缺省或 0 为 65536；范围内大于 1048576 才限幅。此为 stdout/stderr **各自**的原始输出上限，另受最终消息上限约束 |
| 16 FsList | `path: String`、`show_hidden: Boolean` | path 必填且为 Android 绝对路径；拒绝 NUL；show_hidden 缺省 false |
| 17 FsRead | `path: String`、`offset: Long`、`length: Long`、`encoding: String` | path 同上；offset 缺省 0；length 缺省或 0 为 1048576，正值超过此数限幅；负 offset/length 为业务错误；encoding 缺省或空串为 utf8，仅另支持 base64 |

uint32 的负数字面量（包括 `-0`）、小数、指数、字符串、布尔值和超范围整数均为解析失败，不能先转浮点或限幅。FsRead 的数值严格解析为有符号 Int64，不经 Double；超 Long 范围为解析失败，范围内负值为业务失败。`Long.MAX_VALUE` offset 保持精确，后续读取不能通过未经检查的 `offset + length` 判断截断；应比较文件长度差值或检查加法溢出。

未知 encoding 属于业务错误，固定返回 `unknown encoding`，不回显传入值。无目录范围沙箱；实际权限由 Android/Root/Shizuku 执行 UID 与现有显式授权决定。路径的 nofollow、非阻塞打开、实际 fd 普通文件校验及并发替换保护由后续实际文件后端落实，不能用 DTO 校验替代。

## 严格 JSON 与现有 JSON 栈的差异

本工作包仅新增运行时依赖 `com.google.code.gson:gson:2.10.1`，不升级任何现有版本。Android `JSONObject` 会接受宽松语法和类型转换，且普通 JVM 单测中的 Android API 未 mock；Gson 的 `JsonReader/JsonWriter` 提供可在真实 JVM 上测试的流式适配。仅使用手写字段映射，不调用 Gson 反射 DTO、`toJson` 或 `fromJson`，core 不依赖 Gson。

Gson 2.10.1 `isLenient=false` 仍可能接受引号内未转义控制字符、部分非标准转义和大小写混写的 TRUE/FALSE/NULL，解析前增加字符串、字面量及数字词法校验；JSON 文法、容器和字段继续由流式 reader 处理。该扫描不构造 JSON 树，也不记录原始内容。未知字段允许合法 JSON 小数及指数，已知整数字段仍单独拒绝这两种形式。

该版本另有 1024 字符的内部数字 buffer：长度达到 1024 的合法数字会被严格 reader 拒绝，即使字段本应兼容跳过。适配层先验证**原始完整数字词法**，再仅在供 reader 使用的副本中把这类超长数字 token 替换为 null；字符串内的数字不改。所有已知字段及 args/env 元素都明确拒绝 null，因此替换不能产生缺省值、合法命令或其他可接受字段；未知字段则可正常跳过。这类已知超长数字仍是解析失败，错误类别为 invalid field type。JSON 结构、深度及尾随文档继续验证；请求限额与 `Parsed.utf8Bytes` 一律按原文计费，绝不按缩短后的副本。未出现超长数字时直接使用原字符串，不创建副本。

- 顶层必须是对象，结束后只能有 JSON 空白，拒绝第二份 JSON、尾随注释和其他字符。
- 已知字段严格类型；字符串数字、数字字符串、真假字符串均不强转。显式 null 拒绝。
- 未知字段前向兼容跳过，但同时校验 JSON 并限制容器嵌套深度。顶层对象深度为 1，总深度最大 64；未知值不能绕过限制。
- 重复已知字段（含转义后同名字段）及重复 env 键明确拒绝；未知字段重复仍允许。这比 Go `encoding/json` 的覆盖行为更严格。
- 负 FsRead offset/length 明确拒绝；上游对负 length 使用缺省值，Android R1 将其作为错误输入。绝不把负值悄悄转换成默认长度。
- 已知字段 null 拒绝比 Go 的部分 null 零值行为更严格；空白 cmd、Android 绝对路径和 NUL 检查为业务检查。
- 错误只包含固定类别和白名单字段名，不使用 reader 的动态 path、env 键、输入值或底层异常消息。解析器和编码器不写日志。

新增依赖的 APK 增量必须在最终 Runtime 接线后，用相同 R8 配置实际构建测量；本轮没有可靠增量数据。当前新类未接线，可能被 R8 消除，因此不能用本阶段 release APK 的变化代表最终成本。

## 结果与 Protobuf 语义

`Task.id/type` 为 protobuf uint64，Java API 使用 Long 位模式。所有结果直接透传原始 Long，包括设置最高位的值；从不把 id/type 放进 JSON、转 Double 或通过十进制字符串重建。

| 情况 | `TaskResult.successful` | `data` |
| --- | --- | --- |
| 有效结构化结果，包括非零 exit、timeout | true | 对应族 JSON；保留真实退出码、输出、持续时间与状态 |
| 权限拒绝、busy、notfound、能力不足、非法业务参数或 I/O 故障 | true | 对应族 JSON，`error` 非空 |
| JSON/类型/整数范围解析失败 | false | 清理后的固定类别与字段说明 |
| 编码内部失败或结果与任务族不匹配 | false | 固定合法 JSON：`{"error":"agent: failed to encode result"}` |
| 未实现任务，包括 12–14、18–20 | false | 现有显式 unsupported 文本 |

非零 exit 和 timeout 本身不强制 error；这是固定上游的命令结果语义。旧 Task4 的授权失败、退出码及文本协议完全不变，不能复用其 successful 判断来编码 Task15。

输出字段显式对应上游：Exec 的 `exit_code/stdout/stderr/duration_ms` 必写，`*_truncated/timed_out/error` 按需；FsEntry 的 type 为 `file/dir/symlink/special`，size/mtime 用 Long，mtime 为 Unix 秒；FsList 包含 entries 和可选 truncated/total/error。FsRead 的 size 是**本段原始字节数**，sha256 是**同一原始段**的散列，不能用整个文件或 Base64 文本计算。

单个**最终序列化 Protobuf TaskResult** 最大 3145728 字节（3 MiB），包含 uint64 元数据、JSON 转义、数据字段标签/长度与 successful；Dashboard/Android 默认 gRPC 4 MiB 接收限额留有余量。编码器预留 protobuf 包络及长度 varint 的空间，再用有界 Writer 按输出 UTF-8 字节累计；控制字符可能变成六字节 `\uXXXX`，写到上限立即停止，不先生成可能达 12 MiB 的 JSON 字符串。最后再次检查 `serializedSize`。

结果过大返回同族短结构化错误 `output/result too large`，successful 仍为 true：

- Exec 保留真实 exit_code、duration_ms、timed_out；被丢弃的非空输出设置对应截断标记，输出清空，已有截断标记保留。
- FsList 返回 error、truncated=true，保留 total；entries 清空代表结果已丢弃，error 必须存在，不能误报为空目录成功。
- FsRead 返回 error、truncated=true、空 content、size=0，移除原段 sha256；不能把原大小或散列配给空内容而伪造读取成功。

业务错误中的 message 由后续调用者提供，必须使用经过清理的稳定文案，不直接传 `Throwable.message`、命令、环境变量、stdin、文件正文或完整敏感路径。枚举 code 供本地判断，线协议保持上游 error 字符串，不额外添加私有字段。

## 限额与资源归属

| 资源 | 限额 |
| --- | --- |
| 单个原始请求 UTF-8 字节 | 1048576（1 MiB） |
| FsList 保留目录条目 | 5000（仍须另限保留字节与枚举时间） |
| Exec 活动任务 | 2 |
| FsList + FsRead 合计活动任务 | 2 |
| MCP 保留请求总字节 | 4194304（4 MiB） |
| MCP 结果缓冲总字节 | 6291456（6 MiB） |
| 单个最终 TaskResult | 3145728（3 MiB） |

字节预算用减法判断剩余量，避免 Long 加法溢出；负大小返回 InvalidSize/InvalidRequestSize，资源耗尽返回 Busy，不能混为非法输入。lease 实现 AutoCloseable 并通过原子状态幂等释放，完成、取消和会话清理可重复 close。预算按保留的线协议字节计费，不代表 JVM 堆内存估算。

以上是公共规则，尚未接线即不产生实际调度限额。后续 Runtime 须在入口尝试准入，并将 MCP 分派给独立任务路径；禁止让原有 8 个短任务 worker 等待长命令名额。预算属于当前任务流/会话；旧会话结果不得发往新连接。请求 lease 持有到执行与清理结束，结果 lease 持有到发送/丢弃完成，异常、取消与旧会话替换都必须释放。

## 后续接线清单

1. Runtime 识别 15–17，在路由处和真正执行前调用 `McpPolicy.denial`；撤权取消对应活动任务。先得出有效 DTO/业务错误，解析错误仍返回 false；实际执行前必须完成 `McpRequestRules.prepare`。
2. 独立于旧短任务 worker 的 MCP 入口调用 `McpTaskAdmission.tryAcquire`，busy 用同族业务结果编码；保留请求与结果都纳入总字节预算，按当前会话拥有并取消。
3. 只有实际处理器安装后，从 `TaskTypes.isKnownUnsupportedOnAndroid` 清单删除 15–17，并调整直接旧执行器相关断言；12–14、18–20 继续 unsupported。
4. 每任务 `app_process` helper 复用 DTO 与严格协议；执行 UID、nonce、控制 EOF、独立 session/process group、期限和 kill/回收由后续管道负责，不新增 Service 或配置。结果编码前限制原始输出、目录条目保留量和执行时间，不能只靠最终编码器限幅。
5. API23–25 FsList 返回明确能力错误；API26+ 才接入已打开目录 fd 锚定的流式枚举。FsRead 在实际执行 UID 下完成 nofollow、非阻塞打开和普通文件 fd 校验。
6. 处理器全部安装后，在 AgentRuntime 传入实际应用版本的边界调用 `McpProtocolVersion.hostVersion(实际 APK 版本)`，仅使 Host.version 上报 `2.1.0+android.A0.10.1` 形状；APK versionName/versionCode、UI 及模拟节点的原版本表达不变，Android platform 身份保留。本工作包不改 Runtime 或采集器。

Dashboard v2.6.1 的 `compareSemver/semverParts` 去掉可选 v 前缀及 `-/+` 后缀，只比较前三段数字，因此 A0.10.1 低于门槛，`2.1.0+android.A0.10.1` 等于 v2.1.0。这里的 2.1.0 是协议入口兼容标识，**不表示 Android 变成 Go Agent，也不承诺 18–20 的全部 MCP 能力**。本轮只实现 15–17；未实现任务必须快速给出 unsupported。

## 固定测试与验证范围

core 的版本 fixture 锁定 v2.6.1 比较规则，测试直接移植该版本的比较算法；app 的 `mcp/r1` fixture 固定任务号、请求及结果字段，manifest 记录上游 SHA。单测覆盖严格类型/null、缺省/0、uint32 最大/溢出、Long 边界/精度、未知字段/深度/尾随内容、1023/1024/1025 字符数字及长指数兼容跳过、原始请求计费、Unicode/控制字符、最高位 id/type、Task4 与 Task15 语义、权限/unsupported、最终 Protobuf 大小、转义放大、内部编码失败及配额/并发/重复释放。涉及旧执行器日志的测试沿用 SilentLoggerRule，不新增 Android mock 或 Robolectric。

建议统一执行 `:core:test :app:testDebugUnitTest`，然后在实际 Runtime/后端接线后执行 R8、lint、API23–25/API26+ 和普通/Root/Shizuku 设备验证，以及本地 Dashboard v2.6.1 联调。本文件的 fixture 和 JVM 测试不能替代真实设备执行、撤权取消、进程组回收或最终 APK 体积测量。
