package filesystem

// Windows 开发测试不执行目录同步；生产 Docker 使用 Linux 同步实现。
func (storage *Storage) syncDirectory(string) error { return nil }
