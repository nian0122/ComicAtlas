package config

import (
	"fmt"
	"path/filepath"
	"strings"
)

// LoadDirectories 供离线命令复用路径配置，不读取网络、AI 或工具设置。
func LoadDirectories(getenv func(string) string) (Directories, error) {
	var result Directories
	for _, item := range []struct {
		name, fallback string
		target         *string
	}{{"DATA_DIR", ".runtime/data", &result.Data}, {"IMPORT_DIR", ".runtime/import", &result.Import}, {"MEDIA_DIR", ".runtime/media", &result.Media}} {
		value := strings.TrimSpace(getenv("COMICATLAS_" + item.name))
		if value == "" {
			value = item.fallback
		}
		absolute, err := filepath.Abs(value)
		if err != nil {
			return Directories{}, fmt.Errorf("解析 %s 失败", item.name)
		}
		*item.target = absolute
	}
	return result, nil
}
