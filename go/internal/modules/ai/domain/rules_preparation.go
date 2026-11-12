package domain

import (
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"slices"
)

// PreparationSpecs 固定有界降档，不通过提高预算接纳过大图片。
func PreparationSpecs() []mediadomain.ImageSpec {
	return []mediadomain.ImageSpec{{MaxLongEdge: MaxImageEdge, Quality: 70}, {MaxLongEdge: 1200, Quality: 60}, {MaxLongEdge: 960, Quality: 50}, {MaxLongEdge: 640, Quality: 45}}
}

func ValidatePrepared(snapshot Snapshot, request Request) error {
	if ValidateSnapshot(snapshot) != nil || ValidateRequest(request) != nil {
		return ErrValidation
	}
	if request.ComicID != snapshot.ComicID || request.MetadataVersion != snapshot.MetadataVersion || request.StructureVersion != snapshot.StructureVersion || len(request.Samples) != len(snapshot.Images) || !slices.Equal(request.Categories, snapshot.Categories) || !slices.Equal(request.Tags, snapshot.Tags) {
		return ErrSource
	}
	for index, sample := range request.Samples {
		if sample.MediaID != snapshot.Images[index].MediaID || sample.Ordinal != snapshot.Images[index].Ordinal {
			return ErrSource
		}
	}
	return nil
}
