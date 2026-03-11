//go:build android

// Android 专用磁盘监控实现
// 使用 syscall.Statfs 获取磁盘空间信息，绕过 gopsutil 的 psDisk.Partitions
// 原因：psDisk.Partitions 在 Android 上受 SELinux 策略限制，可能无法读取 /proc/mounts
//       或者返回了大量虚拟文件系统分区导致非预期行为

package disk

import (
	"context"

	"golang.org/x/sys/unix"
)

// android 上需要检查的关键挂载点
// /data  - 用户数据分区（最重要）
// /      - 根分区
// /sdcard - 外部存储（如果存在）
var androidMountPoints = []string{"/data", "/", "/sdcard", "/storage/emulated/0"}

// GetHost 返回 Android 设备的磁盘总空间
// 使用 syscall.Statfs 直接查询，不依赖 /proc/mounts 解析
func GetHost(_ context.Context) (uint64, error) {
	var maxTotal uint64

	// Android 上多个挂载点可能指向同一分区
	// 取报告的最大值作为总空间（通常是 /data 分区）
	for _, mp := range androidMountPoints {
		var stat unix.Statfs_t
		if err := unix.Statfs(mp, &stat); err != nil {
			continue
		}

		// 磁盘总空间 = 总块数 × 每块大小
		total := uint64(stat.Blocks) * uint64(stat.Bsize)
		if total > maxTotal {
			maxTotal = total
		}
	}

	return maxTotal, nil
}

// GetState 返回 Android 设备的磁盘已用空间
func GetState(_ context.Context) (uint64, error) {
	var maxUsed uint64

	for _, mp := range androidMountPoints {
		var stat unix.Statfs_t
		if err := unix.Statfs(mp, &stat); err != nil {
			continue
		}

		// 已用空间 = (总块数 - 可用块数) × 每块大小
		total := uint64(stat.Blocks) * uint64(stat.Bsize)
		free := uint64(stat.Bfree) * uint64(stat.Bsize)
		used := total - free
		if used > maxUsed {
			maxUsed = used
		}
	}

	return maxUsed, nil
}
