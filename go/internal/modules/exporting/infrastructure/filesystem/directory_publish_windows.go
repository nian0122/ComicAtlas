package filesystem

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"golang.org/x/sys/windows"
	"os"
	"runtime"
	"unsafe"
)

const directoryPublishSupported = true

// 用目录句柄解析两端，ReplaceIfExists=0；不能用可覆盖目标的普通 Rename 代替。
func renameDirectoryNoReplace(source, target *os.File, name string, expected os.FileInfo) error {
	unicode, err := windows.NewNTUnicodeString(name)
	if err != nil {
		return err
	}
	attributes := windows.OBJECT_ATTRIBUTES{RootDirectory: windows.Handle(source.Fd()), ObjectName: unicode, Attributes: windows.OBJ_DONT_REPARSE}
	attributes.Length = uint32(unsafe.Sizeof(attributes))
	var handle windows.Handle
	var status windows.IO_STATUS_BLOCK
	err = windows.NtCreateFile(&handle, windows.DELETE|windows.SYNCHRONIZE|windows.FILE_READ_ATTRIBUTES, &attributes, &status, nil, 0, windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE, windows.FILE_OPEN, windows.FILE_DIRECTORY_FILE|windows.FILE_OPEN_REPARSE_POINT|windows.FILE_SYNCHRONOUS_IO_NONALERT, 0, 0)
	runtime.KeepAlive(source)
	runtime.KeepAlive(unicode)
	if err != nil {
		return directoryWindowsError(err)
	}
	opened := os.NewFile(uintptr(handle), name)
	defer opened.Close()
	actual, err := opened.Stat()
	if err != nil {
		return err
	}
	if !actual.IsDir() || !os.SameFile(expected, actual) {
		return exportdomain.ErrManifest
	}
	encoded, err := windows.UTF16FromString(name)
	if err != nil {
		return err
	}
	var information struct {
		ReplaceIfExists uint32
		RootDirectory   windows.Handle
		FileNameLength  uint32
		FileName        [128]uint16
	}
	if len(encoded) > len(information.FileName) {
		return exportdomain.ErrValidation
	}
	information.RootDirectory = windows.Handle(target.Fd())
	information.FileNameLength = uint32((len(encoded) - 1) * 2)
	copy(information.FileName[:], encoded)
	err = windows.NtSetInformationFile(handle, &status, (*byte)(unsafe.Pointer(&information)), uint32(unsafe.Sizeof(information)), windows.FileRenameInformation)
	runtime.KeepAlive(target)
	runtime.KeepAlive(opened)
	return directoryWindowsError(err)
}

func directoryWindowsError(err error) error {
	if status, ok := err.(windows.NTStatus); ok {
		return status.Errno()
	}
	return err
}
