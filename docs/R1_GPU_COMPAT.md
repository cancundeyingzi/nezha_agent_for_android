# R1 GPU 协议兼容

协议固定于 Go Agent `c2b65ab6fa8926f3c7ed4ef3a0065fe003410237`：保留 `State.gpu=17`（`repeated double`），新增 `State.gpus=18`（`repeated State_GPU`）。`State_GPU` 内的 `utilization=1` 为百分比，`memory_used=2` 和 `memory_total=3` 为 `uint64`，单位均为 MiB。

系统每次上报只调用一次现有 `GpuCollector.getGpuUsages(isRootMode)`，由 `addGpuSample` 按原顺序同时生成两个字段。模拟器在原随机 GPU 调用位置复用同一映射，不增加随机取值。采集器的来源、模式缓存、Adreno/Mali 解析和节流保持原状。

成功取得的硬件名称与厂商按 App 进程缓存，多个 Runtime 的采集器共享一次查询结果；名称/厂商以同一 Pair 发布，查询失败后仍可重试。每个采集器的普通/高权策略、路径、采样状态和重置行为仍独立。名称探测的临时 EGL 资源在原清理之后额外释放线程状态，不在释放后调用 EGL 查询。

手机没有可靠显存来源，因此两个显存字段保留 0，表示未知，不读取或换算 RAM 来代替。无样本、采样异常或样本含 NaN/Infinity 时，两个 GPU 列表都为空，其他 State 字段仍可上报；不逐项过滤而移动索引，也不填充虚假的 0% 使用率。真实采到的 0% 保留，取消异常继续向上传播。

JVM 回归覆盖同采样/顺序、真实 0%、未知显存、空样本、异常隔离、非有限样本及取消；固定字节 fixture 验证旧字段 17 的 packed/unpacked 编码和新字段 18/内层字段的往返。只识别旧字段的最小 wire reader 使用 protobuf 标准 unknown-field 路径跳过 18，仍读出旧 GPU 及基础字段。模拟器测试同时检查双字段一致及未知显存。

这些检查验证协议与映射；真实 Android15/17 SukiSU 的 GPU 可读性、系统采集及面板显示仍需设备联调。

API36 / Android16 / 16 KiB 页的冷 App 真实 GPU 名称探测加 100 次 Runtime 重建/MCP 取消回归已完成。最新出货代码在 4 GiB 隔离设备上保持同一 PID，连接时 FD 为 94–95，停止后为 93；PSS 从首轮 94591 KiB 到第 100 轮 96253 KiB，停止后 96173 KiB；各轮清理票为 0，旧命令组均退出。修复前曾观察 FD 从 93 增至 205，同设备的描述符分类和禁用 EGL 对照将问题定位到重复硬件名称探测。这是短时模拟器回归，不替代 24 小时真机后台验收，完整条件见 [R1_VALIDATION.md](R1_VALIDATION.md)。
