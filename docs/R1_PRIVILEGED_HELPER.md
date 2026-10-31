# R1 DEV06 高权 MCP helper 边界

DEV06 提供独立的 launcher/授权/清理组件，暂不接入 AgentRuntime、Dashboard 能力展示或旧命令处理器。

- `McpHelperLaunchers.select(context, rootMode)` 的普通模式分支只构造 `ApplicationHelperLauncher`，不会访问 RootShell、su 或 Shizuku。
- 高权模式通过 RootShell 进程授权和同一 `ShellBackendPolicy` 固定选择后端。Shizuku 服务存在时先选择 Shizuku；待授权、拒绝、失效或服务死亡都返回明确的能力错误，不回退到 su/普通模式。
- 只有首次 Binder 查询确实为空才视为未发现服务。已见 Binder 后的 UID 查询异常、Binder 替换/死亡均视为发现失败，固定或保留 Shizuku 选择并返回 `CAPABILITY_UNAVAILABLE`；后续服务消失也不触发 su。迟到发现失败受原启用/策略 epoch 约束，不改变新启用的选择。
- su 从应用进程的可信本地 PATH 解析绝对可执行路径，以通用 `su -c` 启动固定程序。启动脚本没有远端 cmd/args/env/cwd/stdin；用户数据仍只通过 REQUEST 帧进入 helper。
- 高权默认工作目录 `/system/bin`，TMPDIR `/data/local/tmp`。CLASSPATH 直接使用安装 APK 的 `applicationInfo.sourceDir`，不复制 APK 或放宽私有目录权限。su 可能重建环境，因此 app_process 前通过 `env -i` 重施 bootstrap 白名单。

## 授权和生命周期

`PrivilegedHelperAuthority` 保存启用 epoch、捕获的 Shizuku Binder 和服务 epoch。它从该 Binder 的 `IShizukuService.checkSelfPermission()` 获取真实授权结果，绕过 SDK 13.1.5 的 `checkSelfPermission`/`checkRemotePermission` 缓存。不会从 launcher 请求权限。活动 Shizuku 许可每 500ms 触发共享检查；全进程最多一个在途 RPC，约 1.5s 无法确认即撤销旧许可。拒绝/超时结果发布前先将旧许可标为失效，确保首次 REQUEST 的快速写入检查已关闭；登记回调在锁外执行，可晚于 `requireLive()` 报错完成，具体 IO 清理完成由清理票表示。迟到 grant 不会重新授权旧 epoch。

启动前、native 启动返回后、三路流登记后、HELLO 接纳和 REQUEST 写入前都复核许可。协议 worker 在同一限时任务内接纳 HELLO 并写入首次 REQUEST；输出包装层在每次 write/flush 前快速拒绝已撤销的许可。UID/eUID 必须与选择的后端一致：Root=0，Shizuku=捕获服务的 0 或 2000。

RootShell 在短 lifecycle 锁内更新本地授权/epoch、使旧 MCP 许可失效并分离旧 legacy 资源快照。锁外先关闭旧 MCP 登记，再进行旧 persistent/managed shell 的慢清理。新 epoch 不属于旧快照，旧失败也不能 reject 新 epoch 的 su。

Shizuku 收到/死亡监听器属于 RootShell 进程资源，不绑定 Activity。状态锁不执行 Binder/Process/流操作或回调；backend-policy 的选择锁也不覆盖 native 启动。

## 代理和清理

高权远程 Process 对应固定 `/system/bin/sh` 代理。代理先执行 `exec 3<&0` 保存控制输入，再后台启动 app_process，并用 `<&3 3<&-` 将保存的输入接入子进程 stdin、关闭子进程 fd3；代理保存 `child=$!` 后执行 `exec 3<&-`，关闭自己的 fd3，再 `wait "$child"`。不会通过 exec 替换成 HelperMain。mksh 会在应用后台命令的重定向前把 fd0 置为 `/dev/null`，所以此前直接 `<&0 &` 的方案无法保留输入。helper 自己建立 session/group；控制 EOF/watchdog 负责同 UID 清组。

