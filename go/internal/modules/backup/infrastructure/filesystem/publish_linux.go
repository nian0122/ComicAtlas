package filesystem

import (
	"os"

	"golang.org/x/sys/unix"
)

func publishNoReplace(parent *os.Root, old, next string) error {
	file, err := parent.Open(".")
	if err != nil {
		return err
	}
	defer file.Close()
	return unix.Renameat2(int(file.Fd()), old, int(file.Fd()), next, unix.RENAME_NOREPLACE)
}
