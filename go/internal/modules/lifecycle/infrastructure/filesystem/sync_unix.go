//go:build !windows

package filesystem

import "path/filepath"

func (files *Files) syncDirectory(name string) error {
	directory, err := files.root.Open(filepath.FromSlash(name))
	if err != nil {
		return err
	}
	defer directory.Close()
	return directory.Sync()
}
