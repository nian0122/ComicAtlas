package application

import (
	"context"
	"path/filepath"
	"strings"
	"unicode/utf8"

	ingestiondomain "comicatlas.local/backend/internal/modules/ingestion/domain"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
)

// SetHostRoot 在启动时配置绑定挂载的宿主机根；空值用于原生进程的本机绝对路径。
func (directory *Directory) SetHostRoot(root string) error {
	if root == "" {
		directory.hostRoot = ""
		return nil
	}
	value, err := ingestiondomain.HostImportPath(root)
	if err != nil {
		return err
	}
	directory.hostRoot = value
	return nil
}

// SourcePath 统一处理单本、目录扫描和压缩包批量入口。
func (directory *Directory) SourcePath(value string, allowRoot bool) (string, error) {
	if directory.hostRoot != "" {
		relative, err := ingestiondomain.RelativeHostImportSource(directory.hostRoot, value)
		if err != nil {
			return "", err
		}
		value = filepath.Join(directory.input.Name(), filepath.FromSlash(relative))
	}
	if !filepath.IsAbs(value) || !utf8.ValidString(value) || strings.ContainsRune(value, 0) || len(value) > 4096 {
		return "", jobsdomain.ErrValidation
	}
	root, err := filepath.Abs(directory.input.Name())
	if err != nil {
		return "", err
	}
	relative, err := filepath.Rel(root, filepath.Clean(value))
	if err != nil || !filepath.IsLocal(relative) || !allowRoot && relative == "." {
		return "", jobsdomain.ErrValidation
	}
	relative = filepath.ToSlash(relative)
	if relative != "." && jobsdomain.ValidateImportSource(relative) != nil {
		return "", jobsdomain.ErrValidation
	}
	return relative, nil
}

func (directory *Directory) Submit(ctx context.Context, archive bool, source, title, key string) (jobsdomain.Task, error) {
	relative, err := directory.SourcePath(source, false)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	if archive {
		return directory.store.CreateArchive(ctx, relative, title, key)
	}
	return directory.store.Create(ctx, relative, title, key)
}