主代理已在 API 36 设备上对同一固定代理脚本独立验证：保存 fd3 后，UID2000 的 `id -u` 获得完整 RESULT、用户命令 exit0，helper/代理最终退出137。该证据不代替完整 App 内 Shizuku、Manager 撤权或服务重启验证。

三路流只在 launch worker 各取得一次，部分取得失败也保留已有 Process/流的清理所有权。高权任务不会加入旧 `managedProcesses`；已接纳身份后不从 App UID 对高权 PID/进程组发信号，也不以 destroy 代理代替回收证明。

所有用户 MCP 任务及其底层资源束共用全局 4 张清理票，在 native 启动前取得。close 幂等，并把可能等待 buffered writer/flush 的三路关闭和退出检查移到固定清理池。三路关闭不代表退出；可信代理退出可释放票，Binder/退出状态未知则保票，池满明确 BUSY。

为处理 Shizuku 重启后保留的 unknown 票，另设 **独立单槽的短维护探针**。这不是用户任务票；因此可成为第 5 个短 native 进程。它只接受程序生成的 nonce/UID/PID/startTicks，使用固定 app_process 入口、VM 内约 1.2s watchdog、父端约 1.5s 启动的销毁尝试；这些计时器不被当作退出证明。它不接收用户命令，不是常驻服务。

在新 Binder 明确授权后，探针从内核重新读取 UID、session/group、startTicks 和精确 nonce nice-name，信号前再次核对身份，清理并确认匹配进程退出后才回收旧票。未知或无法读取的同 UID 记录不会被当作退出；不盲杀缓存整数 PID。新 Root 启用也可在授权范围内用同一固定探针处理旧资源，已 reject 的当前 su epoch 不重试启动。

`CLEAN` 只覆盖已经接纳 HELLO 的 helper/group 及固定代理。已接纳 HELLO 的 Shizuku 资源束可用它收敛旧 Binder 的未知退出；Root 仍须通过原本地 su `Process.exitValue()` 确认退出，不能用 helper/proxy 消失替代 su 包装进程退出。Root 已取得 CLEAN 后不再为同一组重复启动维护 su，后续授权只异步重查既有本地 Process 的退出，不新增 native。尚未接纳 HELLO 的未知资源不进入维护候选，也不接受 PID0/空 nonce 扫描的 CLEAN；su 可能仍在授权/启动，尚无 marker 不能证明它稍后不会启动 helper。这类资源若一直无法确认原进程退出，也可能需要真正终止并重新打开 App 进程，不能仅停启监控探针。

探针先输出固定 `READY:<nonce>:<uid>` 就绪证明，确认收敛后再输出 `CLEAN:<nonce>`。未能取得选中 UID0 的就绪证明或 su 启动失败，会 reject 当前 su epoch，避免四张旧票均 BUSY 时反复弹权；已取得真实 UID0 但旧任务暂未收敛只保留其清理票，不误判为授权失败。候选按队列轮转，每次实际维护尝试移至队尾；首票不可清理时其余票仍能恢复。

维护资源束由唯一 owner 持有 Process、已取得的流和授权登记。只有可信自然退出且流关闭完成后才释放维护槽；直接 Shizuku app_process 探针没有用户子进程，销毁后确认退出也可释放。销毁 su 包装进程不能证明其高权子进程退出。native 启动、取流、退出或销毁 RPC 永久未知时，保留该 owner 和单槽，后续新 Binder/授权也不重叠启动维护进程。这种情况需要真正终止 App 进程并重新打开应用，例如在系统应用设置中强行停止后再打开；仅停止/启动监控探针不会重建该维护槽，不能承诺全自动恢复。raw 入口明确拒绝且证明尚未调用 native 时，未用的维护槽和任务票可释放。正常服务重启后，只要维护探针退出可确认，就随之后续授权请求按需自动收敛旧票。

