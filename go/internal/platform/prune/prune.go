// Package prune 删除受控根内已确认为空的暂存目录。
package prune

import (
	"os"
	"path/filepath"
)

// EmptyDirectories 按传入顺序尝试删除 root 内的空目录，调用方须由深到浅排列。
// 只删除确实为目录且没有子项的路径；非空目录、缺失路径、普通文件、链接和
// 其他异常对象一律保留。目录属于可重建的暂存空壳，删除失败不改变业务结果，
// 因此不向调用方返回错误；需要保留现场的内容由启动清扫的其余阶段负责。
func EmptyDirectories(root *os.Root, names ...string) {
	for _, name := range names {
		if !filepath.IsLocal(name) || filepath.ToSlash(filepath.Clean(name)) != name {
			continue
		}
		info, err := root.Lstat(filepath.FromSlash(name))
		if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			continue
		}
		_ = root.Remove(filepath.FromSlash(name))
	}
}
