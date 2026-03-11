//go:build android

// Android 专用网络连接数监控实现
// 直接读取 /proc/net/tcp 和 /proc/net/udp 统计连接数，绕过 goss 库
// 原因：goss 使用 netlink socket，Android 非 root 应用无权限创建 AF_NETLINK socket，
//       会导致 crash 或 panic

package conn

import (
	"bufio"
	"context"
	"os"
)

// GetState 通过读取 /proc/net/tcp{,6} 和 /proc/net/udp{,6} 统计连接数
// 每个文件的第一行为表头，后续每行代表一个连接
func GetState(_ context.Context) ([]uint64, error) {
	tcpCount := countLines("/proc/net/tcp") + countLines("/proc/net/tcp6")
	udpCount := countLines("/proc/net/udp") + countLines("/proc/net/udp6")

	return []uint64{tcpCount, udpCount}, nil
}

// countLines 统计 /proc/net/* 文件中的连接数（总行数 - 1 表头行）
func countLines(path string) uint64 {
	f, err := os.Open(path)
	if err != nil {
		return 0
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	var count uint64
	for scanner.Scan() {
		count++
	}
	// 减去第一行表头
	if count > 0 {
		count--
	}
	return count
}
