//go:build android

// Android 专用 GPU 监控实现
// Android 设备上不支持通过命令行工具获取 GPU 信息（nvidia-smi、rocm-smi 等均不存在），
// 因此直接返回空值，避免包级别 init 尝试执行不存在的二进制导致 crash。

package gpu

import "context"

// GetHost 在 Android 上返回空 GPU 信息
// 原因：Android 不支持桌面 GPU 工具（nvidia-smi/rocm-smi/intel_gpu_top）
func GetHost(_ context.Context) ([]string, error) {
	return nil, nil
}

// GetState 在 Android 上返回空 GPU 使用率
func GetState(_ context.Context) ([]float64, error) {
	return nil, nil
}
