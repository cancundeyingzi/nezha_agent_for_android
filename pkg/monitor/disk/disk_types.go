package disk

// DiskKeyType 是 Disk 上下文键的类型
type DiskKeyType string

// DiskKey 用于在 context 中传递磁盘分区白名单配置
const DiskKey DiskKeyType = "disk"
