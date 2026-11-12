package domain

import "strings"

func CleanComicInfo(value *ComicInfo) *ComicInfo {
	if value == nil {
		return nil
	}
	copy := *value
	copy.Series, copy.Title, copy.Number = strings.TrimSpace(copy.Series), strings.TrimSpace(copy.Title), strings.TrimSpace(copy.Number)
	copy.Writer, copy.Summary, copy.Genre = strings.TrimSpace(copy.Writer), strings.TrimSpace(copy.Summary), strings.TrimSpace(copy.Genre)
	copy.Tags = append([]string{}, value.Tags...)
	return &copy
}

func CleanOriginalTitle(value *string) *string {
	if value == nil {
		return nil
	}
	clean := strings.TrimSpace(*value)
	if clean == "" {
		return nil
	}
	return &clean
}
func ValidateOriginalTitle(value *string) error {
	if clean := CleanOriginalTitle(value); clean != nil && !ValidName(*clean, 500) {
		return ErrValidation
	}
	return nil
}
func ComicInfoTags(value string) []string {
	result := []string{}
	seen := map[string]bool{}
	for _, part := range strings.FieldsFunc(value, func(c rune) bool { return c == ',' || c == ';' || c == '，' || c == '；' }) {
		name := strings.TrimSpace(part)
		key := strings.ToLower(name)
		if name != "" && !seen[key] {
			seen[key] = true
			result = append(result, name)
		}
	}
	return result
}
func ValidateComicInfo(value *ComicInfo) error {
	if value == nil {
		return nil
	}
	for _, field := range []struct {
		value string
		limit int
	}{{value.Series, 500}, {value.Title, 500}, {value.Number, 100}, {value.Genre, 200}} {
		if field.value != "" && !ValidName(field.value, field.limit) {
			return ErrValidation
		}
	}
	if len(value.Writer) > 2000 || len(value.Summary) > 65536 || len(value.Tags) > 100 {
		return ErrValidation
	}
	for _, tag := range value.Tags {
		if !ValidName(tag, 200) {
			return ErrValidation
		}
	}
	return nil
}
