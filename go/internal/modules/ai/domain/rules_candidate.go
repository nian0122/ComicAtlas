package domain

import "strings"

func ValidateCandidate(request Request, value Candidate) error {
	if ValidateRequest(request) != nil || value.Tags == nil || value.Warnings == nil || len(value.Tags) > MaxCandidateTags || len(value.Warnings) > 20 {
		return ErrProtocol
	}
	if value.Title != nil && !text(*value.Title, 500) || value.Author != nil && !text(*value.Author, 2000) || value.Description != nil && !description(*value.Description) {
		return ErrProtocol
	}
	categories := map[string]bool{}
	for _, term := range request.Categories {
		categories[term.ID] = true
	}
	if value.CategoryID != nil && !categories[*value.CategoryID] {
		return ErrProtocol
	}
	byID, byName := map[string]string{}, map[string]string{}
	for _, term := range request.Tags {
		byID[term.ID] = term.Name
		byName[strings.ToLower(term.Name)] = term.ID
	}
	pages := map[int]bool{}
	for _, sample := range request.Samples {
		pages[sample.Ordinal] = true
	}
	seen := map[string]bool{}
	for _, tag := range value.Tags {
		key := strings.ToLower(tag.Name)
		if !text(tag.Name, 100) || seen[key] || len(tag.Evidence) == 0 || len(tag.Evidence) > len(pages) {
			return ErrProtocol
		}
		if tag.ExistingID != nil {
			if byID[*tag.ExistingID] != tag.Name {
				return ErrProtocol
			}
		} else if byName[key] != "" {
			return ErrProtocol
		}
		previous := 0
		for _, position := range tag.Evidence {
			if !pages[position] || position <= previous {
				return ErrProtocol
			}
			previous = position
		}
		seen[key] = true
	}
	for _, warning := range value.Warnings {
		if !text(warning, 500) {
			return ErrProtocol
		}
	}
	return nil
}
