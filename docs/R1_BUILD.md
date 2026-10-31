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
| `app/build/outputs/apk/r1test/app-r1test.apk` | R8 与真实设备手测，可与正式版并存；显示名“哪吒探针 R1测试”，允许调试 | `com.nezhahq.agent.r1test`，debug 签名 |

`r1test` 继承 release 的 R8 配置及项目 ProGuard 规则。本地 debug key 与 CI 临时环境生成的 debug key 可能不同，两个来源的测试包可能无法互相覆盖安装。正式版升级必须使用原发行签名；本机没有原发行 keystore，release 保持未签名。测试签名 APK 只用于测试。

CI 依次运行单元测试、独立且阻断失败的 `lintRelease`、release/r1test 构建，并把 r1test APK 保存为 `nezha-agent-r1test` 工作流附件，不执行 GitHub Release 发布。失败时保留单测及 lint 报告。

API23–25 的 FsList 返回 `bounded_directory_enumeration_unavailable`，API26+ 才提供有界目录枚举；详细能力边界见 [MCP_FS_BACKEND.md](MCP_FS_BACKEND.md)。
