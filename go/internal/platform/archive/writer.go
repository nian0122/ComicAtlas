package archive

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"hash/crc32"
	"io"
	"os"
)

// Write 在独占的新暂存目录内生成归档；成功前不向调用方返回可发布产物。
// 调用方负责把完整目录纳入自身任务发布协议，本组件不创建业务记录。
func Write(ctx context.Context, parent *os.Root, directory string, options WriteOptions, entries []WriteEntry) ([]Artifact, error) {
	return writeArchive(ctx, parent, directory, options, entries, false)
}
func writeArchive(ctx context.Context, parent *os.Root, directory string, options WriteOptions, entries []WriteEntry, force64 bool) (result []Artifact, err error) {
	if err = validateWrite(directory, options, entries); err != nil {
		return nil, err
	}
	if err = ctx.Err(); err != nil {
		return nil, err
	}
	if err = parent.Mkdir(directory, 0700); err != nil {
		return nil, err
	}
	identity, err := parent.Lstat(directory)
	if err != nil {
		return nil, err
	}
	var root *os.Root
	var segments *segmentWriter
	complete := false
	defer func() {
		if segments != nil && segments.file != nil {
			err = errors.Join(err, segments.closeCurrent())
		}
		if root != nil {
			err = errors.Join(err, root.Close())
		}
		if !complete || err != nil {
			result = nil
			current, checkErr := parent.Lstat(directory)
			if checkErr == nil && os.SameFile(identity, current) {
				err = errors.Join(err, parent.RemoveAll(directory))
			} else {
				err = errors.Join(err, ErrOutputChanged)
			}
		}
	}()
	root, err = parent.OpenRoot(directory)
	if err != nil {
		return nil, err
	}
	opened, err := root.Stat(".")
	if err != nil {
		return nil, err
	}
	if !identity.IsDir() || !os.SameFile(opened, identity) {
		return nil, ErrOutputChanged
	}
	segments = &segmentWriter{ctx: ctx, root: root, limit: options.PartBytes}
	if err = segments.next(); err != nil {
		return nil, err
	}
	if options.PartBytes > 0 {
		if _, err = segments.Write([]byte{0x50, 0x4b, 0x07, 0x08}); err != nil {
			return nil, err
		}
	}
	records := make([]writeRecord, 0, len(entries))
	buffer := make([]byte, 256<<10)
	for _, entry := range entries {
		record := writeRecord{name: entry.Name, size: uint64(entry.Size), sourceSHA: entry.SHA256, force64: force64, directory: entry.Directory}
		header := record.local()
		if err = segments.reserve(len(header)); err != nil {
			return nil, err
		}
		record.disk, record.offset = uint16(len(segments.parts)-1), uint64(segments.parts[len(segments.parts)-1].size)
		if _, err = segments.Write(header); err != nil {
			return nil, err
		}
		record.dataOffset = segments.total
		var checksum uint32
		if !entry.Directory {
			checksum, err = copyEntry(ctx, segments, entry, buffer)
		}
		if err != nil {
			return nil, err
		}
		record.crc = checksum
		var crcBytes [4]byte
		le.PutUint32(crcBytes[:], checksum)
		if err = segments.patch(int(record.disk), int64(record.offset)+14, crcBytes[:]); err != nil {
			return nil, err
		}
		records = append(records, record)
	}
	if err = writeDirectory(segments, records, force64); err != nil {
		return nil, err
	}
	if options.PartBytes > 0 && len(segments.parts) == 1 {
		if err = segments.patch(0, 0, []byte{0x50, 0x4b, 0x30, 0x30}); err != nil {
			return nil, err
		}
	}
	finished, err := segments.finishNames(options.Name)
	if err != nil {
		return nil, err
	}
	result, err = verifyWritten(ctx, root, options.Name, finished, records, buffer)
	if err != nil {
		return nil, err
	}
	current, err := parent.Lstat(directory)
	if err != nil {
		return nil, err
	}
	if !current.IsDir() || !os.SameFile(current, identity) {
		return nil, ErrOutputChanged
	}
	complete = true
	return result, nil
}
func copyEntry(ctx context.Context, destination io.Writer, entry WriteEntry, buffer []byte) (checksum uint32, err error) {
	if err = ctx.Err(); err != nil {
		return 0, err
	}
	reader, err := entry.Open(ctx)
	if err != nil {
		return 0, err
	}
	if reader == nil {
		return 0, io.ErrUnexpectedEOF
	}
	defer func() { err = errors.Join(err, reader.Close()) }()
	hash, crc := sha256.New(), crc32.NewIEEE()
	written, err := io.CopyBuffer(io.MultiWriter(destination, hash, crc), io.LimitReader(checkedReader{ctx, reader}, entry.Size+1), buffer)
	if err != nil {
		return 0, err
	}
	if written != entry.Size || hex.EncodeToString(hash.Sum(nil)) != entry.SHA256 {
		return 0, ErrSourceChanged
	}
	return crc.Sum32(), ctx.Err()
}
func writeDirectory(writer *segmentWriter, records []writeRecord, force64 bool) error {
	var start uint64
	var disk uint16
	var offset uint64
	counts := make([]uint64, MaxParts)
	zip64 := force64
	for index, record := range records {
		header := record.central()
		if err := writer.reserve(len(header)); err != nil {
			return err
		}
		if index == 0 {
			start = writer.total
			disk = uint16(len(writer.parts) - 1)
			offset = uint64(writer.parts[len(writer.parts)-1].size)
		}
		counts[len(writer.parts)-1]++
		if _, err := writer.Write(header); err != nil {
			return err
		}
		zip64 = zip64 || record.size64() || record.offset >= zip32Max
	}
	size := writer.total - start
	zip64 = zip64 || offset >= zip32Max || size >= zip32Max
	length := 22
	if zip64 {
		length += 76
	}
	if err := writer.reserve(length); err != nil {
		return err
	}
	last := uint16(len(writer.parts) - 1)
	end := endRecords(last, disk, uint64(len(records)), counts[last], size, offset, uint64(writer.parts[last].size), zip64)
	_, err := writer.Write(end)
	return err
}
