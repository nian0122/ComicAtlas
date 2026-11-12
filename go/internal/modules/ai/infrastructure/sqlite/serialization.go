package sqlite

import (
	aidomain "comicatlas.local/backend/internal/modules/ai/domain"
	"encoding/json"
)

func encodeSnapshot(value aidomain.Snapshot) ([]byte, error) {
	if err := aidomain.ValidateSnapshot(value); err != nil {
		return nil, err
	}
	stored := storedSnapshot{ComicID: value.ComicID, MetadataVersion: value.MetadataVersion, StructureVersion: value.StructureVersion, Categories: value.Categories, Tags: value.Tags, Images: make([]storedImage, 0, len(value.Images))}
	for _, image := range value.Images {
		stored.Images = append(stored.Images, storedImage{MediaID: image.MediaID, Ordinal: image.Ordinal, Path: image.Path, SHA256: image.SHA256})
	}
	data, err := json.Marshal(stored)
	if err != nil || len(data) > aidomain.MaxManifestBytes {
		return nil, aidomain.ErrValidation
	}
	return data, nil
}

func decodeSnapshot(data []byte) (value aidomain.Snapshot, err error) {
	if len(data) > aidomain.MaxManifestBytes {
		return value, aidomain.ErrSource
	}
	var stored storedSnapshot
	if json.Unmarshal(data, &stored) != nil {
		return value, aidomain.ErrSource
	}
	value = aidomain.Snapshot{ComicID: stored.ComicID, MetadataVersion: stored.MetadataVersion, StructureVersion: stored.StructureVersion, Categories: stored.Categories, Tags: stored.Tags, Images: make([]aidomain.SourceImage, 0, len(stored.Images))}
	for _, image := range stored.Images {
		value.Images = append(value.Images, aidomain.SourceImage{MediaID: image.MediaID, Ordinal: image.Ordinal, Path: image.Path, SHA256: image.SHA256})
	}
	return value, aidomain.ValidateSnapshot(value)
}
