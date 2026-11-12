package domain

import (
	"strings"
	"unicode"
)

func ValidPage(page, size int) bool { return page >= 1 && page <= 1000000 && size >= 1 && size <= 100 }

func ValidName(name string, maximum int) bool {
	if name == "" || len(name) > maximum {
		return false
	}
	for _, character := range name {
		if unicode.IsControl(character) {
			return false
		}
	}
	return true
}

func ValidateMetadata(update MetadataUpdate) error {
	if err := ValidateOriginalTitle(update.OriginalTitle); err != nil {
		return err
	}
	if update.Version < 1 || !ValidName(strings.TrimSpace(update.Title), 500) || len(update.Author) > 2000 || len(update.Description) > 65536 || update.TagIDs == nil || len(update.TagIDs) > MaxComicTags {
		return ErrValidation
	}
	seen := map[string]bool{}
	for _, id := range update.TagIDs {
		if id == "" || seen[id] {
			return ErrValidation
		}
		seen[id] = true
	}
	if update.CategoryID != nil && *update.CategoryID == "" {
		return ErrValidation
	}
	return nil
}

func ValidTermKind(kind string) bool { return kind == "categories" || kind == "tags" }

func ValidateTermWrite(expected, current int, scopeLocked bool) error {
	if expected != current || scopeLocked {
		return ErrConflict
	}
	return nil
}
