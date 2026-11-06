# R1 构建与测试包

R1 版本为 `A0.11.0-r1 / 386`，保持 minSdk 23、compileSdk/targetSdk 35 和现有工具链。

在项目根目录执行（Windows 使用 `gradlew.bat`，Linux/macOS 使用 `./gradlew`）：

```powershell
.\gradlew.bat :core:test :app:testDebugUnitTest
.\gradlew.bat :app:lintRelease
.\gradlew.bat :app:assembleDebug :app:assembleRelease :app:assembleR1test
```

| APK | 用途 | 包名与签名 |
| --- | --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 日常调试，未启用 R8 | `com.nezhahq.agent`，debug 签名 |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 正式发行前的 R8 构建产物 | `com.nezhahq.agent`，当前未配置发行签名 |
| `app/build/outputs/apk/r1test/app-r1test.apk` | R8 与真实设备回归，可与正式版并存；显示名“哪吒探针 R1测试”，非 debuggable | `com.nezhahq.agent.r1test`，debug 签名 |

`r1test` 继承 release 的 R8 配置及项目 ProGuard 规则，关闭 debuggable 以保留 R8 的优化和混淆。测试 APK 采用相同 debug 签名，可对其执行 instrumentation。本地 debug key 与 CI 临时环境生成的 debug key 可能不同，两个来源的测试包可能无法互相覆盖安装。正式版升级必须使用原发行签名；本机没有原发行 keystore，release 保持未签名。测试签名 APK 只用于测试。

CI 先验证提交的依赖锁，再运行单元测试、独立且阻断失败的 `lintRelease`、release/r1test 构建，最后运行 API 23/35 仪器矩阵。成功和失败均归档已有的 JVM XML/HTML、lint、APK、mapping、APK 输出元数据及本次提交/run 信息；设备失败另保留 logcat/截图。OSV 独立扫描同一提交的锁文件；维护命令和产物清单见 [供应链保障](SUPPLY_CHAIN.md)，设备范围见 [设备回归](DEVICE_REGRESSIONS.md)。工作流不执行 GitHub Release 发布。

API23–25 的 FsList 返回 `bounded_directory_enumeration_unavailable`，API26+ 才提供有界目录枚举；详细能力边界见 [MCP_FS_BACKEND.md](MCP_FS_BACKEND.md)。
