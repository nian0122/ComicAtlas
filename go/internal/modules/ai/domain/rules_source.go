package domain

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"encoding/hex"
	"path"
	"strings"
)

func SourceFrom(choice SourceChoice) (SourceImage, error) {
	value := SourceImage{MediaID: choice.MediaID, Ordinal: choice.Ordinal}
	if choice.HQState != "available" && choice.HQState != "released" {
		return value, ErrSource
	}
	quality := "hq/"
	if choice.HQState == "available" && choice.HQSHA256 != "" {
		value.Path, value.SHA256 = choice.HQPath, choice.HQSHA256
	} else {
		record := mediadomain.LQRecord{SourceSHA256: choice.LQSourceSHA256, TaskID: choice.LQTaskID, Provenance: choice.LQProvenance}
		if !choice.LQValid || !mediadomain.LQMatchesSource(&record, choice.HQSHA256) {
			return value, ErrSource
		}
		value.Path, value.SHA256 = choice.LQPath, choice.LQSHA256
		quality = "lq/"
	}
	if ValidateSource(value) != nil || !strings.HasPrefix(value.Path, quality) {
		return SourceImage{}, ErrSource
	}
	return value, nil
}

func ValidateSource(value SourceImage) error {
	data, err := hex.DecodeString(value.SHA256)
	if !identifier(value.MediaID) || value.Ordinal < 1 || value.Ordinal > librarydomain.MaxSnapshotMedia || err != nil || len(data) != 32 || strings.ToLower(value.SHA256) != value.SHA256 || value.Path == "" || len(value.Path) > 2048 || path.Clean(value.Path) != value.Path || strings.ContainsAny(value.Path, "\\:\x00") || !strings.HasPrefix(value.Path, "hq/") && !strings.HasPrefix(value.Path, "lq/") {
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
