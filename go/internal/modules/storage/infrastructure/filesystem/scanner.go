package filesystem

import (
	storageports "comicatlas.local/backend/internal/modules/storage/application/ports"
	storagedomain "comicatlas.local/backend/internal/modules/storage/domain"
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
)

type Scanner struct{ root *os.Root }

func New(path string) (*Scanner, error) {
	root, err := os.OpenRoot(path)
	if err != nil {
		return nil, err
	}
	return &Scanner{root: root}, nil
}
func (scanner *Scanner) Close() error { return scanner.root.Close() }

var _ storageports.Scanner = (*Scanner)(nil)

func (scanner *Scanner) Scan(ctx context.Context, progress func(int) error) (storagedomain.ObservedUsage, error) {
	var usage storagedomain.ObservedUsage
	var walk func(string, int) error
	walk = func(path string, depth int) error {
		if err := ctx.Err(); err != nil {
			return err
		}
		if depth > storagedomain.MaxScanDepth {
			return storagedomain.ErrScanLimit
		}
		before, err := scanner.root.Lstat(filepath.FromSlash(path))
		if err != nil || !before.IsDir() {
			return storagedomain.ErrScanRead
		}
		file, err := scanner.root.Open(filepath.FromSlash(path))
		if err != nil {
			return storagedomain.ErrScanRead
		}
		defer file.Close()
		after, err := file.Stat()
		if err != nil || !after.IsDir() || !os.SameFile(before, after) {
			return storagedomain.ErrScanRead
		}
		for {
			entries, readErr := file.ReadDir(128)
			if readErr != nil && !errors.Is(readErr, io.EOF) {
				return storagedomain.ErrScanRead
			}
			for _, entry := range entries {
				if err := ctx.Err(); err != nil {
					return err
				}
				usage.Entries++
				if usage.Entries > storagedomain.MaxScanEntries {
					return storagedomain.ErrScanLimit
				}
				name := entry.Name()
				if path != "." {
					name = path + "/" + name
				}
				info, err := scanner.root.Lstat(filepath.FromSlash(name))
				if err != nil {
					return storagedomain.ErrScanRead
				}
				switch {
				case info.Mode()&os.ModeSymlink != 0:
					usage.IgnoredLinks++
				case info.IsDir():
					if err = walk(name, depth+1); err != nil {
						return err
					}
				case info.Mode().IsRegular():
					if err = storagedomain.AddFile(&usage, name, info.Size()); err != nil {
						return err
					}
				default:
					usage.IgnoredSpecial++
				}
				if usage.Entries%128 == 0 {
					if err = progress(int(usage.Entries)); err != nil {
						return err
					}
				}
			}
			if errors.Is(readErr, io.EOF) {
				return nil
			}
		}
	}
	err := walk(".", 0)
	return usage, err
}
