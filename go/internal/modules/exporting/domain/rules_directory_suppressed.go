package domain

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"strings"
)

func directorySuppressedPaths(value DirectoryInventory) error {
	if len(value.SuppressedPaths) == 0 {
		return nil
	}
	if len(value.SuppressedPaths)+len(value.Media) > MaxDirectoryFiles {
		return ErrValidation
	}
	seen := map[string]bool{}
	for _, media := range value.Media {
		seen[strings.ToLower(media.StoragePath)] = true
	}
	for _, name := range value.SuppressedPaths {
		key := strings.ToLower(name)
		if !directorySource(name) || !strings.HasPrefix(name, "hq/"+value.Comic.ID+"/") || seen[key] || len(strings.Split(name, "/")) > 67 {
			return ErrValidation
		}
		for _, part := range strings.Split(name, "/") {
			if !librarydomain.ValidName(part, 255) {
				return ErrValidation
			}
		}
		seen[key] = true
	}
	return nil
}
