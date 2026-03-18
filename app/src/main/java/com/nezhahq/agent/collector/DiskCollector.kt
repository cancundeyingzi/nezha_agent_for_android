package com.nezhahq.agent.collector

import android.os.Environment
import android.os.StatFs
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import java.io.File

/**
 * 磁盘容量采集器：支持多分区扫描与去重。
 *
 * ## 架构设计
 *
 * ```
 * ┌──────────────────────────────────────────────────────────────┐
 * │                      DiskCollector                          │
 * │                                                              │
 * │  ┌─────────────────┐    ┌─────────────────────────────────┐  │
 * │  │  /proc/mounts   │───▶│  解析挂载点 + 文件系统类型过滤  │  │
 * │  └─────────────────┘    └──────────┬──────────────────────┘  │
 * │                                    │                         │
 * │                         ┌──────────▼──────────────────┐      │
 * │                         │  按设备路径(device)去重      │      │
 * │                         │  避免 FUSE/绑定挂载重复计算  │      │
 * │                         └──────────┬──────────────────┘      │
 * │                                    │                         │
 * │                         ┌──────────▼──────────────────┐      │
 * │                         │  StatFs 获取各分区容量       │      │
 * │                         │  累加 total / used           │      │
 * │                         └──────────┬──────────────────┘      │
 * │                                    │                         │
 * │                         ┌──────────▼──────────────────┐      │
 * │                         │  兜底：至少包含 /data 分区   │      │
 * │                         └─────────────────────────────┘      │
 * └──────────────────────────────────────────────────────────────┘
 * ```
 *
 * ## Android 特殊性
 *
 * 在现代 Android 设备上，`/data` 和 `/storage/emulated/0` 通常是同一物理
 * 分区通过 FUSE/sdcardfs 透传。本采集器通过 **设备路径去重** 避免重复计算。
 *
 * ## 安全审计
 * - `/proc/mounts` 在所有 Android 版本上均可读，无需特殊权限
 * - `StatFs` 是公开 API，无需 Root
 * - Root 模式下使用 `df` 命令获取更精确的数据（可访问系统分区）
 *
 * ## 支持的文件系统类型
 * - ext4 / f2fs：Android 内部存储主流格式
 * - vfat / exfat / ntfs / fuse / sdcardfs：外部 SD 卡和 USB OTG 常见格式
 */
object DiskCollector {

    /**
     * 磁盘容量信息数据类。
     *
     * @property totalBytes  所有分区的总容量（字节）
     * @property usedBytes   所有分区的已用容量（字节）
     */
    data class DiskInfo(val totalBytes: Long, val usedBytes: Long)

    /**
     * 被识别为磁盘的真实文件系统类型集合。
     *
     * 排除了 proc / sysfs / tmpfs / devpts / cgroup 等虚拟文件系统，
     * 只保留承载用户数据的物理/半物理文件系统。
     *
     * 包含 sdcardfs 和 fuse：部分 Android 设备将外部存储以这两种方式挂载，
     * 但通过设备路径去重机制，它们不会与 /data 重复计算。
     */
    private val REAL_FS_TYPES = setOf(
        "ext4", "ext3", "ext2",  // Linux 标准文件系统
        "f2fs",                   // 闪存友好文件系统（Android 主流）
        "vfat", "exfat",          // FAT 家族（SD 卡常用）
        "ntfs", "fuseblk",        // NTFS（USB OTG 常见）
        "xfs", "btrfs"            // 其他 Linux 文件系统（少见但可能存在）
    )

    /**
     * 已知无法被普通应用访问的挂载点前缀。
     * 这些分区由 vendor/firmware 使用，StatFs 必定抛出 IllegalArgumentException。
     * 静默跳过，避免每 2 秒输出 7+ 行日志污染。
     */
    private val SKIP_MOUNT_PREFIXES = arrayOf(
        "/mnt/vendor/",      // vendor 分区（persist, oplusreserve, qmcs 等）
        "/vendor/firmware",  // 固件分区
        "/vendor/bt_",       // 蓝牙固件
        "/mnt/pass_through/" // FUSE 透传（重复挂载，去重无意义）
    )

