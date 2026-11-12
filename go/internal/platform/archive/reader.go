// Package archive 统一读取 ZIP/CBZ，包括 PKWARE 标准 .z01…zip 分卷。
// 只转换有界中央目录，媒体数据通过跨卷 ReaderAt 读取，不拼接整个归档。
package archive

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"strings"
	"unicode"
	"unicode/utf8"
)

const MaxEntries = 20000
const MaxDirectoryBytes = 16 << 20
const MaxParts = 128
const MaxFileBytes uint64 = 20 << 30
const MaxTotalBytes uint64 = 100 << 30

var ErrInvalid = errors.New("归档格式或内容无效")
var ErrMissingVolume = errors.New("归档缺少分卷")
var le = binary.LittleEndian

type volume struct {
	file  *os.File
	info  os.FileInfo
	start int64
}

type Reader struct {
	Files   []*zip.File
	volumes []volume
	size    int64
	ctx     context.Context
}

func (reader *Reader) Close() error {
	var failures []error
	for _, volume := range reader.volumes {
		failures = append(failures, volume.file.Close())
	}
	return errors.Join(failures...)
}

// Unchanged 确保打开的各卷在读取期间未发生尺寸/时间变化。
func (reader *Reader) Unchanged() error {
	for _, volume := range reader.volumes {
		info, err := volume.file.Stat()
		if err != nil {
			return err
		}
		if info.Size() != volume.info.Size() || !info.ModTime().Equal(volume.info.ModTime()) {
			return ErrInvalid
		}
	}
	return nil
}

func (reader *Reader) ReadAt(buffer []byte, offset int64) (int, error) {
	if offset < 0 {
		return 0, ErrInvalid
	}
	written := 0
	for len(buffer) > 0 {
		if err := reader.ctx.Err(); err != nil {
			return written, err
		}
		var selected *volume
		for index := len(reader.volumes) - 1; index >= 0; index-- {
			if offset >= reader.volumes[index].start {
				selected = &reader.volumes[index]
				break
			}
		}
		if selected == nil || offset >= reader.size {
			return written, io.EOF
		}
		remaining := selected.info.Size() - (offset - selected.start)
		if remaining <= 0 {
			return written, io.EOF
		}
		count := min(int64(len(buffer)), remaining)
		n, err := selected.file.ReadAt(buffer[:int(count)], offset-selected.start)
		written += n
		offset += int64(n)
		buffer = buffer[n:]
		if err != nil {
			return written, err
		}
	}
	return written, nil
}

type overlay struct {
	source *Reader
	start  int64
	tail   []byte
}

func (value overlay) ReadAt(buffer []byte, offset int64) (int, error) {
	if offset < 0 {
		return 0, ErrInvalid
	}
	n := 0
	if offset < value.start {
		count := min(int64(len(buffer)), value.start-offset)
		read, err := value.source.ReadAt(buffer[:int(count)], offset)
		n += read
		offset += int64(read)
		buffer = buffer[read:]
		if err != nil {
			return n, err
		}
	}
	if len(buffer) == 0 {
		return n, nil
	}
	read, err := bytes.NewReader(value.tail).ReadAt(buffer, offset-value.start)
	return n + read, err
}

func openVolume(root *os.Root, name string) (volume, error) {
	info, err := root.Lstat(name)
	if err != nil {
		return volume{}, err
	}
	if !info.Mode().IsRegular() {
		return volume{}, ErrInvalid
	}
	file, err := root.Open(name)
	if err != nil {
		return volume{}, err
	}
	opened, err := file.Stat()
	if err != nil || !opened.Mode().IsRegular() || !os.SameFile(info, opened) || opened.Size() < 1 {
		file.Close()
		return volume{}, ErrInvalid
	}
	return volume{file: file, info: opened}, nil
}

