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

All three default to off, and every task is checked twice — once when it is routed and once before
it executes. Turning a switch off applies to new requests; sessions already established continue
until the dashboard ends them.

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
  service/    Android lifecycle. AgentService owns the foreground notification and hands the work
              to a reloadable AgentRuntime; keepalive/ owns the wake lock, overlay, audio and
              placeholder-VPN resources.
  executor/   Task handlers — commands, terminal, file manager, NAT.
  collector/  Metric gathering, each with a non-privileged path and a privileged one.
  grpc/       Channel construction, authentication metadata, connection state.
  util/       RootShell and the shell protocol, configuration storage, logging.
  ui/         Compose screens and the design system.
```

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

Some behaviour cannot be reached from a JVM test — anything touching a real shell, a window, or a
live dashboard. `DEVICE_VERIFICATION.md` lists what still needs a device and `VERIFICATION_GUIDE.md`
explains how to check each item.

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
