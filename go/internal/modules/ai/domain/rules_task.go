package domain

import (
	"fmt"
	"slices"
)

func AnalysisRequestID(task string, attempt int) string { return fmt.Sprintf("%s_%d", task, attempt) }

func NormalizeAnalysisRequest(comic string, request AnalysisRequest) (AnalysisRequest, error) {
	if request.SampleCount == 0 {
		request.SampleCount = DefaultSampleCount
	}
	if !scope(comic, request.Version, request.StructureVersion) || request.SampleCount < 1 || request.SampleCount > MaxSamples {
		return request, ErrValidation
	}
	return request, nil
}

func ValidateAnalysisVersion(request AnalysisRequest, snapshot Snapshot) error {
	if ValidateSnapshot(snapshot) != nil {
		return ErrValidation
	}
	if request.Version != snapshot.MetadataVersion || request.StructureVersion != snapshot.StructureVersion {
		return ErrSource
	}
	return nil
}

func SameSnapshot(expected, current Snapshot) bool {
	return ValidateSnapshot(expected) == nil && ValidateSnapshot(current) == nil && expected.ComicID == current.ComicID && expected.MetadataVersion == current.MetadataVersion && expected.StructureVersion == current.StructureVersion && slices.Equal(expected.Images, current.Images) && slices.Equal(expected.Categories, current.Categories) && slices.Equal(expected.Tags, current.Tags)
}
