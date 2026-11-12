//go:build !windows

package filesystem

import "path/filepath"

func (scanner *Scanner) syncDirectory(name string) error {
	file, err := scanner.root.Open(filepath.FromSlash(name))
	if err != nil {
		return err
	}
	defer file.Close()
	return file.Sync()
}
