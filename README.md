# Nezha Agent for Android

An Android client for the [Nezha](https://github.com/nezhahq/nezha) monitoring dashboard. It runs
as a foreground service, reports device metrics over gRPC, and — only where the user has explicitly
granted it — carries out tasks the dashboard sends back.

## What it does

**Reports**, always: CPU, memory, swap, disk, network throughput and connection counts, load
average, process count, temperatures, GPU utilisation, and host information such as SoC, boot time
and virtualization type.

**Executes**, only with the matching switch turned on in the app:

| Task | Switch | Notes |
|:--|:--|:--|
| HTTP GET, ICMP ping, TCP ping | — | monitoring probes, always available |
| Shell command, interactive terminal | 允许面板远程执行命令 | also gates `@agent` virtual commands |
| File browse, download, upload | 允许面板远程管理文件 | the app holds all-files access |
| NAT / TCP forwarding | 允许面板内网穿透 | the device becomes a route into its network |

三个开关默认关闭。收到请求时检查对应能力，短任务执行前再次读取最新授权。
关闭能力不会重连 gRPC：新传统终端、文件管理和 NAT 请求会被拒绝，已建立的传统长会话继续到
面板结束或连接关闭；受影响的活动 MCP Exec/FsList/FsRead 后端会被取消并等待清理。
重新开启能力不会复活已撤销的 MCP 任务。

**Root and Shizuku** are separate from the switches above. They decide whether an already-permitted
shell runs with elevation, not whether the dashboard may ask for one.

连接配置以密文保存在应用私有目录，密钥由 Android Keystore 管理。此保护针对静态存储；
如果应用进程被控制，正在使用的配置仍可能被读取。读取密钥或密文失败时保留原文件，
只有明确确认重置才清空配置；界面会显示需要重试或处理的存储问题。

## 电池指标

温度栏同时上报 `Battery`（摄氏度）、`电池电量 (%)` 和电池侧充电/放电功率（瓦）。
缺失的指标不发送，真实零值保留。旧面板如果固定追加 `℃`，应按指标名称中的单位显示；
当前温度协议没有独立单位字段，修改探针不能消除面板写死的单位。

普通模式使用系统广播与 BatteryManager。高权限模式在现有批量快照中读取
`/sys/class/power_supply/battery/uevent`，优先 `POWER_NOW`，其次同一节点的电压×电流，
不可读时使用系统 API。不会读取充电器输入功率充当电池功率。

双电芯不自动乘二，也不累加可能重复映射的 battery/bms/main/slave 节点。
电压×电流统一标注“估算”：部分厂商仅公开单电芯电压或等效电流，通用 API 无法确认其整包口径，
因此尚不能保证这些机型的整包功率准确；需实机驱动数据才能进一步适配。
充电期间电池侧净功率不等于整机耗电功率或充电器输入功率。

高权限后端在启用期间保持选择：Shizuku 已授权时直接使用；服务失效或撤权不会尝试 su。
Shizuku 正在等待授权时不触发 Root 请求。无 Shizuku 服务且存在 su 时，Root 路径以限时
`id -u` 校验确认 UID 0；失败后本次启用期间不再请求 su。需要重新选择后端时关闭并重新开启高权限模式。

## Building

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # minified release APK
./gradlew :core:test :app:testDebugUnitTest   # the whole unit suite
```

JDK 17, Android SDK 35, minSdk 23. Device regressions run against the non-debuggable, R8-optimized
`r1test` APK on API 23 and 35: `./gradlew :app:connectedR1testAndroidTest`.
See [device coverage and remaining manual checks](docs/DEVICE_REGRESSIONS.md).
Dependency lock maintenance, OSV scans, CI artifacts and Dependabot installation are documented in
[the supply chain guide](docs/SUPPLY_CHAIN.md).

## Layout

```
core/   Pure Kotlin. Configuration validation, remote-capability policy, the privileged-access
        boundary. No Android SDK on its classpath, so these rules cannot start depending on a
        device. :app depends on :core, never the reverse.

app/
  service/    AgentService/Controller 管理前台服务与 Runtime 替换；AgentRuntime 装配组件并拥有
              根生命周期。ConnectionSupervisor、MetricReporter、TaskDispatcher、
              StreamSessionManager 分别管理连接、上报、任务注册和传统流会话。
              keepalive/ 拥有 wake lock、悬浮窗、音频和占位 VPN 资源。
  executor/   Task handlers — commands, terminal, file manager, NAT.
  collector/  Metric gathering, each with a non-privileged path and a privileged one.
  grpc/       Channel construction, authentication metadata, connection state.
  util/       RootShell and the shell protocol, configuration storage, logging.
  ui/         Compose screens and the design system; ui/main/ contains the feature controllers
              and immutable UI state.
  simulator/  模拟节点循环及由 AppContainer 持有的单个会话槽和原生连接清理状态。
```

`MainViewModel` 只装配连接设置、运行控制、模拟器和诊断控制器，合并只读 `MainUiState`，并转发
明确的 UI 事件。控制器共用一份 `UiConfigurationCoordinator`，由它拥有草稿、仓库读写、
存储恢复和共享写入/解析门禁；保存失败不会继续启动服务或更新运行时权限。

`AppContainer` 保有配置就绪状态、连接状态、本地诊断和 `SimulatorSessionStore`。模拟器在
异步保存前先占用带代次的会话槽；停止请求先关闭准入，只有会话 Job 和全部原生连接都确认退出后
才释放槽。清理失败或 ViewModel 销毁不会抹掉待清理的连接，新 ViewModel 也不能绕过该槽启动后继。
此槽与真实 Service 的 `RuntimeShutdownGate` 分别管理各自资源。

Runtime 的作用域层级、任务撤权和完整停止条件见 [运行时架构说明](docs/R1_RUNTIME.md)。

### Two shell channels

`RootShell.execute` runs on one shared session that every caller queues behind. The metrics loop
reads `/proc` through it every two seconds, so anything holding it delays state reports — and once
the dashboard stops receiving those, it drops the connection. Commands that can run for seconds
must use `RootShell.executeIsolated`, which gets its own process. `ShellTimeoutBudgetTest` pins the
timing relationship that makes this safe.

## Testing

Unit tests run against an unmocked `android.jar`: `unitTests.isReturnDefaultValues = false`, so an
Android call that a test forgot to stub throws instead of quietly returning `0`/`false`/`null`. A
test whose code path logs needs `SilentLoggerRule`. There is no Robolectric and no mocking
framework.

Runtime 的组件装配回归直接检查空闲 producer 替换、MCP 请求/结果预算、传统会话与短 worker
存活、权限刷新、真实流错误重连、GeoIP 取消竞态和首回执退避。JVM 用例替换平台解析/执行入口；
Android 仪器用例另用真实 `JSONObject` 验证传统任务解析、错误结果和配额释放。

[设备回归说明](docs/DEVICE_REGRESSIONS.md) 列出优化目标 APK 上的自动化范围及证据要求；
真实 Dashboard、Root/Shizuku 管理器和厂商后台行为仍需对应环境验收。
`DEVICE_VERIFICATION.md` 与 `VERIFICATION_GUIDE.md` 提供手工步骤。用例存在或旧报告通过不代表
当前源码已经验证，本次状态应以同一提交的实际 JVM、构建和仪器结果为准。

## Security notes

- Connection settings, including the client secret, are stored in AES-GCM-encrypted DataStore files
  in the app's private directory. The encryption key is managed by Android Keystore. Legacy
  plaintext and EncryptedSharedPreferences stores are read only for verified migration.
- The accessibility service exists for keep-alive, and also provides the screenshot capability
  behind the remote-command switch. Its description says so, because that description is what the
  user reads when granting it.
- TLS is on by default and never falls back to plaintext on failure. Plaintext requires the user to
  turn TLS off explicitly, and exposes the secret and everything else on the wire.
- Only system CAs are trusted; a user-installed certificate cannot intercept the agent.
