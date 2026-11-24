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

// LQPath 与旧系统一致，用 HQ 文件名替换扩展名生成轻量副本路径，
// 例如 hq/212/684/68.jpg 对应 lq/212/684/68.webp。
// 同一物理章节中只有扩展名不同的同名页面会得到同一路径，这种布局不允许发起 LQ 生成。
func LQPath(source string) string {
	comic, chapter, ok := HQOwner(source)
	if !ok {
		return ""
	}
	base := path.Base(source)
	extension := path.Ext(base)
	if extension == "" || extension == base {
		return ""
	}
	stem := strings.TrimSuffix(base, extension)
	if stem == "" || len(stem)+len(".webp") > 255 {
		return ""
	}
	return "lq/" + comic + "/" + chapter + "/" + stem + ".webp"
}

// TranscodedPath 为视频转码产物生成 HQ 路径；产物不复用源文件名，避免与原件同名。
func TranscodedPath(source, media, revision string) string {
	comic, chapter, ok := HQOwner(source)
	if !ok || !StorageKey(media) || !StorageKey(revision) || len(media)+len(revision)+len(".mp4")+2 > 255 {
		return ""
	}
	return "hq/" + comic + "/" + chapter + "/" + media + "-" + revision + ".mp4"
}
