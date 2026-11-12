package domain

import "sort"

func ValidateComicFilter(filter ComicFilter) error {
	if filter.Reaction != "" && filter.Reaction != "any" && filter.Reaction != "like" && filter.Reaction != "dislike" {
		return ErrValidation
	}
	if len(filter.Keyword) > 500 || len(filter.CategoryID) > 128 || len(filter.TagID) > 128 || (filter.ReadStatus != "" && filter.ReadStatus != "read" && filter.ReadStatus != "unread" && filter.ReadStatus != "reading" && filter.ReadStatus != "completed") {
		return ErrValidation
	}
	return nil
}
func NormalizeSelection(value ComicSelection) (ComicSelection, error) {
	if len(value.IDs) > MaxSelectedComics || len(value.ExcludedIDs) > MaxSelectedComics {
		return value, ErrValidation
	}
	switch value.Mode {
	case "ids":
		if len(value.IDs) == 0 || value.Filter != nil || len(value.ExcludedIDs) > 0 {
			return value, ErrValidation
		}
	case "filter":
		if len(value.IDs) > 0 || value.Filter == nil {
			return value, ErrValidation
		}
		if err := ValidateComicFilter(*value.Filter); err != nil {
			return value, err
		}
	default:
		return value, ErrValidation
	}
	var err error
	value.IDs, err = selectionIDs(value.IDs)
	if err != nil {
		return value, err
	}
	value.ExcludedIDs, err = selectionIDs(value.ExcludedIDs)
	return value, err
}
func selectionIDs(ids []string) ([]string, error) {
	if len(ids) == 0 {
		return nil, nil
	}
	seen := map[string]bool{}
	result := make([]string, 0, len(ids))
	for _, id := range ids {
		if id == "" || len(id) > 128 {
			return nil, ErrValidation
		}
		if !seen[id] {
			result = append(result, id)
			seen[id] = true
		}
	}
	sort.Strings(result)
	return result, nil
}
