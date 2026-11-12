package archive

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"strings"
)

type writeSegment struct {
	name     string
	size     int64
	identity os.FileInfo
}

type finishedSegment struct {
	name string
	size int64
}

type segmentWriter struct {
	ctx   context.Context
	root  *os.Root
	file  *os.File
	parts []writeSegment
	limit int64
	total uint64
}

func (writer *segmentWriter) closeCurrent() error {
	if writer.file == nil {
		return nil
	}
	err := writer.file.Sync()
	err = errors.Join(err, writer.file.Close())
	writer.file = nil
	return err
}
func (writer *segmentWriter) next() error {
	if err := writer.closeCurrent(); err != nil {
		return err
	}
	if len(writer.parts) >= MaxParts {
		return ErrWriteLimit
	}
	name := fmt.Sprintf("part-%03d", len(writer.parts))
	file, err := writer.root.OpenFile(name, os.O_CREATE|os.O_EXCL|os.O_RDWR, 0600)
	if err != nil {
		return err
	}
	writer.file = file
	identity, err := file.Stat()
	if err != nil {
		return err
	}
	writer.parts = append(writer.parts, writeSegment{name: name, identity: identity})
	return nil
}
func (writer *segmentWriter) reserve(size int) error {
	if err := writer.ctx.Err(); err != nil {
		return err
	}
	if writer.limit > 0 && int64(size) > writer.limit {
		return ErrWriteLimit
	}
	if writer.file == nil {
		return writer.next()
	}
	if writer.limit > 0 && writer.parts[len(writer.parts)-1].size+int64(size) > writer.limit {
		return writer.next()
	}
	return nil
}
func (writer *segmentWriter) Write(data []byte) (int, error) {
	written := 0
	for len(data) > 0 {
		if err := writer.ctx.Err(); err != nil {
			return written, err
		}
		if writer.file == nil || writer.limit > 0 && writer.parts[len(writer.parts)-1].size == writer.limit {
			if err := writer.next(); err != nil {
				return written, err
			}
		}
		count := len(data)
		if writer.limit > 0 {
			count = int(min(int64(count), writer.limit-writer.parts[len(writer.parts)-1].size))
		}
		if writer.total+uint64(count) > MaxTotalBytes+MaxDirectoryBytes {
			return written, ErrWriteLimit
		}
		n, err := writer.file.Write(data[:count])
		writer.parts[len(writer.parts)-1].size += int64(n)
		writer.total += uint64(n)
		written += n
		data = data[n:]
		if err != nil {
			return written, err
		}
		if n != count {
			return written, io.ErrShortWrite
		}
	}
	return written, nil
}
func (writer *segmentWriter) patch(disk int, offset int64, data []byte) error {
	if err := writer.ctx.Err(); err != nil {
		return err
	}
	part := writer.parts[disk]
	if offset < 0 || offset+int64(len(data)) > part.size {
		return ErrInvalid
	}
	info, err := writer.root.Lstat(part.name)
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() || !os.SameFile(info, part.identity) || info.Size() != part.size {
		return ErrOutputChanged
	}
	if disk == len(writer.parts)-1 && writer.file != nil {
		n, writeErr := writer.file.WriteAt(data, offset)
		if writeErr == nil && n != len(data) {
			writeErr = io.ErrShortWrite
		}
		return writeErr
	}
	file, err := writer.root.OpenFile(part.name, os.O_RDWR, 0)
	if err != nil {
		return err
	}
	opened, err := file.Stat()
	if err == nil && (!os.SameFile(opened, part.identity) || opened.Size() != part.size) {
		err = ErrOutputChanged
	}
	if err == nil {
		var n int
		n, err = file.WriteAt(data, offset)
		if err == nil && n != len(data) {
			err = io.ErrShortWrite
		}
	}
	if err == nil {
		err = file.Sync()
	}
	return errors.Join(err, file.Close())
}
func (writer *segmentWriter) finishNames(name string) ([]finishedSegment, error) {
	if err := writer.closeCurrent(); err != nil {
		return nil, err
	}
	finished := make([]finishedSegment, 0, len(writer.parts))
	for index, part := range writer.parts {
		info, err := writer.root.Lstat(part.name)
		if err != nil {
			return nil, err
		}
		if !info.Mode().IsRegular() || !os.SameFile(info, part.identity) || info.Size() != part.size {
			return nil, ErrOutputChanged
		}
		final := name
		if index < len(writer.parts)-1 {
			final = strings.TrimSuffix(name, path.Ext(name)) + fmt.Sprintf(".z%02d", index+1)
		}
		if _, err := writer.root.Lstat(final); !errors.Is(err, os.ErrNotExist) {
			if err != nil {
				return nil, err
			}
			return nil, ErrOutputChanged
		}
		if err := writer.root.Rename(part.name, final); err != nil {
			return nil, err
		}
		// 重命名可能改变 inode。后续只按正式卷名打开并验证归档内容。
		finished = append(finished, finishedSegment{name: final, size: part.size})
	}
	return finished, nil
}
