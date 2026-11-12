package domain

import (
	"crypto/sha256"
	"encoding/hex"
)

// 列表与详情封面需要覆盖 2x 屏幕的清晰度，统一使用 1024/85，且不放大源图。
func CoverSpec() ImageSpec { return ImageSpec{MaxLongEdge: 1024, Quality: 85} }
func SelectImageSource(candidate ImageCandidate) (ImageSource, error) {
	source := candidate.Source
	if candidate.HQState == "available" && source.OriginalSHA256 != "" {
		return source, nil
	}
	if candidate.LQValid != nil && *candidate.LQValid && candidate.LQPath != nil && candidate.LQSHA256 != nil && candidate.LQSize != nil {
		source.Path, source.SHA256, source.Size = *candidate.LQPath, *candidate.LQSHA256, *candidate.LQSize
		return source, nil
	}
	if candidate.MetadataOnly && candidate.HQState == "available" {
		return source, nil
	}
	return ImageSource{}, ErrLQNotFound
}
func CoverKey(source ImageSource) string {
	digest := sha256.Sum256([]byte("cover-v1\x00" + source.MediaID + "\x00" + source.OriginalSHA256 + "\x00" + source.SHA256))
	return hex.EncodeToString(digest[:])
}
