package domain

import (
	"path"
	"strings"
	"unicode"
	"unicode/utf8"

	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
)

// HostImportPath 按宿主机语义解析绝对路径，不依赖容器的操作系统。
func HostImportPath(value string) (string, error) {
	value = strings.ReplaceAll(value, `\`, "/")
	if !utf8.ValidString(value) || len(value) > 4096 || strings.ContainsFunc(value, unicode.IsControl) {
		return "", jobsdomain.ErrValidation
	}
	windows := windowsHostPath(value)
	if !strings.HasPrefix(value, "/") && !windows {
		return "", jobsdomain.ErrValidation
	}
	parts := strings.Split(value, "/")
	for index, part := range parts {
		if part == "." || part == ".." || strings.Contains(part, ":") && !(windows && index == 0 && len(part) == 2) ||
			windows && part != "" && strings.TrimRight(part, " .") != part {
			return "", jobsdomain.ErrValidation
		}
	}
	if strings.HasPrefix(value, "//") {
		if len(parts) < 4 || parts[2] == "" || parts[3] == "" {
			return "", jobsdomain.ErrValidation
		}
		return "//" + strings.TrimPrefix(path.Clean(value), "/"), nil
	}
	clean := path.Clean(value)
	if windows && len(clean) == 2 {
		clean += "/"
	}
	return clean, nil
}

func windowsHostPath(value string) bool {
	return strings.HasPrefix(value, "//") || len(value) >= 3 &&
		(value[0] >= 'A' && value[0] <= 'Z' || value[0] >= 'a' && value[0] <= 'z') && value[1:3] == ":/"
}

// RelativeHostImportSource 仅匹配根本身或完整子路径，拒绝相似前缀和穿越。
func RelativeHostImportSource(root, value string) (string, error) {
	value, err := HostImportPath(value)
	if err != nil {
		return "", err
	}
	match := func(left, right string) bool {
		if windowsHostPath(root) {
			return strings.EqualFold(left, right)
		}
		return left == right
	}
	if match(root, value) {
		return ".", nil
	}
	prefix := strings.TrimSuffix(root, "/") + "/"
	if len(value) <= len(prefix) || !match(prefix, value[:len(prefix)]) {
		return "", jobsdomain.ErrValidation
	}
	return value[len(prefix):], nil
}