// Open 入口为最后一卷 .zip/.cbz；同目录的 .z01、.z02…必须连续齐全。
func Open(ctx context.Context, root *os.Root, name string) (_ *Reader, err error) {
	extension := strings.ToLower(path.Ext(name))
	if extension != ".zip" && extension != ".cbz" {
		return nil, ErrInvalid
	}
	last, err := openVolume(root, name)
	if err != nil {
		return nil, err
	}
	reader := &Reader{ctx: ctx, volumes: []volume{last}, size: last.info.Size()}
	defer func() {
		if err != nil {
			reader.Close()
		}
	}()
	tail := make([]byte, min(last.info.Size(), 65557))
	if _, err = last.file.ReadAt(tail, last.info.Size()-int64(len(tail))); err != nil {
		return nil, err
	}
	end := -1
	for index := len(tail) - 22; index >= 0; index-- {
		if le.Uint32(tail[index:]) == 0x06054b50 && index+22+int(le.Uint16(tail[index+20:])) == len(tail) {
			end = index
			break
		}
	}
	if end < 0 {
		return nil, ErrInvalid
	}
	eocd := tail[end:]
	lastDisk := uint32(le.Uint16(eocd[4:]))
	directoryDisk := uint32(le.Uint16(eocd[6:]))
	count := uint64(le.Uint16(eocd[10:]))
	directorySize := uint64(le.Uint32(eocd[12:]))
	directoryOffset := uint64(le.Uint32(eocd[16:]))
	endOffset := last.info.Size() - int64(len(tail)) + int64(end)
	locator := make([]byte, 20)
	has64 := endOffset >= 20
	if has64 {
		_, err = last.file.ReadAt(locator, endOffset-20)
		if err != nil {
			return nil, err
		}
		has64 = le.Uint32(locator) == 0x07064b50
	}
	if has64 {
		parts := le.Uint32(locator[16:])
		if parts == 0 || parts > MaxParts {
			return nil, ErrInvalid
		}
		if lastDisk != 0xffff && lastDisk != parts-1 {
			return nil, ErrInvalid
		}
		lastDisk = parts - 1
	}
	if lastDisk >= MaxParts {
		return nil, ErrInvalid
	}
	reader.volumes = nil
	reader.size = 0
	// 最后一卷已打开，出错前也须保留在关闭列表。
	reader.volumes = append(reader.volumes, last)
	var preceding []volume
	for disk := uint32(0); disk < lastDisk; disk++ {
		partName := strings.TrimSuffix(name, path.Ext(name)) + fmt.Sprintf(".z%02d", disk+1)
		part, openErr := openVolume(root, partName)
		if openErr != nil {
			return nil, fmt.Errorf("%w: %v", ErrMissingVolume, openErr)
		}
		part.start = reader.size
		reader.size += part.info.Size()
		preceding = append(preceding, part)
		reader.volumes = append(reader.volumes, part)
	}
	last.start = reader.size
	reader.size += last.info.Size()
	reader.volumes = append(preceding, last)
	if reader.size > int64(MaxTotalBytes)+MaxDirectoryBytes {
		return nil, ErrInvalid
	}
	boundary := last.start + endOffset
	if has64 {
		offset, offsetErr := reader.absolute(le.Uint32(locator[4:]), le.Uint64(locator[8:]))
		if offsetErr != nil {
			return nil, offsetErr
		}
		record := make([]byte, 56)
		if _, err = reader.ReadAt(record, offset); err != nil {
			return nil, err
		}
		if le.Uint32(record) != 0x06064b50 || le.Uint64(record[4:]) < 44 || le.Uint64(record[4:]) > MaxDirectoryBytes || offset+12+int64(le.Uint64(record[4:])) != boundary-20 || le.Uint32(record[16:]) != le.Uint32(locator[4:]) {
			return nil, ErrInvalid
		}
		directoryDisk = le.Uint32(record[20:])
		count = le.Uint64(record[32:])
		directorySize = le.Uint64(record[40:])
		directoryOffset = le.Uint64(record[48:])
		boundary = offset
	} else if lastDisk == 0xffff || count == 0xffff || directorySize == 0xffffffff || directoryOffset == 0xffffffff {
		return nil, ErrInvalid
	}
	start, err := reader.absolute(directoryDisk, directoryOffset)
	if err != nil {
		return nil, err
	}
	if count == 0 || count > MaxEntries || directorySize > MaxDirectoryBytes || start+int64(directorySize) != boundary {
		return nil, ErrInvalid
	}
	central := make([]byte, int(directorySize))
	if _, err = reader.ReadAt(central, start); err != nil {
		return nil, err
	}
	normalized, err := reader.normalize(central, count, start)
	if err != nil {
		return nil, err
	}
	virtual := overlay{reader, start, normalized}
	zipReader, err := zip.NewReader(virtual, start+int64(len(normalized)))
	if err != nil {
		return nil, err
	}
	if uint64(len(zipReader.File)) != count {
		return nil, ErrInvalid
	}
	reader.Files = zipReader.File
	seen := map[string]bool{}
	var total uint64
	for _, file := range reader.Files {
		if err = decodeUnicodePath(file); err != nil {
			return nil, err
		}
		if !safeName(file.Name) || !utf8.ValidString(file.Name) || file.Flags&(1|64|8192) != 0 || (file.Method != zip.Store && file.Method != zip.Deflate) {
			return nil, ErrInvalid
		}
		mode := file.Mode()
		if !mode.IsRegular() && !mode.IsDir() {
			return nil, ErrInvalid
		}
		key := strings.ToLower(strings.TrimSuffix(file.Name, "/"))
		if _, exists := seen[key]; exists {
			return nil, ErrInvalid
		}
		seen[key] = file.FileInfo().IsDir()
		if file.UncompressedSize64 > MaxFileBytes || file.UncompressedSize64 > MaxTotalBytes-total {
			return nil, ErrInvalid
		}
		total += file.UncompressedSize64
		offset, dataErr := file.DataOffset()
		if dataErr != nil || offset < 0 || offset > start || file.CompressedSize64 > uint64(start-offset) {
			return nil, ErrInvalid
		}
	}
	for key := range seen {
		for parent := path.Dir(key); parent != "."; parent = path.Dir(parent) {
			if directory, exists := seen[parent]; exists && !directory {
				return nil, ErrInvalid
			}
		}
	}
	return reader, reader.Unchanged()
}