    /**
     * 读取所有真实磁盘分区的容量信息（去重后累加）。
     *
     * ## 策略
     * 1. 解析 `/proc/mounts` 获取全部挂载点
     * 2. 按文件系统类型过滤，只保留真实磁盘分区
     * 3. 按底层设备路径（`/dev/block/xxx`）去重，避免 FUSE/绑定挂载导致重复
     * 4. 对每个唯一分区调用 `StatFs` 获取容量并累加
     * 5. 兜底：若解析失败或无数据，至少返回 `/data` 分区的容量
     *
     * @param isRootMode 是否处于 Root/Shizuku 提权模式（Root 下可读更多挂载点）
     * @return DiskInfo 包含总量和已用量
     */
    fun getDiskInfo(isRootMode: Boolean): DiskInfo {
        val seenDevices = mutableSetOf<String>()
        var totalBytes = 0L
        var usedBytes = 0L

        val lineProcessor: (String) -> Unit = { line ->
            if (line.isNotBlank()) {
                val parts = line.split(' ')
                if (parts.size >= 3) {
                    val device = parts[0]
                    val mountPoint = parts[1]
                    val fsType = parts[2]

                    if (fsType in REAL_FS_TYPES) {
                        // 静默跳过已知不可访问的 vendor/firmware 分区
                        val shouldSkip = SKIP_MOUNT_PREFIXES.any { prefix ->
                            mountPoint.startsWith(prefix)
                        }
                        if (!shouldSkip) {
                            val deduplicationKey = if (device.startsWith("/dev/")) device else mountPoint
                            if (seenDevices.add(deduplicationKey)) {
                                try {
                                    val sf = StatFs(mountPoint)
                                    val blockSize = sf.blockSizeLong
                                    val partTotal = sf.blockCountLong * blockSize
                                    val partFree = sf.availableBlocksLong * blockSize

                                    if (partTotal > 0) {
                                        totalBytes += partTotal
                                        usedBytes += (partTotal - partFree)
                                    }
                                } catch (_: Exception) {
                                    // StatFs 失败的分区静默跳过（通常是权限不足或路径无效）
                                }
                            }
                        }
                    }
                }
            }
        }

        try {
            if (isRootMode) {
                try {
                    val output = RootShell.execute("cat /proc/mounts")
                    if (!output.isNullOrBlank()) {
                        output.lineSequence().forEach(lineProcessor)
                    }
                } catch (e: Exception) {
                    Logger.e("DiskCollector: Root 模式读取 /proc/mounts 失败，回退到直接读取", e)
                    readMountsDirect(lineProcessor)
                }
            } else {
                readMountsDirect(lineProcessor)
            }

            if (totalBytes > 0) {
                return DiskInfo(totalBytes, usedBytes)
            }
        } catch (e: Exception) {
            Logger.e("DiskCollector: 磁盘容量采集异常", e)
        }

        // 兜底：仅返回 /data 分区（保证不会返回 0）
        return getDataPartitionInfo()
    }

    private fun readMountsDirect(action: (String) -> Unit) {
        try {
            File("/proc/mounts").forEachLine(action = action)
        } catch (e: Exception) {
            Logger.e("DiskCollector: 读取 /proc/mounts 失败", e)
        }
    }



    /**
     * 获取 /data 分区的容量信息（兜底方案）。
     *
     * 当 /proc/mounts 解析失败或返回空数据时，
     * 回退到仅统计 /data 分区，保证至少有基本的磁盘数据。
     *
     * @return DiskInfo /data 分区的总量和已用量
     */
    private fun getDataPartitionInfo(): DiskInfo {
        return try {
            val sf = StatFs(Environment.getDataDirectory().path)
            val total = sf.blockCountLong * sf.blockSizeLong
            val free = sf.availableBlocksLong * sf.blockSizeLong
            DiskInfo(total, total - free)
        } catch (e: Exception) {
            Logger.e("DiskCollector: 读取 /data 分区容量失败", e)
            DiskInfo(0L, 0L)
        }
    }
}
