package domain

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"path"
	"strings"
)

func SourceFrom(choice SourceChoice) (SourceImage, error) {
	value := SourceImage{MediaID: choice.MediaID, Ordinal: choice.Ordinal}
	if choice.HQState != "available" && choice.HQState != "released" {
		return value, ErrSource
	}
	quality := "hq/"
	if choice.HQState == "available" {
		value.Path = choice.HQPath
	} else {
		if !choice.LQValid {
			return value, ErrSource
		}
		value.Path = choice.LQPath
		quality = "lq/"
	}
	if ValidateSource(value) != nil || !strings.HasPrefix(value.Path, quality) {
		return SourceImage{}, ErrSource
	}
	return value, nil
}

func ValidateSource(value SourceImage) error {
	if !identifier(value.MediaID) || value.Ordinal < 1 || value.Ordinal > librarydomain.MaxSnapshotMedia || value.Path == "" || len(value.Path) > 2048 || path.Clean(value.Path) != value.Path || strings.ContainsAny(value.Path, "\\:\x00") || !strings.HasPrefix(value.Path, "hq/") && !strings.HasPrefix(value.Path, "lq/") {
		return ErrSource
	}
	return nil
}

func ValidateSnapshot(value Snapshot) error {
	if !scope(value.ComicID, value.MetadataVersion, value.StructureVersion) || len(value.Images) == 0 || len(value.Images) > MaxSamples {
		return ErrValidation
	}
	seen, previous := map[string]bool{}, 0
	for _, image := range value.Images {
		if ValidateSource(image) != nil || seen[image.MediaID] || image.Ordinal <= previous {
			return ErrSource
		}
		seen[image.MediaID], previous = true, image.Ordinal
	}
	return validateVocabulary(value.Categories, value.Tags)
}
