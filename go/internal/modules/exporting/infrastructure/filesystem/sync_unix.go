//go:build !windows

package filesystem

import (
	"errors"
	"os"
)

func (files *Files) syncDirectory(name string) error {
	return syncDirectoryOn(files.rootFor(name), name)
}

func syncDirectoryOn(root *os.Root, name string) error {
	directory, err := root.Open(name)
	if err != nil {
		return err
	}
	return errors.Join(directory.Sync(), directory.Close())
}
