# R1 本地验收记录

候选版本：`A0.11.0-r1 / 386`。本记录针对当前本地源码与生成的测试 APK；不表示正式签名发布或真机长期验收已经完成。

## 构建与协议

| 项目 | 结果 |
| --- | --- |
| 工具链 | Corretto 17、Gradle 8.7、Kotlin 1.9.22、AGP 8.6；沿用 min23、compile/target35 |
| core 单测 | 40 项，0 失败、0 错误 |
| app 单测 | 470 项，0 失败、0 错误 |
| `lintRelease` | 通过，保持默认错误阻断 |
| debug / release / r1test | 全部构建通过；release 与 r1test 均运行 R8 |
| protobuf | 旧 GPU17、新 GPU18 固定字节与兼容读取通过；uint64 任务 ID 位模式及精确文件偏移有回归 |
| Dashboard | 2026-10-31 再次查询官方 latest，仍为 [v2.6.1](https://github.com/nezhahq/nezha/releases/tag/v2.6.1)；实际联调仅使用回环监听的隔离副本 |

统一命令：

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleR1test :app:lintRelease
```

## 真实设备与面板往返

| 环境/场景 | 结果与证据边界 |
| --- | --- |
| API36 / Android16 / x86_64 / 16 KiB 页 | Task15/16/17 经过真实 gRPC 与 Dashboard MCP 完成，不只调用 JVM 后端 |
| Exec | argv 元字符原样、两路输出、exit7、含 NUL 的 stdin/EOF、环境隔离、超时及已有输出保留、分别截断、JSON 转义膨胀后的有界错误通过 |
| 文件 | 特殊名/隐藏过滤、5011 项目录的完整 total 与 5000 条保留上限、分段 Base64/原始段 SHA256、最终 symlink 拒绝通过 |
| 配额隔离 | 2 条长 Exec 占满后第 3 条得到 busy，同时文件请求完成，采样与旧短 worker 没有被新长命令占满 |
| 完整 minified r1test | 真实命令、文件读取/目录枚举、执行中关闭 Shell 的进程组回收、另一 File 能力继续、重开后新请求、File 关闭后拒绝通过 |
| 完整 production release 代码 | 在隔离模拟器用调试证书签名的副本复验上述路径；这不是正式升级签名 |
| Release Shizuku UID2000 | 真实 launcher/Exec/FsRead 通过；无法读取 App 私有目录时明确失败，未回退 App UID；Root 配置重载后旧组退出、旧请求不重放 |
| Shizuku Manager 撤权/服务重启 | 官方 Manager 13.6 的实际撤权 force-stop 后 helper 组退出；重新授权恢复。4 个已接纳 HELLO/REQUEST 并运行命令的资源束在服务死亡后保票，新服务按需恢复至 0，维护槽回到 IDLE |
| API23 / Android6 / x86 | 完整 Release R8 应用以 App UID10061 完成实际 MCP Exec、独立输出/exit7、FsRead；FsList 明确报 `bounded_directory_enumeration_unavailable`，没有加载 NIO26 路径 |
| Root UID0 机制预检 | API23 原有 root adbd 下，R8 helper 固定代理与 UID0 执行通过；不代替 SukiSU 的 App 授权链 |

临时面板令牌仅限隔离节点的 read/exec，保存在内存中，每次验证结束即撤销。没有连接生产节点、部署或发布 Release。

## 100 次同进程资源回归

最终测试使用独立的 API36 / 16 KiB 页 / 4 GiB 模拟器，同一个 App PID3674。每轮都重建真实 gRPC Runtime、等待本轮连接，发起并确认长命令已运行，再通过 reload 取消，验证旧进程组没有活进程、旧请求没有通过新注册回包。100 轮全部完成；过程没有 force-stop 目标 App。

| 观察点 | FD | PSS（KiB） | native 清理票 |
| --- | ---: | ---: | ---: |
| 第 1 轮已连接 | 95 | 94591 | 0 |
| 第 100 轮已连接 | 94 | 96253 | 0 |
| 最终停止并短暂稳定 | 93 | 96173 | 0 |

连接阶段 FD 始终为 94–95。过程中的线程数包含协程和有空闲超时的固定清理/IO 池，不把短时线程存活等同于任务未退出。

该回归发现并修复了两类资源生命周期问题：重复 Runtime 的 EGL 名称探测残留驱动连接；普通 helper 仅关流/发信号而未证明退出即归还容量。修复后成功硬件信息按进程复用，临时 EGL 线程状态释放；普通 helper 关闭原三流后，在固定 worker 内短暂观察原进程退出，活跃或未知状态继续保票。被替换的共享 Shell 另通过永久 retire 防止旧引用复活，原可重用 shutdown 行为保留。

GeoIP 缓存仅在本循环测试中预置为回环测试值，避免外网变化影响连接生命周期测量；这项结果不证明外网 GeoIP 服务可用性。此前 2 GiB 模拟器在安装广播后的 Google 组件更新期间发生过系统 LMK，最终回归改用 4 GiB；本记录不把该回归写成低内存压力或 24 小时后台验收。

## 测试包与剩余验收

`r1test` 包名为 `com.nezhahq.agent.r1test`，名称“哪吒探针 R1测试”，版本 386，debuggable，使用调试签名且通过 v1/v2 签名校验；已与原包并存安装并冷启动。正式 release 的包名保持 `com.nezhahq.agent`，本机未配置原发行 keystore。

仍需按 [R1_MANUAL_TEST.md](R1_MANUAL_TEST.md) 完成：

- Android15/17 两台 SukiSU 真机的 App 授权、拒绝、停止/撤权及进程回收。
- 真机 GPU 来源可读性、面板实际显示和后台至少 24 小时。
- 原发行签名覆盖安装、UUID/配置/权限保留，以及递增 versionCode 的修复恢复。

本轮仅实现 R1：Task15 Exec、Task16 FsList、Task17 FsRead。Task12–14、18–20 继续明确 unsupported。原始性能数据与设备驱动脚本保存在本次本地验证产物目录，仓库中的机制测试与协议 fixture 可重复运行。
