package domain

import (
	"path/filepath"
	"strings"
)

// ContainsDirectory 仅比较已规范化的绝对路径，解析链接由文件适配器负责。
func ContainsDirectory(parent, child string) (bool, error) {
	if !filepath.IsAbs(parent) || !filepath.IsAbs(child) {
		return false, ErrValidation
	}
	relative, err := filepath.Rel(parent, child)
	if err != nil {
		if !strings.EqualFold(filepath.VolumeName(parent), filepath.VolumeName(child)) {
			return false, nil
		}
		return false, ErrValidation
	}
	return relative == "." || filepath.IsLocal(relative), nil
}

func ValidateSourceDirectories(data, media string) error {
	contained, err := ContainsDirectory(media, data)
	if err != nil || contained {
		// 不能把 SQLite 主库、日志和实例锁当作普通媒体复制。
		return ErrValidation
	}
	return nil
}