func (reader *Reader) absolute(disk uint32, offset uint64) (int64, error) {
	if disk >= uint32(len(reader.volumes)) || offset > uint64(reader.volumes[disk].info.Size()) {
		return 0, ErrInvalid
	}
	return reader.volumes[disk].start + int64(offset), nil
}

func safeName(name string) bool {
	name = strings.TrimSuffix(name, "/")
	if name == "" || len(name) > 2048 || strings.Count(name, "/") > 64 || path.Clean(name) != name || strings.HasPrefix(name, "/") || strings.ContainsAny(name, "\\:") {
		return false
	}
	for _, component := range strings.Split(name, "/") {
		if component == ".." || component == "." || strings.TrimRight(component, " .") != component {
			return false
		}
		base := strings.ToUpper(strings.SplitN(component, ".", 2)[0])
		if base == "CON" || base == "PRN" || base == "AUX" || base == "NUL" || (len(base) == 4 && (strings.HasPrefix(base, "COM") || strings.HasPrefix(base, "LPT")) && base[3] >= '1' && base[3] <= '9') {
			return false
		}
		for _, character := range component {
			if unicode.IsControl(character) || strings.ContainsRune("<>\"|?*", character) {
				return false
			}
		}
	}
	return true
}

func (reader *Reader) normalize(central []byte, count uint64, start int64) ([]byte, error) {
	var result bytes.Buffer
	for index := uint64(0); index < count; index++ {
		if len(central) < 46 || le.Uint32(central) != 0x02014b50 {
			return nil, ErrInvalid
		}
		names, extras, comments := int(le.Uint16(central[28:])), int(le.Uint16(central[30:])), int(le.Uint16(central[32:]))
		length := 46 + names + extras + comments
		if length > len(central) {
			return nil, ErrInvalid
		}
		header := append([]byte(nil), central[:46+names]...)
		uncompressed, compressed, offset, disk := uint64(le.Uint32(central[24:])), uint64(le.Uint32(central[20:])), uint64(le.Uint32(central[42:])), uint32(le.Uint16(central[34:]))
		needs64 := uncompressed == 0xffffffff || compressed == 0xffffffff || offset == 0xffffffff || disk == 0xffff
		found64 := false
		var extra bytes.Buffer
		fields := central[46+names : 46+names+extras]
		for len(fields) > 0 {
			if len(fields) < 4 {
				return nil, ErrInvalid
			}
			size := int(le.Uint16(fields[2:]))
			if size+4 > len(fields) {
				return nil, ErrInvalid
			}
			if le.Uint16(fields) == 1 {
				if found64 {
					return nil, ErrInvalid
				}
				found64 = true
				values := fields[4 : 4+size]
				for _, value := range []*uint64{&uncompressed, &compressed, &offset} {
					if *value == 0xffffffff {
						if len(values) < 8 {
							return nil, ErrInvalid
						}
						*value = le.Uint64(values)
						values = values[8:]
					}
				}
				if disk == 0xffff {
					if len(values) < 4 {
						return nil, ErrInvalid
					}
					disk = le.Uint32(values)
				}
			} else {
				extra.Write(fields[:size+4])
			}
			fields = fields[size+4:]
		}
		if needs64 && !found64 {
			return nil, ErrInvalid
		}
		absolute, err := reader.absolute(disk, offset)
		if err != nil || absolute >= start || extra.Len()+28 > 65535 {
			return nil, ErrInvalid
		}
		field := make([]byte, 28)
		le.PutUint16(field, 1)
		le.PutUint16(field[2:], 24)
		le.PutUint64(field[4:], uncompressed)
		le.PutUint64(field[12:], compressed)
		le.PutUint64(field[20:], uint64(absolute))
		extra.Write(field)
		le.PutUint32(header[20:], 0xffffffff)
		le.PutUint32(header[24:], 0xffffffff)
		le.PutUint32(header[42:], 0xffffffff)
		le.PutUint16(header[34:], 0)
		le.PutUint16(header[30:], uint16(extra.Len()))
		le.PutUint16(header[32:], 0)
		result.Write(header)
		result.Write(extra.Bytes())
		central = central[length:]
		if result.Len() > MaxDirectoryBytes+MaxEntries*28 {
			return nil, ErrInvalid
		}
	}
	if len(central) != 0 {
		return nil, ErrInvalid
	}
	directorySize := result.Len()
	record := make([]byte, 56+20+22)
	le.PutUint32(record, 0x06064b50)
	le.PutUint64(record[4:], 44)
	le.PutUint16(record[12:], 45)
	le.PutUint16(record[14:], 45)
	le.PutUint64(record[24:], count)
	le.PutUint64(record[32:], count)
	le.PutUint64(record[40:], uint64(directorySize))
	le.PutUint64(record[48:], uint64(start))
	le.PutUint32(record[56:], 0x07064b50)
	le.PutUint64(record[64:], uint64(start)+uint64(directorySize))
	le.PutUint32(record[72:], 1)
	le.PutUint32(record[76:], 0x06054b50)
	le.PutUint16(record[84:], uint16(count))
	le.PutUint16(record[86:], uint16(count))
	le.PutUint32(record[88:], 0xffffffff)
	le.PutUint32(record[92:], 0xffffffff)
	result.Write(record)
	return result.Bytes(), nil
}
