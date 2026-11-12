package domain

import (
	"sort"

	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
)

// SampleOrdinals 按漫画中活动图片的既定顺序抽样，保留首、中、尾并有界补齐。
func SampleOrdinals(total, count int) ([]int, error) {
	if count == 0 {
		count = DefaultSampleCount
	}
	if total < 1 || total > librarydomain.MaxSnapshotMedia || count < 1 || count > MaxSamples {
		return nil, ErrValidation
	}
	count = min(count, total)
	selected := map[int]bool{1: true}
	if count > 2 {
		selected[total/2+1] = true
	}
	if count > 1 {
		selected[total] = true
	}
	for index := 1; len(selected) < count && index < count-1; index++ {
		denominator := count - 1
		position := (index*(total-1)+denominator/2)/denominator + 1
		selected[position] = true
	}
	if len(selected) != count {
		return nil, ErrValidation
	}
	result := make([]int, 0, count)
	for position := range selected {
		result = append(result, position)
	}
	sort.Ints(result)
	return result, nil
}
