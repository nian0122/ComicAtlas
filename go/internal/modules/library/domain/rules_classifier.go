package domain

import (
	"iter"
	"sort"
	"strings"
	"unicode/utf8"
)

var keywordTags = []struct{ keyword, name string }{
	{"彩色", "彩色"}, {"全彩", "彩色"}, {"カラー", "彩色"},
	{"黑白", "黑白"}, {"白黑", "黑白"}, {"モノクロ", "黑白"},
	{"短篇", "短篇"}, {"短編", "短篇"}, {"one-shot", "短篇"},
	{"长篇", "长篇"}, {"長編", "长篇"}, {"连载", "连载"},
	{"合集", "合集"}, {"anthology", "合集"}, {"动画", "动画"}, {"anime", "动画"},
	{"video", "视频"}, {"视频", "视频"}, {"4k", "4K"},
}

func ValidateImportedMetadata(comic Comic) error {
	if err := ValidateOriginalTitle(comic.OriginalTitle); err != nil {
		return err
	}
	if !ValidName(comic.Title, 500) || len(comic.Author) > 2000 || len(comic.Description) > 65536 || len(comic.Tags) > MaxComicTags {
		return ErrValidation
	}
	if comic.CategoryName != "" && !ValidName(comic.CategoryName, 200) {
		return ErrValidation
	}
	for _, tag := range comic.Tags {
		if !ValidName(strings.TrimSpace(tag.Name), 200) {
			return ErrValidation
		}
	}
	return nil
}

func ClassifyImport(comic *Comic, categories iter.Seq[Term]) error {
	if err := ValidateImportedMetadata(*comic); err != nil {
		return err
	}
	search := comic.Title + " " + comic.Author + " " + comic.Description
	if comic.OriginalTitle != nil {
		search += " " + *comic.OriginalTitle
	}
	for _, tag := range comic.Tags {
		search += " " + tag.Name
	}
	search = strings.ToLower(search)
	var selected *Term
	longest := 0
	explicit := strings.TrimSpace(comic.CategoryName)
	for candidate := range categories {
		if explicit != "" {
			if strings.EqualFold(explicit, candidate.Name) {
				copy := candidate
				selected = &copy
			}
		} else if size := utf8.RuneCountInString(candidate.Name); size > longest && strings.Contains(search, strings.ToLower(candidate.Name)) {
			copy := candidate
			selected = &copy
			longest = size
		}
	}
	comic.Category = selected
	if selected != nil {
		comic.CategoryName = selected.Name
	}
	seen := map[string]bool{}
	tags := []ComicTag{}
	for _, tag := range comic.Tags {
		name := strings.TrimSpace(tag.Name)
		if !ValidName(name, 200) {
			return ErrValidation
		}
		key := strings.ToLower(name)
		if seen[key] {
			continue
		}
		seen[key] = true
		tags = append(tags, ComicTag{Term: Term{Name: name}, Source: "comicinfo"})
	}
	for _, rule := range keywordTags {
		key := strings.ToLower(rule.name)
		if !seen[key] && strings.Contains(search, rule.keyword) {
			seen[key] = true
			tags = append(tags, ComicTag{Term: Term{Name: rule.name}, Source: "auto"})
		}
	}
	if len(tags) > MaxComicTags {
		return ErrValidation
	}
	sort.Slice(tags, func(i, j int) bool { return tags[i].Name < tags[j].Name })
	comic.Tags = tags
	return nil
}
