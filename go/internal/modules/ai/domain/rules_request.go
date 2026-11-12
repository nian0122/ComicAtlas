package domain

import (
	"encoding/hex"
	"strings"
	"unicode"
	"unicode/utf8"

	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
)

func text(value string, maximum int) bool {
	return utf8.ValidString(value) && strings.TrimSpace(value) == value && librarydomain.ValidName(value, maximum)
}

func identifier(value string) bool { return text(value, 128) }

func ValidateRequest(value Request) error {
	if !identifier(value.RequestID) || !scope(value.ComicID, value.MetadataVersion, value.StructureVersion) || len(value.Samples) < 1 || len(value.Samples) > MaxSamples {
		return ErrValidation
	}
	previous := 0
	ids := map[string]bool{}
	var size int64
	for _, sample := range value.Samples {
		digest, err := hex.DecodeString(sample.SHA256)
		if !identifier(sample.MediaID) || ids[sample.MediaID] || sample.Ordinal <= previous || sample.Ordinal > librarydomain.MaxSnapshotMedia || err != nil || len(digest) != 32 || strings.ToLower(sample.SHA256) != sample.SHA256 || sample.Size < 1 || sample.Size > MaxPageBytes || size > MaxImageBytes-sample.Size || sample.Width < 1 || sample.Height < 1 || sample.Width > MaxImageEdge || sample.Height > MaxImageEdge {
			return ErrValidation
		}
		if sample.ContentType != "image/webp" && sample.ContentType != "image/jpeg" && sample.ContentType != "image/png" {
			return ErrValidation
		}
		ids[sample.MediaID] = true
		previous = sample.Ordinal
		size += sample.Size
	}
	return validateVocabulary(value.Categories, value.Tags)
}

func scope(comicID string, metadataVersion, structureVersion int) bool {
	return identifier(comicID) && metadataVersion > 0 && structureVersion > 0
}

func validateVocabulary(categories, tags []Term) error {
	if categories == nil || tags == nil || len(categories) > 200 || len(tags) > 1000 {
		return ErrValidation
	}
	bytes := 0
	for _, terms := range [][]Term{categories, tags} {
		seenIDs, seenNames := map[string]bool{}, map[string]bool{}
		for _, term := range terms {
			key := strings.ToLower(term.Name)
			bytes += len(term.ID) + len(term.Name)
			if !identifier(term.ID) || !text(term.Name, 100) || seenIDs[term.ID] || seenNames[key] || bytes > MaxVocabularyBytes {
				return ErrValidation
			}
			seenIDs[term.ID] = true
			seenNames[key] = true
		}
	}
	return nil
}

func description(value string) bool {
	if !utf8.ValidString(value) || value == "" || len(value) > 8192 || strings.TrimSpace(value) != value {
		return false
	}
	for _, character := range value {
		if unicode.IsControl(character) && character != '\n' && character != '\t' {
			return false
		}
	}
	return true
}
