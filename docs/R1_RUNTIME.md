# R1 MCP 运行时

Runtime 现已安装 Task15 Exec、Task16 FsList、Task17 FsRead。Task12–14、18–20 继续返回 `successful=false` 的明确 unsupported。旧 Task4、Task8/9/11 的执行和会话协议继续沿用原入口。

每次 `RequestTask` 注册都有独立 `McpSession`：最多 2 个 Exec，FsList/FsRead 合计最多 2 个；收到请求时立即准入，容量已满便回传同族 `busy`，不进入旧 8 个短任务 worker。请求以原 JSON 的实际 UTF-8 字节计费，单条上限 1 MiB、会话合计 4 MiB，准入 lease 保留到协调任务完成结果交接或丢弃。

结果的最终 protobuf 上限为 3 MiB，会话预算为 6 MiB。helper transport 在分配 RESULT payload 前预留预算；解析错误、内部错误、撤权和忙响应也先预留 1024 字节再编码。预算等待器按代次广播释放通知，关闭会话唤醒全部等待者。容量为 4 的 Owned 队列持有预算，出站通过单个 flow 直接公平选择旧结果或本会话结果；直到 gRPC 的真正 collector 完成发送或丢弃才归还预算。

准入登记、永久撤权标记和正常结果发布由同一把锁决定。后端 child 在登记后才启动，执行前再查最新授权；关闭 Shell 或 File 权限会取消对应的活动 MCP 后端，并等待其清理/交接，然后在会话仍有效时只回一次同族 `permission_denied`。重新开启权限不会复活旧任务。授权更新不等待网络或结果预算；已经发布的正常结果保留。helper lease 的关闭可能只完成异步清理票交接，不代表所有原生进程已退出。

Launcher 按 Runtime 的 `config.rootMode` 选择一次。普通模式不发现高权限后端；Root 失败不降级。Root 模式变更仍需停止服务、保存后再启动。Runtime 不额外启动 su、不发送盲 kill，也不改动 helper 的权限和清理策略。

任务流空闲期限为 360 秒，给最长 300 秒的 Exec 留出传输和收尾时间。同一份最后收包期限同时覆盖 handler 背压和下一次接收。只有内部 idle 到期才重建任务流，其他异常和外部取消正常传播。旧 producer 与全部流内任务取消并 join 后，才关闭旧 MCP 的预算和 Owned 队列、登记新会话；旧终端、NAT 和文件管理长会话仍属于外层 scope。

仅上报真实节点 `Host.version` 的边界使用 `2.1.0+android.<实际 App 版本>`，用于 Dashboard v2.6.1 的 MCP 入口判断。APK 版本、界面版本和模拟节点不随之改变；这个标识只承诺上述 15–17，不能代表 Go Agent 的完整能力。

机制测试覆盖准入与 UTF-8 计费、预算释放和关闭时多等待者唤醒、最终 collector 阻塞、排队与发送取消、旧会话晚完成、启动前/运行中撤权、同族拒绝语义，以及 handler 背压期间的 idle 重建。真机 SukiSU、长期后台、原发行签名覆盖安装仍属于设备验收。
