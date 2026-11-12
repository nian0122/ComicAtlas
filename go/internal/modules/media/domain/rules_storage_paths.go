package domain

import (
	"path"
	"strings"
)

func StorageKey(value string) bool {
	if len(value) == 0 || len(value) > 128 {
		return false
	}
	for _, c := range value {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-') {
			return false
		}
	}
	return true
}

// HQOwner 读取稳定物理归属；逻辑章节移动不会自动搬迁原件。
func HQOwner(name string) (string, string, bool) {
	parts := strings.Split(name, "/")
	if len(parts) != 4 || parts[0] != "hq" || parts[1] == "uploads" || parts[1] == "transcoded" || strings.HasPrefix(parts[1], ".") || path.Clean(name) != name || !StorageKey(parts[1]) || !StorageKey(parts[2]) || strings.ContainsAny(parts[3], "\\:\x00") {
		return "", "", false
	}
	return parts[1], parts[2], parts[3] != "" && parts[3] != "." && parts[3] != ".."
}

func DerivedPath(kind, source, media, revision, extension string) string {
	comic, chapter, ok := HQOwner(source)
	if !ok || !StorageKey(media) || !StorageKey(revision) || len(media)+len(revision)+len(extension)+2 > 255 || (kind != "hq" && kind != "lq") || (extension != "webp" && extension != "mp4") {
		return ""
	}
	return kind + "/" + comic + "/" + chapter + "/" + media + "-" + revision + "." + extension
}
