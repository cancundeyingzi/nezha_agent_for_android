# 依赖锁与供应链保障

依赖及插件的直接版本统一维护在 `gradle/libs.versions.toml`。`app/gradle.lockfile`、`core/gradle.lockfile` 由 Gradle 的实际解析生成并提交，不能手工填入推测版本。Kotlin 1.9.22、AGP 8.6、Compose 编译器与 BOM 的兼容关系保持独立审查，库的安全补丁不顺带升级工具链。

## 生成与验证依赖锁

`app`、`core` 对配置容器中所有可解析配置启用严格锁。范围包括各构建变体、JVM 测试、`r1testAndroidTest`、代码生成和可见的模块内工具配置。Gradle 插件/buildscript classpath、插件临时创建的 detached configuration 属于独立作用域，不包含在这两个模块锁文件内；插件直接版本由版本目录固定，不能据此声称插件传递依赖也已全部锁定。

Gradle 另会为本地 `libs.versions.toml` 导入自动生成根目录 `settings-gradle.lockfile`，当前仅含 `empty=incomingCatalogForLibs0`，没有外部依赖。这个准确路径被忽略，不作为有效依赖锁交付，也不计入扫描覆盖；如果 settings 或版本目录将来开始解析外部依赖，应重新审查该范围。`app`、`core` 的真实锁文件必须提交。

使用 JDK 17、Android SDK 35，在仓库根目录执行：

```powershell
# 初次生成或在明确更新依赖之后重建锁。
.\gradlew.bat --no-daemon :app:resolveAndLockAll :core:resolveAndLockAll --write-locks

# 正常验收与 CI：验证既有锁，禁止写入或更新锁。
.\gradlew.bat --no-daemon :app:verifyDependencyLocks :core:verifyDependencyLocks
```

Linux 使用 `./gradlew`。只更新指定模块时，也可将第一条命令的 `--write-locks` 换成 `--update-locks group:module`，随后审查完整锁差异并执行第二条命令。`verifyDependencyLocks` 拒绝 `--write-locks` 与 `--update-locks`，CI 不会通过重写锁掩盖依赖漂移。

每个模块生成 `build/reports/dependency-locks/coverage.tsv`，逐配置记录是否要求非空、外部模块数量、实际 `group:module:version` 集合和解析状态。生成时状态为 `RESOLVED_FOR_WRITE`；它表示本次已解析，锁文件仍需整个 Gradle 命令成功后才能持久化。验证时逐项比对实际模块集合与锁条目，状态为 `VERIFIED`。普通空工具配置可以被记录为 `empty=`；应用、JVM 测试和 R1 设备测试的关键 classpath 必须有外部模块，不能用空锁通过。

验证检查完整依赖图中的未解析依赖，并实际取得外部模块的 artifact。项目内组件不属于 Maven 坐标，不写入模块锁；不要求 AGP 为测试 compile classpath 的本地应用输出在 classes/symbol/metadata 等 artifact 类型间做无类型选择。缺失模块、版本冲突、下载失败和锁缺项均使任务失败，覆盖报告保留有界原因链。

## 已确认漏洞与兼容验证

