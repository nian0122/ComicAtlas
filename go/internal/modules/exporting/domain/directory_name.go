package domain

import (
	"crypto/sha256"
	"encoding/hex"
	"path"
	"strings"
	"unicode"
	"unicode/utf8"
)

const (
	DirectoryBookNameBytes  = 120
	DirectoryNodeNameBytes  = 80
	DirectoryPageNameBytes  = 80
	DirectoryPathBytes      = 2048
	DirectoryPathDepth      = 67
	DirectoryComponentBytes = 255
	DirectorySuffixBytes    = 15
	DirectoryOtherRoot      = "其他文件"
)

// DirectoryOutputPath 把计划内的书内相对路径映射到导出根：
// 正常页面位于 <书名>/...，元数据和保留文件位于 其他文件/<书名>/...
func DirectoryOutputPath(bookName, target string) string {
	if target == "" {
		return bookName
	}
	if target == "metadata.json" || target == "_retained" || strings.HasPrefix(target, "_retained/") {
		return DirectoryOtherRoot + "/" + bookName + "/" + target
	}
	return bookName + "/" + target
}

// directoryLabel 清洗单个输出名称：折叠空白、替换跨平台非法字符、去掉首尾空格和句点，
// 按 UTF-8 字节上限截断且不拆分字符。空名、保留设备名和 .comicatlas- 前缀都做安全处理。
func directoryLabel(value string, maximum int) string {
	if maximum < 1 {
		return "untitled"
	}
	value = strings.Join(strings.Fields(value), " ")
	var result strings.Builder
	for _, character := range value {
		if unicode.IsControl(character) || unicode.Is(unicode.Cf, character) || strings.ContainsRune(`/\:*?"<>|`, character) {
			character = '_'
		}
		if result.Len()+utf8.RuneLen(character) > maximum {
			break
		}
		result.WriteRune(character)
	}
	name := strings.Trim(result.String(), " .")
	if name == "" {
		return "untitled"
	}
	if strings.HasPrefix(strings.ToLower(name), ".comicatlas-") {
		name = "_" + name
	}
	if directoryReservedName(name) {
		name = "_" + name
	}
	return name
}

// directoryStructuralName 避开书籍根目录下的固定文档和保留区名称。
func directoryStructuralName(value string, maximum int) string {
	name := directoryLabel(value, maximum)
	switch strings.ToLower(name) {
	case "_retained", "metadata.json", strings.ToLower(DirectoryOtherRoot):
		name = "_" + name
	}
	return name
}

func directoryReservedName(name string) bool {
	base := strings.ToUpper(strings.SplitN(name, ".", 2)[0])
	switch base {
	case "CON", "PRN", "AUX", "NUL":
		return true
	}
	return len(base) == 4 && (strings.HasPrefix(base, "COM") || strings.HasPrefix(base, "LPT")) && base[3] >= '1' && base[3] <= '9'
}

// directoryNameKey 生成大小写不敏感的比较键；SimpleFold 用于覆盖 Windows/macOS 上
// 常见的同形大小写，不引入额外 Unicode 归一化依赖。
func directoryNameKey(value string) string {
	var result strings.Builder
	for _, character := range value {
		result.WriteRune(directoryFoldedRune(character))
	}
	return result.String()
}

func directoryFoldedRune(character rune) rune {
	minimum := character
	for folded := unicode.SimpleFold(character); folded != character; folded = unicode.SimpleFold(folded) {
		if folded < minimum {
			minimum = folded
		}
	}
	return minimum
}

// directoryUniqueName 只在名称冲突时追加稳定 ID 前缀，不生成序号。
func directoryUniqueName(base, id string, used map[string]bool, force bool) string {
	if !force && !used[directoryNameKey(base)] {
		return base
	}
	key := directoryKey(id)
	for length := 6; length <= len(key); length += 2 {
		candidate := base + " [" + key[:length] + "]"
		if !used[directoryNameKey(candidate)] {
			return candidate
		}
	}
	candidate := base + " [" + directoryFullKey(id) + "]"
	for used[directoryNameKey(candidate)] {
		candidate += "_"
	}
	return candidate
}

func directoryFullKey(id string) string {
	sum := sha256.Sum256([]byte(id))
	return hex.EncodeToString(sum[:])
}

// directoryBookNames 生成整批书名目录。普通书名不带序号或哈希；同批重名时才追加短 ID。
func directoryBookNames(snapshots []DirectoryInventorySnapshot) ([]string, error) {
	if len(snapshots) == 0 || len(snapshots) > MaxDirectoryBooks {
		return nil, ErrValidation
	}
	labels := make([]string, len(snapshots))
	counts := map[string]int{}
	for index, snapshot := range snapshots {
		label := directoryStructuralName(snapshot.Inventory.Comic.Title, DirectoryBookNameBytes)
		labels[index] = label
		counts[directoryNameKey(label)]++
	}
	names := make([]string, len(snapshots))
	used := map[string]bool{}
	for index, snapshot := range snapshots {
		label := labels[index]
		duplicate := counts[directoryNameKey(label)] > 1
		if duplicate || used[directoryNameKey(label)] {
			label = directoryUniqueName(label, snapshot.Inventory.Comic.ID, used, duplicate)
		}
		used[directoryNameKey(label)] = true
		names[index] = label
	}
	return names, nil
}

// directoryStructuralLabelBytes 根据书名和最大层级计算每个结构节点可用的标题字节数。
// 深层目录自动缩短标题，保证书目录、全部结构目录和最深一页仍不超过总路径预算。
func directoryStructuralLabelBytes(bookName string, depth int) int {
	if depth < 1 {
		depth = 1
	}
	reserve := len(bookName) + depth + depth*DirectorySuffixBytes + DirectoryPageNameBytes + DirectorySuffixBytes + 1 + 12
	available := DirectoryPathBytes - reserve
	if available < depth {
		return 0
	}
	limit := available / depth
	if limit > DirectoryNodeNameBytes {
		limit = DirectoryNodeNameBytes
	}
	if limit < 8 {
		return 0
	}
	return limit
}

func directoryPageLabel(fileName string) string {
	base := strings.TrimSpace(strings.TrimSuffix(fileName, path.Ext(fileName)))
	if base == "" {
		return "untitled"
	}
	return directoryLabel(base, DirectoryPageNameBytes)
}

// directoryPageTarget 生成页文件相对路径。没有页码前缀；页名过长时按剩余预算截断。
func directoryPageTarget(directory, pageName, ext string) (string, error) {
	if pageName == "" {
		pageName = "untitled"
	}
	available := DirectoryPathBytes - len(directory) - 1 - len(ext)
	if available < 1 {
		return "", ErrValidation
	}
	if len(pageName) > available {
		pageName = directoryTruncateLabel(pageName, available)
	}
	if pageName == "" {
		return "", ErrValidation
	}
	target := path.Join(directory, pageName) + ext
	if len(target) > DirectoryPathBytes {
		return "", ErrValidation
	}
	return target, nil
}

func directoryTruncateLabel(value string, maximum int) string {
	var result strings.Builder
	for _, character := range value {
		if result.Len()+utf8.RuneLen(character) > maximum {
			break
		}
		result.WriteRune(character)
	}
	return strings.Trim(result.String(), " .")
}
