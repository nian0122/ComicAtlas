package filesystem

import "os"

// Windows 开发环境仅同步文件；生产 Docker 使用 Linux 的目录同步。
func syncDirectory(*os.Root, string) error { return nil }