FullRoot 用户若主动逃逸 session、停止或恶意杀死 helper，平台不能保证回收所有后代；这里不承诺此类条件下全部终止。

## 私有协议变化

- `HelperProtocol.VERSION = 2`。
- HELLO 从 52 字节增加到 60 字节，尾部新增 big-endian `startTimeTicks: Long`，由 helper 的 `/proc/self/stat` 读取。
- nonce/UID/eUID/PID/session 验证和 Dashboard JSON 字段保持原有含义。版本/身份不符时，用户 payload 不会发送。
- `HelperProcessLease.beforeRequest()` 默认空实现；高权实现用于首次 REQUEST 写入前的实时授权复核。
- 清理票增加 retained/retry 状态，仅在可信退出或身份收敛后释放高权资源。

## 验证范围

新增纯 JVM 注入测试覆盖：普通模式不探测、Shizuku pending/撤权不回退、实时 RPC/单在途/超时/迟到 grant、服务与启用 epoch、RootOff 慢清理期间 RootOn、新旧 su failure、坏 UID 不发 payload、三路 getter 一次/部分失败、取消后迟返回、池满 BUSY/unknown 保票/恢复收敛，以及精确 nonce/UID/startTicks/session 的探针核验。

2026-10-31 的本地独立验证：使用缓存中的 Kotlin 1.9.22、JDK20、现有 Android/项目依赖编译本轮 14 个主源码文件成功；新增测试和受影响的 Application/Exec/Fs/transport/backend-policy/RemoteShellProcess 回归共 84 项通过（JUnit 约 7.3s）。日志位于被忽略的 `app/build/mcp-dev06/`。此验证没有运行 Gradle，不替代完整构建/R8 或设备验证。

授权结果发布顺序与 fd3 输入修正后，重新独立编译受影响主源码和两类测试，20 项通过（JUnit 0.488s），包含拒绝/超时先关闭快速写入检查、锁外回调完成同步，以及 callback 阻塞时授权检查仍能及时失败。日志为 `app/build/mcp-dev06/junit-fix.log`；此轮没有重复无关测试，完整 Gradle/App 内设备验证由主代理继续。

随后四项审查修正的独立验证：受影响的 7 个主源码编译成功，发现/授权/lease/维护协调器及后端策略共 36 项测试通过（最终 JUnit 0.556s，`app/build/mcp-dev06/junit-review.log`）。回归覆盖首次发现异常与 Binder 替换/死亡不回退、旧 epoch 迟到失败、四票满时维护 su 不重复请求、真实 READY/CLEAN 回复驱动的轮转收敛、未知维护 owner 跨 Binder 保留、部分取流失败及明确未调用 native 的资源释放。此轮没有启动 Gradle或重复设备用例，仍需主代理对新版本统一构建和复验。

收紧 CLEAN 的证明范围后，独立编译两个受影响主源码并运行 lease/维护协调器相关 17 项测试，全部通过（JUnit 0.478s，`app/build/mcp-dev06/junit-proof.log`）。新增回归用状态读取门闩验证：没有 HELLO、尚未出现 marker 的旧 su 保票；Root 已 HELLO 且 CLEAN 但本地 su 仍 alive 时保票；后续本地 su 真实退出才释放。新 Binder 下已接纳 HELLO 的 Shizuku 四张票仍通过实际 READY/CLEAN 回复全部收敛。

Android 薄适配的最终设备记录由主代理维护，覆盖真实 su/SukiSU 授权、Shizuku Manager 撤权 force-stop、服务重启、保存 fd3 的代理/wait 生命周期、UID/session/后代进程回收、APK 可读性及 minSdk 23。构建、统一测试与双审查由主代理执行；本文件不把未执行的设备验证写成已通过。
