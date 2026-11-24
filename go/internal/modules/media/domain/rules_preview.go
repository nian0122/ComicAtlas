package domain

// 预览优先读取同一原件的有效 LQ，否则读取仍保留的 HQ。
func PreviewSources(candidate ImageCandidate) []ImageSource {
	result := []ImageSource{}
	original := candidate.Source
	lqCandidate := candidate
	lqCandidate.HQState = "released"
	lq, lqErr := SelectImageSource(lqCandidate)
	if lqErr == nil {
		result = append(result, lq)
	}
	if candidate.HQState == "available" {
		result = append(result, original)
	}
	return result
}
