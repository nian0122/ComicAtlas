package domain

import (
	"sort"
	"strings"
)

func ValidateMetadataVersion(current, expected int) error {
	if current != expected {
		return ErrConflict
	}
	return nil
}

func ValidateMetadataReferences(found, expected int) error {
	if found != expected {
		return ErrValidation
	}
	return nil
}

func ValidateMetadataPatch(patch MetadataPatch) error {
	if patch.Title == nil && patch.Author == nil && patch.Description == nil && patch.CategoryID == nil && !patch.ClearCategory && len(patch.AddTagIDs) == 0 && len(patch.RemoveTagIDs) == 0 {
		return ErrValidation
	}
	if patch.Title != nil && !ValidName(strings.TrimSpace(*patch.Title), 500) || patch.Author != nil && len(*patch.Author) > 2000 || patch.Description != nil && len(*patch.Description) > 65536 || patch.CategoryID != nil && (*patch.CategoryID == "" || len(*patch.CategoryID) > 128 || patch.ClearCategory) || len(patch.AddTagIDs) > 100 || len(patch.RemoveTagIDs) > 100 {
		return ErrValidation
	}
	seen := map[string]bool{}
	for _, id := range patch.AddTagIDs {
		if id == "" || len(id) > 128 || seen[id] {
			return ErrValidation
		}
		seen[id] = true
	}
	removed := map[string]bool{}
	for _, id := range patch.RemoveTagIDs {
		if id == "" || len(id) > 128 || seen[id] || removed[id] {
			return ErrValidation
		}
		removed[id] = true
	}
	return nil
}
func ApplyMetadataPatch(current Comic, patch MetadataPatch) (MetadataUpdate, error) {
	if err := ValidateMetadataPatch(patch); err != nil {
		return MetadataUpdate{}, err
	}
	next := MetadataUpdate{Version: current.Version, Title: current.Title, OriginalTitle: current.OriginalTitle, Author: current.Author, Description: current.Description, TagIDs: []string{}}
	if current.Category != nil {
		id := current.Category.ID
		next.CategoryID = &id
	}
	if patch.Title != nil {
		next.Title = *patch.Title
	}
	if patch.Author != nil {
		next.Author = *patch.Author
	}
	if patch.Description != nil {
		next.Description = *patch.Description
	}
	if patch.ClearCategory {
		next.CategoryID = nil
	}
	if patch.CategoryID != nil {
		next.CategoryID = patch.CategoryID
	}
	ids := map[string]bool{}
	for _, tag := range current.Tags {
		ids[tag.ID] = true
	}
	for _, id := range patch.RemoveTagIDs {
		delete(ids, id)
	}
	for _, id := range patch.AddTagIDs {
		ids[id] = true
	}
	for id := range ids {
		next.TagIDs = append(next.TagIDs, id)
	}
	sort.Strings(next.TagIDs)
	return next, ValidateMetadata(next)
}
