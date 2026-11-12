package filesystem

import (
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"context"
	"errors"
	"os"
)

// RequireHQAbsent 不读取未登记的原件字节；缺失叶或父目录进入同一本的来源复核。
func (files *Files) RequireHQAbsent(ctx context.Context, comic, name string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if err := recoverydomain.ValidateHQPath(comic, name); err != nil {
		return err
	}
	if err := files.parents(name); err != nil {
		if errors.Is(err, recoverydomain.ErrMissing) {
			return ctx.Err()
		}
		return err
	}
	_, err := files.root.Lstat(name)
	if errors.Is(err, os.ErrNotExist) {
		if files.observe != nil {
			if err = files.observe(name, nil); err != nil {
				return err
			}
		}
		return ctx.Err()
	}
	if err != nil {
		return err
	}
	return recoverydomain.ErrContentMismatch
}
