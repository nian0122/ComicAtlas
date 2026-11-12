//go:build !linux && !windows

package filesystem

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"os"
)

const directoryPublishSupported = false

// 其他平台未实现句柄相对且禁止覆盖的目录发布，不退化成覆盖式 rename。
func renameDirectoryNoReplace(*os.File, *os.File, string, os.FileInfo) error {
	return exportdomain.ErrStorage
}
