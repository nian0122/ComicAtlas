package relocate

import (
	"os"
	"path"
	"path/filepath"
)

// WithinRoot 在已验证的媒体根内通过父目录句柄移动，不覆盖目标，也不复制内容。
// 调用方负责祖先路径的业务约束、单实例写入隔离以及移动前后的内容核验。
func WithinRoot(root *os.Root, source, target string, expected os.FileInfo) error {
	if !filepath.IsLocal(source) || !filepath.IsLocal(target) || filepath.ToSlash(filepath.Clean(source)) != source || filepath.ToSlash(filepath.Clean(target)) != target {
		return os.ErrInvalid
	}
	from, err := root.Open(filepath.FromSlash(path.Dir(source)))
	if err != nil {
		return err
	}
	defer from.Close()
	to, err := root.Open(filepath.FromSlash(path.Dir(target)))
	if err != nil {
		return err
	}
	defer to.Close()
	return NoReplace(from, to, path.Base(source), path.Base(target), expected)
}
