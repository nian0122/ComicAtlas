package domain

const ProtocolVersion = 1
const DefaultSampleCount = 10
const MaxSamples = 24
const MaxPageBytes int64 = 512 << 10
const MaxImageBytes int64 = 12 << 20
const MaxImageEdge = 1600
const MaxManifestBytes = 256 << 10
const MaxResponseBytes = 64 << 10
const MaxCandidateTags = 30
const MaxVocabularyBytes = 128 << 10

type Term struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

type Sample struct {
	MediaID     string `json:"mediaId"`
	Ordinal     int    `json:"ordinal"`
	SHA256      string `json:"sha256"`
	Size        int64  `json:"size"`
	Width       int    `json:"width"`
	Height      int    `json:"height"`
	ContentType string `json:"contentType"`
}

// Request 只含标识与冻结事实；本地路径不进入 AI 服务协议。
type Request struct {
	RequestID        string   `json:"requestId"`
	ComicID          string   `json:"comicId"`
	MetadataVersion  int      `json:"metadataVersion"`
	StructureVersion int      `json:"structureVersion"`
	Samples          []Sample `json:"samples"`
	Categories       []Term   `json:"categories"`
	Tags             []Term   `json:"tags"`
}
