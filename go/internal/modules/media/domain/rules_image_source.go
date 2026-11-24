package domain

// 列表与详情封面需要覆盖 2x 屏幕的清晰度，统一使用 1024/85，且不放大源图。
func CoverSpec() ImageSpec { return ImageSpec{MaxLongEdge: 1024, Quality: 85} }
func SelectImageSource(candidate ImageCandidate) (ImageSource, error) {
	source := candidate.Source
	if candidate.HQState == "available" {
		return source, nil
	}
	if candidate.LQValid != nil && *candidate.LQValid && candidate.LQPath != nil && candidate.LQSize != nil {
		source.Path, source.Size, source.Modified = *candidate.LQPath, *candidate.LQSize, 0
		return source, nil
	}
	return ImageSource{}, ErrLQNotFound
}
