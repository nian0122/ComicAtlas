package domain

import (
	"path"
	"strings"
)

// CanOpenOriginal 按登记的原件状态判断可读性。
func CanOpenOriginal(state string) bool {
	return state == "available" || state == "releasing"
}

func HQSelectionPreview(id string, counts HQImageCounts, active, upload, transcode bool) HQReleasePreview {
	preview := HQReleasePreview{ScopeType: "comic", ScopeID: id, Blockers: []string{}}
	AddHQCounts(&preview, counts)
	FinishHQPreview(&preview, active, counts.Releasing > 0)
	if upload {
		appendHQBlocker(&preview, "INCOMPLETE_UPLOAD")
	}
	if transcode {
		appendHQBlocker(&preview, "INCOMPLETE_TRANSCODE")
	}
	preview.Allowed = len(preview.Blockers) == 0
	return preview
}

func AddHQPreview(preview *HQReleasePreview, current HQCurrent) {
	counts := HQImageCounts{Images: 1}
	switch current.State {
	case "released":
		counts.Released = 1
	case "releasing":
		counts.Releasing = 1
	case "available":
		counts.Available, counts.OriginalBytes = 1, current.Size
		if current.Size < 1 {
			counts.Invalid = 1
		}
		if current.LQ == nil || !current.LQ.Valid {
			counts.MissingLQ = 1
		}
	default:
		counts.Invalid = 1
	}
	AddHQCounts(preview, counts)
}
func AddHQCounts(preview *HQReleasePreview, counts HQImageCounts) {
	preview.Images += counts.Images
	preview.Available += counts.Available
	preview.Released += counts.Released
	preview.OriginalBytes += counts.OriginalBytes
	if counts.Releasing > 0 {
		appendHQBlocker(preview, "INCOMPLETE_RELEASE")
	}
	if counts.MissingLQ > 0 {
		appendHQBlocker(preview, "LQ_NOT_READY")
	}
	if counts.Invalid > 0 {
		appendHQBlocker(preview, "HQ_STATE_INVALID")
	}
}
func FinishHQPreview(preview *HQReleasePreview, active, incomplete bool) {
	if preview.Available == 0 {
		appendHQBlocker(preview, "NO_HQ_IMAGES")
	}
	if preview.Images > MaxLQItems {
		preview.Truncated = true
		appendHQBlocker(preview, "TOO_MANY_IMAGES")
	}
	if incomplete {
		appendHQBlocker(preview, "INCOMPLETE_RELEASE")
	}
	if active {
		appendHQBlocker(preview, "MEDIA_TASK_ACTIVE")
	}
	preview.Allowed = len(preview.Blockers) == 0
}
func appendHQBlocker(preview *HQReleasePreview, code string) {
	for _, value := range preview.Blockers {
		if value == code {
			return
		}
	}
	preview.Blockers = append(preview.Blockers, code)
}
func MatchHQSnapshot(item HQReleaseItem, current HQCurrent) bool {
	return (current.State == "available" || current.State == "releasing") && current.SourcePath == item.SourcePath && current.Modified == item.SourceModified && current.Size == item.OriginalBytes && current.LQ != nil && current.LQ.Valid && current.LQ.Path == item.LQ.Path && current.LQ.Width == item.LQ.Width && current.LQ.Height == item.LQ.Height && current.LQ.Size == item.LQ.Size
}
func ValidHQPath(value string) bool {
	return strings.HasPrefix(value, "hq/") && path.Clean(value) == value && !strings.ContainsAny(value, "\\:")
}

func HQBlocksLQ(active, incomplete int) bool { return active > 0 || incomplete > 0 }

func HQWriteState(item HQReleaseItem, current HQCurrent, taskState string, prepare bool, outcome string) (string, string, error) {
	if taskState != "running" || !MatchHQSnapshot(item, current) {
		return "", "", ErrLQConflict
	}
	if prepare {
		return "prepared", "releasing", nil
	}
	if current.State != "releasing" || (outcome != "removed" && outcome != "already_absent") {
		return "", "", ErrLQConflict
	}
	return "released", "released", nil
}
