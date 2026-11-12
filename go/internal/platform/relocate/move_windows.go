package relocate

import (
	"errors"
	"fmt"
	"golang.org/x/sys/windows"
	"os"
	"runtime"
	"unsafe"
)

// NoReplace 以来源和目标父目录句柄定位，不覆盖已有文件或目录。
func NoReplace(source, target *os.File, oldName, newName string, expected os.FileInfo) error {
	name, err := windows.NewNTUnicodeString(oldName)
	if err != nil {
		return err
	}
	attributes := windows.OBJECT_ATTRIBUTES{RootDirectory: windows.Handle(source.Fd()), ObjectName: name, Attributes: windows.OBJ_DONT_REPARSE}
	attributes.Length = uint32(unsafe.Sizeof(attributes))
	var handle windows.Handle
	var status windows.IO_STATUS_BLOCK
	err = windows.NtCreateFile(&handle, windows.DELETE|windows.SYNCHRONIZE|windows.FILE_READ_ATTRIBUTES, &attributes, &status, nil, 0, windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE, windows.FILE_OPEN, windows.FILE_OPEN_REPARSE_POINT|windows.FILE_SYNCHRONOUS_IO_NONALERT, 0, 0)
	runtime.KeepAlive(source)
	runtime.KeepAlive(name)
	if err != nil {
		return windowsError(err)
	}
	opened := os.NewFile(uintptr(handle), oldName)
	defer opened.Close()
	actual, err := opened.Stat()
	if err != nil {
		return err
	}
	if !os.SameFile(expected, actual) {
		return os.ErrInvalid
	}
	encoded, err := windows.UTF16FromString(newName)
	if err != nil {
		return err
	}
	var information struct {
		ReplaceIfExists uint32
		RootDirectory   windows.Handle
		FileNameLength  uint32
		FileName        [256]uint16
	}
	if len(encoded) > len(information.FileName) {
		return os.ErrInvalid
	}
	information.RootDirectory = windows.Handle(target.Fd())
	information.FileNameLength = uint32((len(encoded) - 1) * 2)
	copy(information.FileName[:], encoded)
	err = windows.NtSetInformationFile(handle, &status, (*byte)(unsafe.Pointer(&information)), uint32(unsafe.Sizeof(information)), windows.FileRenameInformation)
	runtime.KeepAlive(target)
	runtime.KeepAlive(opened)
	return windowsError(err)
}
func windowsError(err error) error {
	if value, ok := err.(windows.NTStatus); ok {
		return value.Errno()
	}
	return err
}
func CrossDevice(err error) bool { return errors.Is(err, windows.ERROR_NOT_SAME_DEVICE) }
func LinkUnsupported(err error) bool {
	return errors.Is(err, windows.ERROR_INVALID_FUNCTION) || errors.Is(err, windows.ERROR_NOT_SUPPORTED)
}
func Identity(file *os.File) (string, error) {
	var info windows.ByHandleFileInformation
	err := windows.GetFileInformationByHandle(windows.Handle(file.Fd()), &info)
	return fmt.Sprintf("%d:%d:%d", info.VolumeSerialNumber, info.FileIndexHigh, info.FileIndexLow), err
}

// Windows 通过写入文件的 FlushFileBuffers 保证检查点落盘；不对只读目录句柄调用 FlushFileBuffers。
func SyncDirectory(file *os.File) error { return nil }
