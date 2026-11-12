package diskspace

// Snapshot 是目标文件系统的字节容量观察；Available 表示当前进程可用空间。
type Snapshot struct {
	Total     int64
	Available int64
}
