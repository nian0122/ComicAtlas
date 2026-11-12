package filesystem

// Windows 开发环境不执行目录同步；生产 Docker 使用 Linux 实现。
func (scanner *Scanner) syncDirectory(string) error { return nil }
