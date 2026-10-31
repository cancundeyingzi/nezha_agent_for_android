# R1 GPU 协议兼容

协议固定于 Go Agent `c2b65ab6fa8926f3c7ed4ef3a0065fe003410237`：保留 `State.gpu=17`（`repeated double`），新增 `State.gpus=18`（`repeated State_GPU`）。`State_GPU` 内的 `utilization=1` 为百分比，`memory_used=2` 和 `memory_total=3` 为 `uint64`，单位均为 MiB。

系统每次上报只调用一次现有 `GpuCollector.getGpuUsages(isRootMode)`，由 `addGpuSample` 按原顺序同时生成两个字段。模拟器在原随机 GPU 调用位置复用同一映射，不增加随机取值。采集器的来源、缓存、Adreno/Mali 解析、节流及 GPU 名称查询保持原状。

手机没有可靠显存来源，因此两个显存字段保留 0，表示未知，不读取或换算 RAM 来代替。无样本、采样异常或样本含 NaN/Infinity 时，两个 GPU 列表都为空，其他 State 字段仍可上报；不逐项过滤而移动索引，也不填充虚假的 0% 使用率。真实采到的 0% 保留，取消异常继续向上传播。

JVM 回归覆盖同采样/顺序、真实 0%、未知显存、空样本、异常隔离、非有限样本及取消；固定字节 fixture 验证旧字段 17 的 packed/unpacked 编码和新字段 18/内层字段的往返。只识别旧字段的最小 wire reader 使用 protobuf 标准 unknown-field 路径跳过 18，仍读出旧 GPU 及基础字段。模拟器测试同时检查双字段一致及未知显存。

这些检查验证协议与映射；真实 Android15/17 SukiSU 的 GPU 可读性、系统采集及面板显示仍需设备联调。
