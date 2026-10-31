# R1 与 master 集成决策

本次在独立工作区将 `codex/mcp-r1`（`d8bbfd9`）合入 `origin/master`（`a610e36`），保留版本 `A0.11.0-r1` / `386`。正式包由用户通过 Android Studio 使用已有密钥签名；本次不新增签名配置、密钥或发布工作流。

- 保留 master 的版本目录、Terminal 行纪律、UTF-8 文件名截断、日志缓冲、权限枚举与短信披露、GeoIP 任务替换等修复。版本目录中的 17 个版本均与 R1 构建字面量一致；新增 Gson 2.10.1 别名，没有依赖升级。
- 保留 R1 的 MCP 15/16/17 解析、权限、资源预算和每次 RequestTask 注册独立的任务/结果生命周期。MCP 源码与契约在冲突合并中未改动；8 个旧任务 worker 与 Task 8/9/11 长会话继续属于外层作用域。
- 看门狗统一使用 `util.DashboardSessionWatchdog`，移除 service 下的重复实现。保留 360 秒任务空闲预算、处理器背压共用截止时间、只识别自身超时、可空返回值与清理顺序。原有 master 看门狗测试与 R1 新测试合并保留。
- 文件管理上传失败后永久进入 Aborted；不使用原 master 的 3 秒静默恢复。原有静默恢复断言更新为延迟残余帧仍被丢弃的断言，并保留创建失败、最终保存失败、正常上传等覆盖。保留 master 的 UTF-8 截断及其测试。
- HTTP 探测严格校验证书链与主机名，清除自动合并残留的非校验重试辅助函数；失败日志保留 ID/异常类型，不记录命令、路径、正文或密钥。
- 保留 master 的共享 Process 活性检查与 RemoteShellProcess 传输异常传播，同时保留 R1 的永久 retire、授权 epoch 和 expected-shell 守卫。未恢复 master 已清除的死代码。
- 保留 master 的挂起 GPU 策略和隔离 dumpsys 路径、R1 的进程级成功名称/厂商缓存与 EGL 线程释放。`collectState` 继续为 suspend；GPU 样本映射内联以同时支持挂起设备采样和同步模拟器，字段 17/18 仍只读一次样本。增加挂起读者映射回归测试。
- CI 保留权限只读、单元测试、正式 release lint、release/R1test R8 构建。清除重复 lint 步骤与 `abortOnError=false`；release 不使用 debug 签名。

## 本次静态检查

冲突标记扫描与 `git diff --check` 通过；远端 master 没有测试文件被删除。目录版本核对通过。规格审查和代码质量审查均已通过，以下为本次合并新增的执行验证，不使用 R1 旧测试/手机记录代替。

## 本次合并执行验证

- 使用 Corretto17 与现有 SDK35，在独立工作区完整执行 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleR1test :app:lintRelease`。139 个 Gradle 任务均执行成功。
- core 40 项、app 533 项单测通过，失败、错误、跳过均为 0；两种 R8 构建和阻断式 lint 通过。
- 新合并的 r1test APK 在独立 API36 / 16 KiB 页模拟器安装后，通过真实本地 Dashboard MCP 的 UID、独立输出/exit7、FsRead、FsList、执行中 Shell 撤权后的进程组回收、File 能力继续、Shell 重开与 File 关闭拒绝等检查；本次验证没有操作用户手机。
- 本次将代码整合到 master。按照用户最新安排，正式签名打包和 GitHub Release 由用户通过 Android Studio 处理，没有新增发行密钥或自动发布操作。