| 组件 | 本次版本 | 处理依据 |
| --- | --- | --- |
| `protobuf-javalite` 与 `protoc` | `3.25.9`，共用同一版本项 | 修复原 `3.25.3` 的未知 group 递归限制漏洞 [GHSA-735f-pc8j-v9w8 / CVE-2024-7254](https://github.com/protocolbuffers/protobuf/security/advisories/GHSA-735f-pc8j-v9w8)，采用现有 3.25 系列的 [v25.9 补丁](https://github.com/protocolbuffers/protobuf/releases/tag/v25.9) |
| gRPC 传递引入的 Guava | 应用依赖约束 `33.7.2-android` | [33.7.2](https://github.com/google/guava/releases/tag/v33.7.2) 修复 [GHSA-xxph-c9ww-hj94 / CVE-2026-102554](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94) 的反序列化提前分配内存问题 |
| 宿主 UTP Guava / Protobuf Java | `33.7.2-jre` / `3.25.9` | 修复同一批漏洞；Protobuf Java 与应用 lite/protoc 复用版本项，但仅 UTP 配置引入 Java runtime 约束 |
| 宿主 UTP Commons IO | `2.14.0` | [Apache 官方安全说明](https://commons.apache.org/proper/commons-io/security.html) 确认该版本修复 CVE-2024-47554 |
| 宿主 UTP Netty | BOM `4.1.137.Final` | 逐个匹配扫描结果的 `affected.package` 后选择覆盖全部已命中 4.1 系列修复的版本；[官方 BOM](https://github.com/netty/netty/blob/netty-4.1.137.Final/bom/pom.xml) 统一各 4.1 模块，tcnative 保持其独立 2.x 版本 |

Guava 上游从 [33.7.0](https://github.com/google/guava/releases/tag/v33.7.0) 将 Android 支持基线提高到 API 24；对应 [基线调整提交](https://github.com/google/guava/commit/ea0af870d5dc5c982e7e5a3cca783a0cdcfb1d7f) 修改了文档与兼容性检查。本项目保留 minSdk 23，已启用 `desugar_jdk_libs:2.1.5`；升级后需要在 API 23/35 重跑本项目实际 gRPC/生命周期等使用路径。这只能证明本项目测试覆盖的使用面，不能声称整个 Guava 33.7.2 支持 API 23。

当前应用没有发现 `ObjectInputStream`/`readObject` 入口，因此组件命中不等于已证明远端可利用。约束仍消除运行依赖中的已知漏洞版本，不添加反序列化入口或忽略规则。

实际全锁扫描还揭示 AGP 8.6 宿主 UTP 的旧库。不可解析、不可发布的 `utpSecurityConstraints` 仅由 `_internal-unified-test-platform-*` 配置继承，为宿主图提供上述约束及 Netty BOM，不改变应用配置、AGP/Kotlin/Compose 版本，也不把整个 `io.netty` 组强制成一个版本。维护时要检查锁差异范围，并通过真实 API 23/35 UTP 仪器执行验证兼容。其余 lint、Kotlin 或工具配置若出现新漏洞，同样按实际来源分别处理；不得以运行依赖已修复代替全锁扫描。

`ProtobufParsingRegressionTest` 使用项目真实生成的 `Nezha.Task`，验证普通任务、浅层未知 group 的前向兼容、字节数组和流解析的递归拒绝，以及 gRPC marshaller 的错误传播。深层用例仅使用 101 层、202 字节的输入。验收还需运行完整 JVM 测试、release lint、release/R1 构建及 [API 23/35 设备回归](DEVICE_REGRESSIONS.md)。

## OSV 扫描

`.github/workflows/osv.yml` 在推送到 `master`、面向 `master` 的 PR 时扫描提交中的两个 Gradle 锁文件。没有依赖仅在默认分支执行的 cron。扫描 job 仅有 `contents: read` 权限，不上传 Security Events，也不写仓库。

CI 下载官方 OSV-Scanner `2.7.0` Linux amd64 二进制，执行前检查固定摘要：

```text
160c6253e2922e9e0a6a9771c5c4c40a4b3c6090b34a3cdd5e80c96cc2e4218b
```

该值来自 [官方 v2.7.0 发布资产](https://github.com/google/osv-scanner/releases/tag/v2.7.0)。升级扫描器时同时核对官方资产摘要、CLI 参数与扫描行为。所有工作流 Actions 都固定到已核实的完整 commit，并保留版本注释；不能把固定 commit 改回浮动 tag。

本地使用已下载并核实的 Windows 对应二进制：

```powershell
.\build\verification-tools\osv-scanner.exe scan source --lockfile=app/gradle.lockfile --lockfile=core/gradle.lockfile --all-packages --format=json --output-file=build/reports/security/osv-results.json
```

`--all-packages` 保留实际扫描坐标，便于核对覆盖范围。任何非零退出码都使 CI 失败；没有结果 JSON 也视为扫描未完成。零漏洞结论需要成功退出、完整结果和对应锁文件，不能仅凭空日志或缺失报告。扫描器错误日志和退出码也随产物归档，不用漏洞忽略规则清空结果。

## Dependabot 安装与更新

截至 2026-11-06，远端仓库为 `cancundeyingzi/nezha_agent_for_android`，默认分支仍为 `main`，Android 工程在 `master`。GitHub 只从默认分支读取 `.github/dependabot.yml`；本分支增加配置本身尚未激活 Dependabot。[GitHub 配置说明](https://docs.github.com/en/code-security/dependabot/dependabot-version-updates/configuration-options-for-the-dependabot.yml-file)

配置每周一北京时间 04:00 检查 Gradle 和 GitHub Actions，更新 PR 的目标均为 `master`。实际安装步骤：

1. 将本次 `.github/dependabot.yml` 的准确内容以单独、可审查的变更加入默认分支 `main`，无需搬入 Android 工程或修改默认分支。
2. 合入后检查 GitHub 的 Dependabot 更新任务，确认它读取 `master` 的依赖文件、生成的更新 PR 也以 `master` 为目标。
3. 对每个更新 PR 审查版本目录、真实生成的锁文件及 OSV 结果；执行严格锁验证、JVM/lint/构建，涉及运行库时执行设备回归。工具链更新另行审查兼容组合，不自动合并。

这些步骤需要有权维护远端的人员实施；当前代码变更不执行远端安装，也不改变仓库默认分支。

## CI 产物与提交对应

| 产物 | 保存内容 |
| --- | --- |
| `build-evidence-<commit>-<run_id>-<attempt>` | JVM XML/HTML、lint HTML/XML、依赖锁覆盖报告、release/r1test APK 及 `output-metadata.json`、各自 R8 mapping、锁文件、版本目录和 run 元数据 |
| `device-api-<API>-<commit>` | 当次目标/测试 APK、目标 mapping、仪器 XML/HTML、原有独立 `build/device-evidence/run-*` 目录、锁文件、版本目录和对应 workflow attempt 元数据 |
| `osv-<commit>-<run_id>-<attempt>` | 全部扫描坐标和漏洞 JSON、扫描日志/退出码、下载和摘要验证日志、扫描器版本、扫描输入锁文件、版本目录与 run 元数据 |

三个归档步骤均使用 `always()`，成功与失败都保留已经产生的证据，保存 14 天。前置步骤失败时，尚未执行的测试或构建不会凭空产生报告/APK。`scripts/write-ci-metadata.py` 记录实际 checkout 的 commit、工作树是否修改、事件 SHA、run id、attempt、job 及矩阵；PR 构建以实际 checkout 的合并提交为准。APK、mapping 和报告应从同一个 job 产物一起取得，不能拼接不同提交或运行的数据。

仓库根目录 `LICENSE` 从原 `origin/main:LICENSE` 恢复，保留 Apache 2.0 原文及 `Copyright 2020 naiba`。发行签名与 GitHub Release 发布仍由单独的发行流程处理。
