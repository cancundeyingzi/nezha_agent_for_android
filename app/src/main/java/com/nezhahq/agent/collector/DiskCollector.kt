package com.nezhahq.agent.collector

import android.os.Environment
import android.os.StatFs
import com.nezhahq.agent.util.Logger
import com.nezhahq.agent.util.RootShell
import java.io.File

/** 磁盘容量信息，单位为字节。 */
data class DiskInfo(val totalBytes: Long, val usedBytes: Long)

/** Supplies `/proc/mounts` lines, privileged or not. */
internal fun interface MountTableSource {
    fun readMountLines(privileged: Boolean): List<String>
}

/** Reports a mount point's capacity, or null when it cannot be measured. */
internal fun interface PathCapacityProbe {
    fun capacityOf(path: String): DiskInfo?
}

/**
 * 磁盘容量采集器。
 *
 * ## Android 容量语义
 *
 * Android 设备上的“256GB 存储”主要体现在 `/data` 用户数据分区。`/storage/emulated/0`
 * 通常只是同一个分区通过 FUSE/sdcardfs 暴露出来的用户视图；`/system`、`/vendor`、
 * `/product` 等分区虽然也是真实块设备，但它们容量很小且不代表用户可用的整机存储。
 *
 * 因此采集策略是：
 * 1. 先用 `StatFs(/data)` 获取内部存储基准，普通 App 权限即可访问。
 * 2. 再扫描 `/proc/mounts`，只把可确认不是 `/data` 镜像的 SD 卡/USB OTG 等附加存储合并进去。
 * 3. 如果 `/data` 极端情况下不可读，才使用挂载表中可访问的内部存储视图或其它块设备兜底。
 *
 * 这样可以避免普通模式只扫到几个系统分区时，把 256GB 设备误报成约 8GB。
 */
object DiskCollector {
    private val calculator by lazy {
        DiskUsageCalculator(
            mountTable = AndroidMountTableSource,
            capacityProbe = AndroidPathCapacityProbe,
            internalStoragePath = Environment.getDataDirectory().path
        )
    }

    /**
     * 获取设备磁盘容量。
     *
     * @param isRootMode 是否允许通过 Root/Shizuku shell 读取挂载表。即使为 true，也不会
     *                   通过 shell 执行容量计算，容量仍由公开 API `StatFs` 读取。
     */
    fun getDiskInfo(isRootMode: Boolean): DiskInfo = calculator.getDiskInfo(isRootMode)
}

private object AndroidMountTableSource : MountTableSource {
    override fun readMountLines(privileged: Boolean): List<String> =
        if (privileged) readByRoot().ifEmpty { readDirect() } else readDirect()

    private fun readByRoot(): List<String> = try {
        RootShell.execute("cat /proc/mounts")
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()
    } catch (e: Exception) {
        Logger.e("DiskCollector: Root 模式读取挂载信息失败，回退到普通读取", e)
        emptyList()
    }

    private fun readDirect(): List<String> = try {
        File("/proc/mounts").useLines { lines ->
            lines.filter { it.isNotBlank() }.toList()
        }
    } catch (e: Exception) {
        Logger.e("DiskCollector: 读取挂载信息失败", e)
        emptyList()
    }
}

private object AndroidPathCapacityProbe : PathCapacityProbe {
    override fun capacityOf(path: String): DiskInfo? = try {
        val stat = StatFs(path)
        val blockSize = stat.blockSizeLong
        val total = safeMultiply(stat.blockCountLong, blockSize)
        val available = safeMultiply(stat.availableBlocksLong, blockSize)
        val used = (total - available).coerceIn(0L, total)

        if (total > 0L) DiskInfo(total, used) else null
    } catch (_: Exception) {
        null
    }

    private fun safeMultiply(left: Long, right: Long): Long {
        if (left <= 0L || right <= 0L) return 0L
        return if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right
    }
}

/**
 * Mount-table classification and capacity merging, with the two platform touch points injected.
 *
 * Extracting this follows the pattern the collector package already uses for [ProcNetDevReader]
 * and [CpuUsageSampler]: the rules deciding what counts as user-visible storage are worth testing,
 * and they need no device to run.
 */
