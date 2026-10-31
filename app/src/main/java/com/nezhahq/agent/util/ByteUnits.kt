package com.nezhahq.agent.util

/**
 * 二进制字节单位换算常量。
 *
 * 抽出来的原因：这组常量此前在 `executor/AgentCommandHandler` 与
 * `simulator/RandomDeviceFactory` 各定义了一份，两份的层级还不一致
 * （前者以 KIB 为基递推，后者直接从 MIB 起算）。分散定义的换算基数
 * 一旦有人按十进制的 1000 改错一处，两边格式化出的容量就会静默地对不上，
 * 而这种偏差在面板上只表现为"数字有点怪"，很难追到源头。
 *
 * 这里统一采用 IEC 二进制前缀（1 KiB = 1024 B），与 Android 系统
 * 存储/内存相关 API 返回值的口径一致。
 */
internal object ByteUnits {
    const val KIB = 1024L
    const val MIB = 1024L * KIB
    const val GIB = 1024L * MIB
}
