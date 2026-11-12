package domain

import (
	"math"
	"strings"
)

const MaxScanEntries = 1000000
const MaxScanDepth = 64

func ValidateScope(kind, id string) error {
	if kind == "global" && id == "" {
		return nil
	}
	if (kind == "comic" || kind == "chapter") && id != "" && len(id) <= 128 {
		return nil
	}
	return ErrValidation
}

// 累计的是普通文件逻辑长度，不声称等同于块分配、去重或压缩后的设备占用。
func AddFile(usage *ObservedUsage, path string, size int64) error {
	if size < 0 {
		return ErrScanRead
	}
	bucket := &usage.Other
	switch strings.SplitN(path, "/", 2)[0] {
	case "hq":
		bucket = &usage.HQ
	case "lq":
		bucket = &usage.LQ
	case "thumbs":
		bucket = &usage.Covers
	case "metadata":
		bucket = &usage.Metadata
	case "temp":
		bucket = &usage.Temp
	case "trash":
		bucket = &usage.Trash
	case "exports":
		bucket = &usage.Exports
	case "retired":
		bucket = &usage.Retired
	case "staging":
		bucket = &usage.Staging
	}
	if bucket.Bytes > math.MaxInt64-size {
		return ErrScanLimit
	}
	bucket.Files++
	bucket.Bytes += size
	return nil
}