internal class DiskUsageCalculator(
    private val mountTable: MountTableSource,
    private val capacityProbe: PathCapacityProbe,
    private val internalStoragePath: String
) {
    fun getDiskInfo(isRootMode: Boolean): DiskInfo {
        val dataPartition = capacityProbe.capacityOf(internalStoragePath) ?: run {
            Logger.e("DiskCollector: 读取数据分区容量失败")
            DiskInfo(0L, 0L)
        }
        val mountScan = try {
            scanMounts(isRootMode)
        } catch (e: Exception) {
            Logger.e("DiskCollector: 扫描挂载点失败，使用数据分区基准结果", e)
            ScanResult()
        }

        val internal = if (dataPartition.totalBytes > 0L) dataPartition else mountScan.internal

        val userVisibleStorage = internal + mountScan.external
        if (userVisibleStorage.totalBytes > 0L) {
            return userVisibleStorage
        }

        return mountScan.fallbackBlock
    }

    private fun scanMounts(isRootMode: Boolean): ScanResult {
        val partitions = readMountEntries(isRootMode).mapNotNull { entry ->
            val role = classifyMount(entry) ?: return@mapNotNull null
            val info = capacityProbe.capacityOf(entry.mountPoint) ?: return@mapNotNull null
            ScannedPartition(entry, info, role)
        }

        return ScanResult(
            internal = mergePartitions(partitions, PartitionRole.INTERNAL),
            external = mergePartitions(partitions, PartitionRole.EXTERNAL),
            fallbackBlock = mergePartitions(partitions, PartitionRole.FALLBACK_BLOCK)
        )
    }

    private fun readMountEntries(isRootMode: Boolean): List<MountEntry> =
        mountTable.readMountLines(isRootMode).mapNotNull(::parseMountLine)

    private fun parseMountLine(line: String): MountEntry? {
        val parts = line.trim().split(MOUNT_FIELD_SEPARATOR, limit = 4)
        if (parts.size < 3) return null

        return MountEntry(
            device = decodeMountField(parts[0]),
            mountPoint = decodeMountField(parts[1]),
            fsType = parts[2].lowercase()
        )
    }

    private fun classifyMount(entry: MountEntry): PartitionRole? {
        val mountPoint = entry.mountPoint
        val fsType = entry.fsType

        if (fsType !in FALLBACK_BLOCK_FS_TYPES) return null
        if (shouldSkipMount(mountPoint)) return null

        if (isInternalStorageView(mountPoint)) {
            return PartitionRole.INTERNAL
        }

        if (isExternalStorageMount(entry)) {
            return PartitionRole.EXTERNAL
        }

        if (fsType in INTERNAL_FS_TYPES && !isSystemMount(mountPoint)) {
            return PartitionRole.FALLBACK_BLOCK
        }

        return null
    }

    private fun isInternalStorageView(mountPoint: String): Boolean {
        if (isPathAtOrUnder(mountPoint, "/data")) return true
        if (isPathAtOrUnder(mountPoint, "/sdcard")) return true
        if (isPathAtOrUnder(mountPoint, "/storage/emulated")) return true
        if (isPathAtOrUnder(mountPoint, "/storage/self")) return true

        return (mountPoint.startsWith("/mnt/user/") && hasPathSegment(mountPoint, "emulated")) ||
            (mountPoint.startsWith("/mnt/runtime/") && hasPathSegment(mountPoint, "emulated")) ||
            (mountPoint.startsWith("/mnt/pass_through/") && hasPathSegment(mountPoint, "emulated"))
    }

    private fun isExternalStorageMount(entry: MountEntry): Boolean {
        val mountPoint = entry.mountPoint
        val fsType = entry.fsType

        if (isSystemMount(mountPoint) || isInternalStorageView(mountPoint)) {
            return false
        }

        val volumeId = extractVolumeId(mountPoint)
        if (volumeId != null && volumeId !in INTERNAL_VOLUME_IDS) {
            return fsType in EXTERNAL_FS_TYPES || entry.device.startsWith("/dev/")
        }

        return fsType in EXTERNAL_FS_TYPES &&
            entry.device.startsWith("/dev/") &&
            !mountPoint.startsWith("/mnt/vendor/")
    }

    private fun shouldSkipMount(mountPoint: String): Boolean {
        return SKIP_MOUNT_PREFIXES.any { prefix -> mountPoint.startsWith(prefix) } ||
            isSystemMount(mountPoint)
    }

    private fun isSystemMount(mountPoint: String): Boolean {
        return SYSTEM_MOUNT_PREFIXES.any { prefix -> isPathAtOrUnder(mountPoint, prefix) }
    }

    private fun mergePartitions(
        partitions: List<ScannedPartition>,
        role: PartitionRole
    ): DiskInfo {
        val seenKeys = mutableSetOf<String>()
        var merged = DiskInfo(0L, 0L)

        partitions
            .asSequence()
            .filter { it.role == role }
            .sortedBy { mountPriority(it.entry.mountPoint) }
            .forEach { partition ->
                val keys = deduplicationKeys(partition)
                if (keys.any { it in seenKeys }) return@forEach

                seenKeys += keys
                merged += partition.info
            }

        return merged
    }

    private fun deduplicationKeys(partition: ScannedPartition): Set<String> {
        val keys = mutableSetOf<String>()
        val entry = partition.entry
        val rolePrefix = partition.role.name.lowercase()

        extractVolumeId(entry.mountPoint)?.let { volumeId ->
            keys += "$rolePrefix:volume:$volumeId"
        }

        if (keys.isEmpty() && isUniqueBlockDevice(entry.device)) {
            keys += "$rolePrefix:device:${entry.device}"
        }

        if (keys.isEmpty()) {
            keys += "$rolePrefix:capacity:${partition.info.totalBytes}:${partition.info.usedBytes}"
        }

        return keys
    }

    private fun mountPriority(mountPoint: String): Int {
        return when {
            mountPoint.startsWith("/mnt/media_rw/") -> 0
            mountPoint.startsWith("/storage/") -> 1
            mountPoint.startsWith("/mnt/runtime/") -> 2
            mountPoint.startsWith("/mnt/user/") -> 3
            else -> 4
        }
    }

    private fun extractVolumeId(mountPoint: String): String? {
        val segments = mountPoint.trim('/').split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return null

        return when {
            segments.size >= 2 && segments[0] == "storage" -> segments[1]
            segments.size >= 3 && segments[0] == "mnt" && segments[1] == "media_rw" -> segments[2]
            segments.size >= 4 && segments[0] == "mnt" && segments[1] == "runtime" -> segments[3]
            segments.size >= 4 && segments[0] == "mnt" && segments[1] == "user" -> segments[3]
            segments.size >= 4 && segments[0] == "mnt" && segments[1] == "pass_through" -> segments[3]
            segments.size >= 3 && segments[0] == "mnt" && segments[1] == "expand" -> segments[2]
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private fun decodeMountField(value: String): String {
        if ('\\' !in value) return value

        val decoded = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            val octalEnd = index + 4
            if (char == '\\' && octalEnd <= value.length) {
                val octal = value.substring(index + 1, octalEnd)
                val codePoint = octal.toIntOrNull(radix = 8)
                if (codePoint != null) {
                    decoded.append(codePoint.toChar())
                    index = octalEnd
                    continue
                }
            }

            decoded.append(char)
            index++
        }

        return decoded.toString()
    }

    private fun isUniqueBlockDevice(device: String): Boolean {
        return device.startsWith("/dev/") &&
            device != "/dev/fuse" &&
            device != "/dev/sdcardfs"
    }

    private fun hasPathSegment(path: String, segment: String): Boolean {
        return path.trim('/').split('/').any { it == segment }
    }

    private fun isPathAtOrUnder(path: String, prefix: String): Boolean {
        return path == prefix || path.startsWith("$prefix/")
    }

    private operator fun DiskInfo.plus(other: DiskInfo): DiskInfo {
        return DiskInfo(
            totalBytes = addSaturating(totalBytes, other.totalBytes),
            usedBytes = addSaturating(usedBytes, other.usedBytes)
        )
    }

    private data class MountEntry(
        val device: String,
        val mountPoint: String,
        val fsType: String
    )

    private data class ScannedPartition(
        val entry: MountEntry,
        val info: DiskInfo,
        val role: PartitionRole
    )

    private enum class PartitionRole {
        INTERNAL,
        EXTERNAL,
        FALLBACK_BLOCK
    }

    private data class ScanResult(
        val internal: DiskInfo = DiskInfo(0L, 0L),
        val external: DiskInfo = DiskInfo(0L, 0L),
        val fallbackBlock: DiskInfo = DiskInfo(0L, 0L)
    )

    private companion object {
        val MOUNT_FIELD_SEPARATOR = Regex("\\s+")

        val INTERNAL_FS_TYPES = setOf("ext4", "ext3", "ext2", "f2fs")

        val EXTERNAL_FS_TYPES = setOf(
            "vfat", "exfat",
            "ntfs", "fuseblk",
            "fuse", "sdcardfs"
        )

        val FALLBACK_BLOCK_FS_TYPES = INTERNAL_FS_TYPES + EXTERNAL_FS_TYPES + setOf("xfs", "btrfs")

        val SYSTEM_MOUNT_PREFIXES = arrayOf(
            "/system",
            "/system_ext",
            "/vendor",
            "/product",
            "/odm",
            "/oem",
            "/apex",
            "/metadata",
            "/firmware"
        )

        val SKIP_MOUNT_PREFIXES = arrayOf(
            "/mnt/vendor/",
            "/vendor/firmware",
            "/vendor/bt_"
        )

        val INTERNAL_VOLUME_IDS = setOf("emulated", "self", "primary", "obb")
    }
}
