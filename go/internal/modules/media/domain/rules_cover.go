package domain

import (
	"crypto/sha256"
	"encoding/hex"
)

func CoverPath(comic string) string {
	if !StorageKey(comic) {
		return ""
	}
	return "thumbs/" + comic + "/cover.webp"
}
func ValidCoverPath(name string) bool {
	const prefix = "thumbs/"
	const suffix = "/cover.webp"
	if len(name) <= len(prefix)+len(suffix) {
		return false
	}
	comic := name[len(prefix) : len(name)-len(suffix)]
	return CoverPath(comic) == name
}
func ValidCover(record CoverRecord, source ImageSource, comic string) bool {
	return StorageKey(comic) && record.Path == CoverPath(comic) && record.MediaID == source.MediaID && record.SourceKey == CoverKey(source) && record.Size > 0 && record.Size <= 2<<20 && record.Width > 0 && record.Height > 0 && max(record.Width, record.Height) <= CoverSpec().MaxLongEdge
}

// FrameCoverKey 为视频抽帧封面生成独立来源键；它不指向可切换的图片媒体。
func FrameCoverKey(media, sha string) string {
	digest := sha256.Sum256([]byte("video-frame-cover-v1\x00" + media + "\x00" + sha))
	return hex.EncodeToString(digest[:])
}

// ValidFrameCover 校验视频抽帧封面；media 只作为来源证据，不建立可切换的封面选择。
func ValidFrameCover(record CoverRecord, comic string) bool {
	return StorageKey(comic) && StorageKey(record.MediaID) && record.Path == CoverPath(comic) && record.SourceKey == FrameCoverKey(record.MediaID, record.SHA256) && record.Size > 0 && record.Size <= 2<<20 && record.Width > 0 && record.Height > 0 && max(record.Width, record.Height) <= CoverSpec().MaxLongEdge
}
