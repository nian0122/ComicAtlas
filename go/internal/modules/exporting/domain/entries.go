package domain

import (
	"encoding/json"
	"encoding/xml"
	"path"
	"sort"
	"strings"
)

// archiveStructuralName 避开归档根目录的固定文档名。
func archiveStructuralName(value string, maximum int) string {
	name := directoryStructuralName(value, maximum)
	if strings.EqualFold(name, "ComicInfo.xml") {
		name = "_" + name
	}
	return name
}

func Entries(snapshot Snapshot) ([]Entry, error) {
	if err := ValidateSnapshot(snapshot); err != nil {
		return nil, err
	}
	children := map[string][]treeNode{}
	all := map[string]bool{}
	chapters := map[string]string{}
	for _, catalog := range snapshot.Catalogs {
		parent := ""
		if catalog.ParentID != nil {
			parent = *catalog.ParentID
		}
		if all[catalog.ID] {
			return nil, ErrValidation
		}
		all[catalog.ID] = true
		children[parent] = append(children[parent], treeNode{id: catalog.ID, parent: parent, title: catalog.Title, order: catalog.SortOrder})
	}
	for _, chapter := range snapshot.Chapters {
		parent := ""
		if chapter.CatalogID != nil {
			parent = *chapter.CatalogID
		}
		if all[chapter.ID] {
			return nil, ErrValidation
		}
		all[chapter.ID] = true
		children[parent] = append(children[parent], treeNode{id: chapter.ID, parent: parent, title: chapter.Title, order: chapter.SortOrder, chapter: true})
	}
	for key := range children {
		sort.Slice(children[key], func(i, j int) bool {
			a, b := children[key][i], children[key][j]
			if a.order != b.order {
				return a.order < b.order
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
	labelLimit := directoryStructuralLabelBytes("", depth)
	if labelLimit == 0 {
		return nil, ErrValidation
	}
	entries := []Entry{}
	visited := map[string]bool{}
	var walk func(string, string, int) error
	walk = func(parent, prefix string, depth int) error {
		if depth > 64 {
			return ErrValidation
		}
		labels := make([]string, len(children[parent]))
		counts := map[string]int{}
		for index, node := range children[parent] {
			label := archiveStructuralName(node.title, labelLimit)
			labels[index] = label
			counts[directoryNameKey(label)]++
		}
		used := map[string]bool{}
		for index, node := range children[parent] {
			if visited[node.id] {
				return ErrValidation
			}
			visited[node.id] = true
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
			entries = append(entries, Entry{Name: name + "/", Directory: true})
			if node.chapter {
				chapters[node.id] = name
				if len(children[node.id]) > 0 {
					return ErrValidation
				}
			} else if len(children[node.id]) > 0 {
				if err := walk(node.id, name, depth+1); err != nil {
					return err
				}
			}
		}
		return nil
	}
	if err := walk("", "", 1); err != nil {
		return nil, err
	}
	if len(visited) != len(all) {
		return nil, ErrValidation
	}
	pageCounts := map[string]map[string]int{}
	for _, source := range snapshot.Sources {
		label := directoryPageLabel(source.FileName)
		if pageCounts[source.ChapterID] == nil {
			pageCounts[source.ChapterID] = map[string]int{}
		}
		pageCounts[source.ChapterID][directoryNameKey(label)]++
	}
	pageNames := make([]string, len(snapshot.Sources))
	pageUsed := map[string]map[string]bool{}
	for index, source := range snapshot.Sources {
		label := directoryPageLabel(source.FileName)
		used := pageUsed[source.ChapterID]
		if used == nil {
			used = map[string]bool{}
			pageUsed[source.ChapterID] = used
		}
		duplicate := pageCounts[source.ChapterID][directoryNameKey(label)] > 1
		if duplicate || used[directoryNameKey(label)] {
			id := source.MediaID
			if id == "" {
				id = source.Path
			}
			label = directoryUniqueName(label, id, used, duplicate)
		}
		used[directoryNameKey(label)] = true
		pageNames[index] = label
	}
	portable := snapshot
	portable.Sources = append([]Source(nil), snapshot.Sources...)
	for index := range snapshot.Sources {
		source := snapshot.Sources[index]
		directory, ok := chapters[source.ChapterID]
		if !ok {
			return nil, ErrValidation
		}
		ext := strings.ToLower(path.Ext(source.Path))
		if ext == "" {
			return nil, ErrValidation
		}
		name := path.Join(directory, pageNames[index]+ext)
		if len(name) > DirectoryPathBytes {
			return nil, ErrValidation
		}
		portable.Sources[index].Path = name
		entries = append(entries, Entry{Name: name, Source: &snapshot.Sources[index]})
	}
	tags := []string{}
	for _, tag := range snapshot.Comic.Tags {
		tags = append(tags, tag.Name)
	}
	genre := snapshot.Comic.CategoryName
	if snapshot.Comic.Category != nil {
		genre = snapshot.Comic.Category.Name
	}
	info := comicXML{Title: snapshot.Comic.Title, Series: snapshot.Comic.Title, Writer: snapshot.Comic.Author, Summary: snapshot.Comic.Description, Genre: genre, Tags: strings.Join(tags, ", "), PageCount: len(snapshot.Sources)}
	if snapshot.ComicInfo != nil {
		info.Number = snapshot.ComicInfo.Number
	}
	xmlData, err := xml.Marshal(info)
	if err != nil {
		return nil, err
	}
	metadata, err := json.Marshal(portable)
	if err != nil {
		return nil, err
	}
	if len(metadata) > MaxSnapshotBytes {
		return nil, ErrValidation
	}
	entries = append([]Entry{{Name: "ComicInfo.xml", Data: append([]byte(xml.Header), xmlData...)}, {Name: "metadata.json", Data: metadata}}, entries...)
	if len(entries) > 20000 {
		return nil, ErrValidation
	}
	return entries, nil
}
