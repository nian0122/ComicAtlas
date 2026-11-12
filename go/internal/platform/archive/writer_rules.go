package archive

import (
	"encoding/hex"
	"errors"
	"path"
	"strings"
	"unicode/utf8"
)

const MinPartBytes int64 = 64 << 10
const MaxPartBytes int64 = 4<<30 - 1
const emptySHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

var ErrWriteLimit = errors.New("归档写入超出限制")
var ErrSourceChanged = errors.New("归档来源与快照不符")
var ErrOutputChanged = errors.New("归档输出发生外部变化")

func validateWrite(directory string, options WriteOptions, entries []WriteEntry) error {
	if !safeName(directory) || path.Base(directory) != directory || len(directory) > 128 || !utf8.ValidString(directory) || !safeName(options.Name) || path.Base(options.Name) != options.Name || !utf8.ValidString(options.Name) {
		return ErrInvalid
	}
	if ext := strings.ToLower(path.Ext(options.Name)); ext != ".zip" && ext != ".cbz" {
		return ErrInvalid
	}
	if options.PartBytes != 0 && (options.PartBytes < MinPartBytes || options.PartBytes > MaxPartBytes) {
		return ErrWriteLimit
	}
	if len(entries) == 0 || len(entries) > MaxEntries {
		return ErrWriteLimit
	}
	seen := map[string]bool{}
	var total uint64
	var directoryBytes int
	for _, entry := range entries {
		if !safeName(entry.Name) || !utf8.ValidString(entry.Name) || !validDigest(entry.SHA256) || strings.HasSuffix(entry.Name, "/") != entry.Directory {
			return ErrInvalid
		}
		if entry.Directory && (entry.Size != 0 || entry.SHA256 != emptySHA256 || entry.Open != nil) || !entry.Directory && entry.Open == nil {
			return ErrInvalid
		}
		if entry.Size < 0 || uint64(entry.Size) > MaxFileBytes || uint64(entry.Size) > MaxTotalBytes-total {
			return ErrWriteLimit
		}
		total += uint64(entry.Size)
		// 为 ZIP64 大小/偏移扩展预留最大头部，准入后不会在末尾才发现目录超预算。
		directoryBytes += 46 + len(entry.Name) + 28
		if directoryBytes > MaxDirectoryBytes {
			return ErrWriteLimit
		}
		key := strings.ToLower(strings.TrimSuffix(entry.Name, "/"))
		if _, exists := seen[key]; exists {
			return ErrInvalid
		}
		seen[key] = entry.Directory
	}
	if total+uint64(directoryBytes)*2+98+4 > MaxTotalBytes+MaxDirectoryBytes {
		return ErrWriteLimit
	}
	if options.PartBytes > 0 && total > uint64(options.PartBytes)*MaxParts {
		return ErrWriteLimit
	}
	for name := range seen {
		for parent := path.Dir(name); parent != "."; parent = path.Dir(parent) {
			if directory, exists := seen[parent]; exists && !directory {
				return ErrInvalid
			}
		}
	}
	return nil
}

func validDigest(value string) bool {
	if len(value) != 64 {
		return false
	}
	decoded, err := hex.DecodeString(value)
	return err == nil && len(decoded) == 32 && strings.ToLower(value) == value
}
