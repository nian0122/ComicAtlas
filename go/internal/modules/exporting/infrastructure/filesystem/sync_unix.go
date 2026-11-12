//go:build !windows

package filesystem

import "errors"

func (files *Files) syncDirectory(name string) error {
	directory, err := files.root.Open(name)
	if err != nil {
		return err
	}
	return errors.Join(directory.Sync(), directory.Close())
}
