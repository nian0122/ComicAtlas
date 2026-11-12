package filesystem

import (
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"context"
	"errors"
	"io"
	"os"
)

// HQPresence 只判断遗留内容；文件名支持并不代表媒体字节已验证或可以登记。
func (files *Files) HQPresence(ctx context.Context, comic string) (result recoverydomain.HQPresence, err error) {
	defer func() {
		if err != nil {
			result = recoverydomain.HQPresence{}
		}
	}()
	if !recoverydomain.Key(comic) {
		return result, recoverydomain.ErrValidation
	}
	if err = ctx.Err(); err != nil {
		return result, err
	}
	if err = files.parents("hq/" + comic + "/entry"); err != nil {
		return result, err
	}
	entries := 0
	var walk func(string, int) error
	walk = func(name string, depth int) (err error) {
		if err = ctx.Err(); err != nil {
			return err
		}
		if depth > 64 {
			return recoverydomain.ErrLimit
		}
		directory, err := files.directory(name)
		if err != nil {
			return err
		}
		defer func() { err = errors.Join(err, directory.Close()) }()
		before, err := directory.Stat()
		if err != nil {
			return err
		}
		for {
			items, readErr := directory.ReadDir(128)
			if readErr != nil && !errors.Is(readErr, io.EOF) {
				return readErr
			}
			for _, item := range items {
				if err = ctx.Err(); err != nil {
					return err
				}
				entries++
				if entries > recoverydomain.MaxRootEntries {
					return recoverydomain.ErrLimit
				}
				child := name + "/" + item.Name()
				info, err := files.root.Lstat(child)
				if err != nil {
					return err
				}
				if info.Mode()&os.ModeSymlink != 0 {
					return recoverydomain.ErrUnsafe
				}
				switch {
				case info.IsDir():
					result.Directories++
					if err = walk(child, depth+1); err != nil {
						return err
					}
				case info.Mode().IsRegular():
					if mediadomain.UploadMediaKind(item.Name()) != "" {
						result.Media++
					} else {
						result.Other++
					}
				default:
					return recoverydomain.ErrUnsafe
				}
			}
			if errors.Is(readErr, io.EOF) {
				break
			}
		}
		after, err := directory.Stat()
		if err != nil {
			return err
		}
		current, err := files.root.Lstat(name)
		if err != nil {
			return err
		}
		if !os.SameFile(before, current) || !before.ModTime().Equal(after.ModTime()) {
			return recoverydomain.ErrChanged
		}
		return nil
	}
	err = walk("hq/"+comic, 0)
	return result, err
}
