package archive

import (
	"bytes"
	"context"
	"errors"
	"io"
	"os"
)

type checkedReader struct {
	ctx    context.Context
	reader io.Reader
}

func (reader checkedReader) Read(data []byte) (int, error) {
	if err := reader.ctx.Err(); err != nil {
		return 0, err
	}
	return reader.reader.Read(data)
}

func verifyWritten(ctx context.Context, root *os.Root, name string, parts []finishedSegment, records []writeRecord, buffer []byte) (result []Artifact, err error) {
	reader, err := Open(ctx, root, name)
	if err != nil {
		return nil, err
	}
	defer func() {
		err = errors.Join(err, reader.Close())
		if err != nil {
			result = nil
		}
	}()
	if len(reader.Files) != len(records) || len(reader.volumes) != len(parts) {
		return nil, ErrOutputChanged
	}
	for index, file := range reader.Files {
		record := records[index]
		offset, offsetErr := file.DataOffset()
		if offsetErr != nil || file.Name != record.name || file.UncompressedSize64 != record.size || file.CompressedSize64 != record.size || file.Method != 0 || file.CRC32 != record.crc || uint64(offset) != record.dataOffset {
			return nil, ErrOutputChanged
		}
		expected := record.local()
		actual := buffer[:len(expected)]
		if _, err = reader.ReadAt(actual, int64(record.dataOffset)-int64(len(expected))); err != nil {
			return nil, err
		}
		if !bytes.Equal(actual, expected) || file.Mode().IsDir() != record.directory || file.Flags != 1<<11 {
			return nil, ErrOutputChanged
		}
	}
	for index, part := range parts {
		artifact, readErr := verifyPart(root, part, reader.volumes[index])
		if readErr != nil {
			return nil, readErr
		}
		artifact.LastSegment = index == len(parts)-1
		result = append(result, artifact)
	}
	return result, reader.Unchanged()
}
func verifyPart(root *os.Root, part finishedSegment, opened volume) (Artifact, error) {
	if opened.info.Size() != part.size {
		return Artifact{}, ErrOutputChanged
	}
	finished, err := root.Lstat(part.name)
	if err != nil {
		return Artifact{}, err
	}
	if !os.SameFile(finished, opened.info) || finished.Size() != part.size || !finished.ModTime().Equal(opened.info.ModTime()) {
		return Artifact{}, ErrOutputChanged
	}
	return Artifact{FileName: part.name, Size: part.size, Modified: finished.ModTime().UnixNano(), VerifiedInfo: &WriteReceipt{Info: finished}}, nil
}
