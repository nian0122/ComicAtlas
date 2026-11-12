package domain

import (
	"encoding/json"
	"encoding/xml"
	"fmt"
	"path"
	"sort"
	"strings"
	"unicode"
)

func label(value string, maximum int) string {
	var result strings.Builder
	for _, r := range value {
		if unicode.IsControl(r) || strings.ContainsRune("/\\:<>\"|?*", r) {
			r = '_'
		}
		if result.Len()+len(string(r)) > maximum {
			break
		}
		result.WriteRune(r)
	}
	name := strings.Trim(result.String(), " .")
	if name == "" {
		return "untitled"
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
	entries := []Entry{}
	visited := map[string]bool{}
	var walk func(string, string, int) error
	walk = func(parent, prefix string, depth int) error {
		if depth > 64 {
			return ErrValidation
		}
		for index, node := range children[parent] {
			if visited[node.id] {
				return ErrValidation
			}
			visited[node.id] = true
			name := path.Join(prefix, fmt.Sprintf("%05d-%s", index+1, label(node.title, 20)))
			if len(name) > 1800 {
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
	portable := snapshot
	portable.Sources = append([]Source(nil), snapshot.Sources...)
	missingDigestBytes := 0
	for index := range snapshot.Sources {
		source := snapshot.Sources[index]
		if source.SHA256 == "" {
			missingDigestBytes += 64
		}
		directory, ok := chapters[source.ChapterID]
		if !ok {
			return nil, ErrValidation
		}
		ext := strings.ToLower(path.Ext(source.Path))
		if ext == "" {
			return nil, ErrValidation
		}
		name := path.Join(directory, fmt.Sprintf("%06d-%s%s", source.PageNumber, label(strings.TrimSuffix(source.FileName, path.Ext(source.FileName)), 100), ext))
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
	if len(metadata)+missingDigestBytes > MaxSnapshotBytes {
		return nil, ErrValidation
	}
	entries = append([]Entry{{Name: "ComicInfo.xml", Data: append([]byte(xml.Header), xmlData...)}, {Name: "metadata.json", Data: metadata}}, entries...)
	if len(entries) > 20000 {
		return nil, ErrValidation
	}
	return entries, nil
}
