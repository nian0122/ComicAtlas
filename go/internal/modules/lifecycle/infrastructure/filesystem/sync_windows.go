package filesystem

// Windows 用于本地开发；生产 Docker 通过 Linux 实现同步目录。
func (files *Files) syncDirectory(string) error { return nil }
