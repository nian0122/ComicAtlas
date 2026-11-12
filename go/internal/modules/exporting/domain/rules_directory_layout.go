package domain

import (
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"crypto/sha256"
	"encoding/hex"
	"path"
	"sort"
	"strings"
)

func directoryKey(id string) string {
	sum := sha256.Sum256([]byte(id))
	return hex.EncodeToString(sum[:6])
}

func directoryExtension(source, role string) string {
	if role == "lq" || role == "retired_lq" {
		return ".webp"
	}
	ext := strings.ToLower(path.Ext(source))
	if len(ext) < 2 || len(ext) > 12 {
		return ".bin"
	}
	for _, c := range ext[1:] {
		if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
			return ".bin"
		}
	}
	return ext
}

func directoryLayout(value DirectoryInventory, bookName string) ([]string, map[string]string, error) {
	children := map[string][]treeNode{}
	for _, node := range value.Catalogs {
		parent := ""
		if node.ParentID != nil {
			parent = *node.ParentID
		}
		children[parent] = append(children[parent], treeNode{id: node.ID, title: node.Title, order: node.SortOrder})
	}
	directories := []string{}
	chapters := map[string]string{}
	for _, node := range value.Chapters {
		if node.State == "trashed" {
			name := "_retained/chapters/" + directoryKey(node.ID)
			chapters[node.ID] = name
			directories = append(directories, name)
			continue
		}
		parent := ""
		if node.CatalogID != nil {
			parent = *node.CatalogID
		}
		children[parent] = append(children[parent], treeNode{id: node.ID, title: node.Title, order: node.SortOrder, chapter: true})
	}
	for key := range children {
		sort.Slice(children[key], func(i, j int) bool {
			a, b := children[key][i], children[key][j]
			if a.order != b.order {
				return a.order < b.order
			}
			if a.chapter != b.chapter {
				return !a.chapter
			}
			return a.id < b.id
		})
	}
	var measure func(string) int
	measure = func(parent string) int {
		maximum := 0
		for _, node := range children[parent] {
			next := 1
			if !node.chapter {
				next += measure(node.id)
			}
			if next > maximum {
				maximum = next
			}
		}
		return maximum
	}
	depth := measure("")
	if depth < 1 {
		depth = 1
	}
	labelLimit := directoryStructuralLabelBytes(bookName, depth)
	if labelLimit == 0 {
		return nil, nil, ErrValidation
	}
	seen := map[string]bool{}
	var walk func(string, string, int) error
	walk = func(parent, prefix string, depth int) error {
		if depth > DirectoryPathDepth-2 {
			return ErrValidation
		}
		labels := make([]string, len(children[parent]))
		counts := map[string]int{}
		for index, node := range children[parent] {
			label := directoryStructuralName(node.title, labelLimit)
			labels[index] = label
			counts[directoryNameKey(label)]++
		}
		used := map[string]bool{}
		for index, node := range children[parent] {
			kind := "catalog:"
			if node.chapter {
				kind = "chapter:"
			}
			key := kind + node.id
			if seen[key] {
				return ErrValidation
			}
			seen[key] = true
			label := labels[index]
			duplicate := counts[directoryNameKey(label)] > 1
			if duplicate || used[directoryNameKey(label)] {
				label = directoryUniqueName(label, node.id, used, duplicate)
			}
			used[directoryNameKey(label)] = true
			name := path.Join(prefix, label)
			if len(name) > DirectoryPathBytes {
				return ErrValidation
			}
			directories = append(directories, name)
			if node.chapter {
				chapters[node.id] = name
			} else if err := walk(node.id, name, depth+1); err != nil {
				return err
			}
		}
		return nil
	}
	if err := walk("", "", 0); err != nil {
		return nil, nil, err
	}
	if len(chapters) != len(value.Chapters) || len(seen) != len(value.Catalogs)+len(value.Chapters)-countHiddenChapters(value) {
		return nil, nil, ErrValidation
	}
	sort.Strings(directories)
	return directories, chapters, nil
}

func countHiddenChapters(value DirectoryInventory) int {
	n := 0
	for _, node := range value.Chapters {
		if node.State == "trashed" {
			n++
		}
	}
	return n
}

func directoryOwnerRoot(comic, source string) (string, error) {
	if source == "metadata/"+comic+".json" {
		return "", nil
	}
	parts := strings.Split(source, "/")
	if len(parts) < 3 {
		return "", ErrManifest
	}
	switch parts[0] {
	case "hq", "lq", "thumbs":
		if parts[1] != comic {
			return "", ErrManifest
		}
		return strings.Join(parts[:2], "/"), nil
	case "retired":
		if len(parts) != 4 || parts[1] != "uploads" || !directoryID(parts[2]) {
			return "", ErrManifest
		}
		return strings.Join(parts[:3], "/"), nil
	case "trash":
		return strings.Join(parts[:2], "/"), nil
	}
	return "", ErrManifest
}

func directoryRoots(values map[string]bool) []string {
	roots := make([]string, 0, len(values))
	for root := range values {
		roots = append(roots, root)
	}
	sort.Strings(roots)
	result := make([]string, 0, len(roots))
	for _, root := range roots {
		if len(result) == 0 || !strings.HasPrefix(root, result[len(result)-1]+"/") {
			result = append(result, root)
		}
	}
	return result
}

// DirectoryPhysicalChapter 只承认新库布局中的独占物理章节；侧车、回收、上传保留件均走文件移动。
func DirectoryPhysicalChapter(source string) (string, bool) {
	normalized := source
	if strings.HasPrefix(source, "lq/") {
		normalized = "hq/" + strings.TrimPrefix(source, "lq/")
	}
	if _, _, ok := mediadomain.HQOwner(normalized); !ok || strings.HasPrefix(path.Base(source), ".comicatlas-page-") {
		return "", false
	}
	return path.Dir(source), true
}
