package domain

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	"comicatlas.local/backend/internal/platform/natural"
	"crypto/sha256"
	"encoding/hex"
	"sort"
	"strings"
)

func ScanReady(complete bool, status string) bool { return complete && status == "succeeded" }
func CandidateID(source string) string {
	sum := sha256.Sum256([]byte(source))
	return "candidate_" + hex.EncodeToString(sum[:16])
}
func SortTargets(targets []ScanTarget) {
	sort.Slice(targets, func(i, j int) bool {
		value := natural.Compare(targets[i].SourcePath, targets[j].SourcePath)
		if value == 0 {
			return targets[i].SourcePath < targets[j].SourcePath
		}
		return value < 0
	})
}
func ValidateSelection(items []Candidate) error {
	if len(items) < 1 || len(items) > jobsdomain.MaxImportBatchItems {
		return jobsdomain.ErrValidation
	}
	seen := map[string]bool{}
	for index, item := range items {
		if !item.Selectable || item.Kind != "directory" || seen[item.ID] {
			return jobsdomain.ErrValidation
		}
		seen[item.ID] = true
		for _, other := range items[:index] {
			if item.Kind == "directory" && other.Kind == "directory" && (strings.HasPrefix(item.SourcePath, other.SourcePath+"/") || strings.HasPrefix(other.SourcePath, item.SourcePath+"/")) {
				return jobsdomain.ErrValidation
			}
		}
	}
	return nil
}
