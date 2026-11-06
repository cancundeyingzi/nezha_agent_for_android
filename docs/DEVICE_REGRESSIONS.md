# Android 设备回归

这些用例运行在独立包 `com.nezhahq.agent.r1test`。目标 APK 继承 release 的 R8 配置、启用压缩和混淆、关闭 debuggable，仅采用 debug 签名以便同签名测试 APK 执行 instrumentation。测试启动时检查包名和 `FLAG_DEBUGGABLE`，不会读写正式应用的配置。

`proguard-r1test.pro` 仅为两个 APK 之间的调用保留共享 Kotlin、协程、Compose/生命周期等直接调用的 AndroidX 公开 ABI，以及测试直接访问的具体应用接口。AGP 对测试 APK 应用目标映射，不能还原目标优化时已删除或改变签名的方法。测试 APK 的 R8 任务主要用于应用映射；这里的优化证据指目标 APK。正式 release 不使用这些测试规则；helper 入口仍只依赖生产 `app_process` 保留规则，helper 执行实现继续由 R8 优化。因此这些仪器结果证明带测试 ABI 的优化目标 APK 的行为，不代表正式 release 的每项缩减决策；正式 release 仍需构建及发布包验收。

两个 R1 APK 的 L8 兼容库也使用同一组 app/test DEX 输入追踪实际 API，避免测试 APK 内缩减过的 `j$` 类遮盖应用需要的构造和方法。这项接线仅针对固定 AGP 8.6 的 R1 仪器变体；升级 AGP 时须一并检查 L8 任务接口与双包运行结果。

## 执行与证据

JDK 17、Android SDK 35 下执行：

```powershell
.\gradlew.bat :app:assembleR1test :app:assembleR1testAndroidTest
.\gradlew.bat :app:connectedR1testAndroidTest
```

只连接用于回归的模拟器。CI 在独立的 API 23 `default/x86_64` 和 API 35 `google_apis/x86_64` 模拟器上执行 [设备回归脚本](../scripts/run-device-regressions.sh)。保留每次运行提交号、Android API/镜像标识、Junit XML/HTML、实际安装的目标与测试 APK、对应 R8 mapping。失败时另外保存 logcat 和屏幕截图。测试中只使用合成数据。

CI 启用 AGP 的 `android.injected.androidTest.leaveApksInstalledAfterRun`，先取回应用专属外部目录的逐项失败证据，再卸载两个隔离测试包；API 23 不依赖 UTP 的 additionalTestOutput 支持。运行开始也仅卸载这两个包，避免旧截图混入，不清理其他应用或整台设备。

宿主每次在 `build/device-evidence/run-<随机后缀>/` 建立独立目录，本轮 `run.txt`、取回的逐项失败文件、失败 logcat 和截图均写入该目录。已有运行目录完整保留，CI 继续归档整个 `build/device-evidence/`，避免复跑时把旧失败证据与新运行信息混装。

脚本在任何卸载前要求只连接一台状态为 `device` 的 `emulator-*`，随后固定该 serial。连接真机、多个设备或离线设备时直接拒绝执行包修改。

CI 产物名称为 `device-api-<API>-<commit>`；报告与 APK 必须来自同一产物。单独通过 JVM 测试、生成 APK 或存在旧报告，都不能作为本次设备回归通过的证据。本文件说明执行范围；具体通过状态以对应提交的本次仪器结果为准。

单项 JUnit 用例限时 60 秒；CI 的设备命令另设 15 分钟总时限，覆盖尚未进入 JUnit 的 runner/UTP 启动故障，超时仍执行证据收集。测试运行器会检查 fixture 是否初始化，并在清理异常时继续报告结果。`tracing:1.1.0` 仅加入 `r1test`，满足 AndroidJUnitRunner 1.6.2 的实际 API 调用；正式 release 仍由原有传递依赖选择 tracing 版本。

