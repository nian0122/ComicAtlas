package archive

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"hash"
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

// 源媒体区间与整卷摘要在同一遍顺序回读计算，避免再完整读取第二遍产物。
type payloadVerifier struct {
	records  []writeRecord
	index    int
	position uint64
	hash     hash.Hash
}

func (verifier *payloadVerifier) Write(data []byte) (int, error) {
	count := len(data)
	for verifier.index < len(verifier.records) {
		record := verifier.records[verifier.index]
		if verifier.hash == nil {
			verifier.hash = sha256.New()
		}
		end := record.dataOffset + record.size
		if verifier.position >= end {
			if hex.EncodeToString(verifier.hash.Sum(nil)) != record.sourceSHA {
				return 0, ErrOutputChanged
			}
			verifier.index++
			verifier.hash = nil
			continue
		}
		if len(data) == 0 {
			return count, nil
		}
		if verifier.position < record.dataOffset {
			n := min(uint64(len(data)), record.dataOffset-verifier.position)
			verifier.position += n
			data = data[int(n):]
			continue
		}
		n := min(uint64(len(data)), end-verifier.position)
		verifier.hash.Write(data[:int(n)])
		verifier.position += n
		data = data[int(n):]
	}
	verifier.position += uint64(len(data))
	return count, nil
}
func verifyWritten(ctx context.Context, root *os.Root, name string, parts []writeSegment, records []writeRecord, buffer []byte) (result []Artifact, err error) {
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
	if len(reader.Files) != len(records) {
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
	verifier := &payloadVerifier{records: records}
	for index, part := range parts {
		artifact, readErr := verifyPart(ctx, root, part, verifier, buffer)
		if readErr != nil {
			return nil, readErr
		}
		artifact.LastSegment = index == len(parts)-1
		result = append(result, artifact)
	}
	if _, err = verifier.Write(nil); err != nil {
		return nil, err
	}
	if verifier.index != len(records) {
		return nil, ErrOutputChanged
	}
	return result, reader.Unchanged()
}
func verifyPart(ctx context.Context, root *os.Root, part writeSegment, verifier *payloadVerifier, buffer []byte) (artifact Artifact, err error) {
	info, err := root.Lstat(part.name)
	if err != nil {
		return artifact, err
	}
	if !info.Mode().IsRegular() || !os.SameFile(info, part.identity) || info.Size() != part.size {
		return artifact, ErrOutputChanged
	}
	file, err := root.Open(part.name)
	if err != nil {
		return artifact, err
	}
	defer func() { err = errors.Join(err, file.Close()) }()
	opened, err := file.Stat()
	if err != nil {
		return artifact, err
	}
	if !os.SameFile(opened, info) || opened.Size() != part.size {
		return artifact, ErrOutputChanged
	}
	hash := sha256.New()
	n, err := io.CopyBuffer(io.MultiWriter(hash, verifier), checkedReader{ctx, file}, buffer)
	if err != nil {
		return artifact, err
	}
	finished, err := file.Stat()
	if err != nil {
		return artifact, err
	}
	if n != part.size || finished.Size() != opened.Size() || !finished.ModTime().Equal(opened.ModTime()) {
		return artifact, ErrOutputChanged
	}
	return Artifact{FileName: part.name, Size: n, SHA256: hex.EncodeToString(hash.Sum(nil)), VerifiedInfo: &WriteReceipt{Info: finished}}, nil
}
