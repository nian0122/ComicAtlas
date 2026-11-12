package domain

import (
	"path"
	"sort"
	"strings"

	"comicatlas.local/backend/internal/platform/natural"
)

var namedCoverStems = []string{"cover", "封面", "表紙", "front", "folder"}

const (
	namedCoverPriority    = 0
	imageFallbackRank     = 5
	videoFallbackPriority = 6
)

// OrderCoverCandidates 按命名封面、全书图片、视频抽帧的顺序排列。
// 同一档位内，图片按目录深度、自然路径、章节顺序、页码排序；视频按阅读顺序排序。
func OrderCoverCandidates(candidates []CoverCandidate) []CoverCandidate {
	ordered := append([]CoverCandidate(nil), candidates...)
	sort.SliceStable(ordered, func(left, right int) bool {
		a, b := ordered[left], ordered[right]
		ap, bp := coverCandidatePriority(a), coverCandidatePriority(b)
		if ap != bp {
			return ap < bp
		}
		if ap == videoFallbackPriority {
			if a.GlobalOrder != b.GlobalOrder {
				return a.GlobalOrder < b.GlobalOrder
			}
			if a.PageNumber != b.PageNumber {
				return a.PageNumber < b.PageNumber
			}
		} else {
			if a.Depth != b.Depth {
				return a.Depth < b.Depth
			}
			if comparison := natural.Compare(a.RelativePath, b.RelativePath); comparison != 0 {
				return comparison < 0
			}
			if a.GlobalOrder != b.GlobalOrder {
				return a.GlobalOrder < b.GlobalOrder
			}
			if a.PageNumber != b.PageNumber {
				return a.PageNumber < b.PageNumber
			}
		}
		if comparison := natural.Compare(a.FileName, b.FileName); comparison != 0 {
			return comparison < 0
		}
		return a.MediaID < b.MediaID
	})
	return ordered
}

func coverCandidatePriority(candidate CoverCandidate) int {
	if candidate.MediaType == "video" {
		return videoFallbackPriority
	}
	if candidate.MediaType != "image" {
		return videoFallbackPriority + 1
	}
	stem := strings.ToLower(strings.TrimSuffix(candidate.FileName, path.Ext(candidate.FileName)))
	for index, named := range namedCoverStems {
		if stem == named {
			return namedCoverPriority + index
		}
	}
	return imageFallbackRank
}
