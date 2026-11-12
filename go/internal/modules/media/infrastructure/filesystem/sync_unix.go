//go:build !windows

package filesystem

import "path/filepath"

func (storage *Storage) syncDirectory(name string) error {
	file, err := storage.root.Open(filepath.FromSlash(name))
	if err != nil {
		return err
	}
	defer file.Close()
	return file.Sync()
}
