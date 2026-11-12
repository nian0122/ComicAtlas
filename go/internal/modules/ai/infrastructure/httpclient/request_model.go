package httpclient

import aidomain "comicatlas.local/backend/internal/modules/ai/domain"

type wireSample struct {
	aidomain.Sample
	Field string `json:"field"`
}

type wireManifest struct {
	Version          int             `json:"version"`
	RequestID        string          `json:"requestId"`
	ComicID          string          `json:"comicId"`
	MetadataVersion  int             `json:"metadataVersion"`
	StructureVersion int             `json:"structureVersion"`
	Samples          []wireSample    `json:"samples"`
	Categories       []aidomain.Term `json:"categories"`
	Tags             []aidomain.Term `json:"tags"`
}