API 23 的首次旋转回归发现 Shizuku 13.1.5 注销监听器使用 `java.util.function.Predicate` 与 `List.removeIf`，而这些 Java 库 API 从 Android API 24 才提供。应用现启用正式 core library desugaring（`desugar_jdk_libs:2.1.5`），所有 Android 变体保持 minSdk 23 并执行完整注销；不会跳过清理或吞掉加载错误。该依赖支持现有 AGP 8.6，无需升级工具链。[Android Java API 兼容说明](https://developer.android.com/studio/write/java8-support)，[官方兼容版本记录](https://github.com/google/desugar_jdk_libs/blob/master/CHANGELOG.md)。

## 自动化范围

| 范围 | 设备测试实际验证 | 隔离方式 |
| --- | --- | --- |
| Service | 真实前台服务启动、停止、同进程重建；运行时不重叠；gRPC TCP 连接释放；网络回调注册、触发和注销；通知及运行状态 | 合成配置，gRPC 仅连接 `127.0.0.1`；GeoIP 获取返回固定空结果 |
| Activity | 旋转/重建保留草稿与选中页；API 35 静态界面停止重绘、滚动时真实玻璃 View 刷新；退到后台并结束 Activity 后 Service 继续运行 | 真实 MainActivity，测试应用容器 |
| 通知权限 Host | 真实 Activity 重建及 Bundle 恢复后，等待中的请求不重复发起；新 Registry 按重建前的请求码返回实际发起 ID；重复结果不重复交付；旧意图停止并创建后继意图后，旧结果仍携旧 ID，后继请求随后独立发起 | `r1test` 专用、非导出的 Activity 运行生产 Host 叶子逻辑；可控 Registry 延迟结果，不请求系统权限弹窗；API 23、35 均验证 AndroidX 路由 |
| 配置存储 | Android Keystore AES-GCM、Android DataStore 写入与关闭重开；旧明文和真实 EncryptedSharedPreferences 迁移；`.bak` 备份优先且旧加密源字节保持；密钥或目标丢失不自动重建；显式重置恢复 | 独立文件、SharedPreferences 名称和 Keystore alias，合成密钥内容 |
| 上传 | 接收部分数据后取消，目标不被覆盖，临时文件和会话工作结束 | 真实应用沙箱文件，受控 IOStream |
| 传统任务解析 | 默认 Android `JSONObject` 解析 terminal/NAT/file；非法 JSON、StreamID UTF-8 超长、非法/超长 NAT Host、重复 StreamID 和配额超限产生失败结果；取消后配额归零 | 合成任务，执行器仅登记并等待取消，不启动真实终端、不转发流量、不连接面板 |
| MCP 普通权限 helper | 由生产 launcher 经 `app_process` 加载 R8 后的入口；命令、应用 UID、文件读往返及清理；HELLO 接纳前取消；真实 PID 从 `/proc` 消失，API 23 三根原始 FD 关闭；API 35 列举，API 23 明确拒绝不支持的有界列举 | 独立沙箱目录；测试返回未包装的原始 Process，不直接引用或反射 helper 入口类 |
| 权限边界 | 注入 adapter 模拟 Root 开关撤销、Shizuku 即时拒绝/Binder 替换；已登记普通 UID 进程退出、旧许可失效且不回退 Root | **模拟授权边界**，不会申请 Root 或 Shizuku 权限 |

网络回调使用 Android 的真实 `ConnectivityManager`，仅替换 GeoIP 的外部请求函数。gRPC fixture 接收实际 TCP/HTTP2 数据；停止后的 EOF 与资源计数共同确认释放。测试不连接真实面板或节点。取消与状态转换使用有界条件等待，失败会报告未达到的条件。

`NotificationPermissionHostRegressionTest` 使用 `ActivityScenario.recreate()` 创建新的 Activity 和 `ActivityResultRegistry`，通过真实 `onSaveInstanceState`/`onCreate` 的 Bundle 保存与恢复 Host 的 `rememberSaveable` 状态、launcher key 和 Registry 映射。受控 `onLaunch` 仅记录请求码，随后通过真实 `RequestPermission.parseResult` 分发合成结果。首次恢复后的请求码必须能路由；重复分发可以由 Registry 拒绝，但不能再次交付已消费的请求。该范围不覆盖系统权限弹窗、实际授权变化或厂商权限界面；测试不会撤销现有通知权限。宿主只在 `src/r1test` 中声明，正式 release 不包含该 Activity。

API 23 的旧 Java ProcessManager 只等待原进程组，已隔离 helper 留在登记表中还会触发 ECHILD 忙循环。适配在启动前核对固定 libcore 结构，启动后记录原始 Process/弱引用与 UID/PPID/startTime；确认同一子进程已拥有独立会话后，在登记锁内清除原弱引用并移除该登记，将回收权转交给原 proof。已隔离的启动结果立即转交，其他结果须在接受 HELLO 前完成；HELLO 前取消也由同一 proof 清理。原生非阻塞 waitpid 成功后仅向原 Process 发布退出值，发布重试不会再次等待数字 PID。僵尸状态、ECHILD、身份变化和转交失败均不能释放票据；普通进程仍由系统回收。API 24+ 保持 Java Process 回收路径；厂商若改变 API 23 的私有结构，启动会明确拒绝，不猜测其他私有入口。

需要观察旧回收线程 CPU 时，可显式设置仪器参数 `helperObservationMillis`（0–5000，默认 0）。仅该诊断路径会在 helper 已运行后启动并回收一个同组短进程，再保留有界观察窗口；常规 CI 不加入这个等待。

## 仍需真机验收

以下项目不能由上述模拟授权 adapter 或普通模拟器替代：

1. SukiSU 授权管理器撤权：helper/命令运行中撤销授权，检查新请求拒绝、既有资源退出证明、恢复授权后受控清理。记录管理器和内核版本。
2. Shizuku 管理器撤权、服务死亡、重新启动与 Binder 替换：检查旧许可失效、无 `su` 回退、恢复后才重新授权。记录 Shizuku 版本及启动方式。
3. 应用进程死亡与系统重启后的恢复，以及不同厂商的后台限制、Doze、通知权限拒绝和电池策略。自动化中的同进程 Service 重建、Activity 旋转不能代表这些场景。
4. 真实硬件 Keystore 的密钥失效/设备凭据变化，以及外部存储/FUSE、VPN、悬浮窗和无障碍功能的厂商差异。

手工验收使用专用测试包与合成面板，不操作日常使用的应用配置。记录提交、APK、系统/API、授权管理器版本、操作步骤、期望与实际行为；任何未执行项保留“未验证”。
