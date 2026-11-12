package domain

const DefaultImagePixels int64 = 16000000
const MaxImageInputBytes int64 = 1 << 30
const MaxImageOutputBytes int64 = 128 << 20

type ImageSpec struct {
	MaxLongEdge int `json:"maxLongEdge"`
	Quality     int `json:"quality"`
}
type ImageHeader struct {
	Width, Height int
	Format        string
	Orientation   int
}
type ImagePlan struct {
	Width, Height             int
	DecodeWidth, DecodeHeight int
	JPEGScale                 int
}
type ImageRequest struct {
	SourcePath   string    `json:"-"`
	SourceSHA256 string    `json:"-"`
	WorkRoot     string    `json:"-"`
	Spec         ImageSpec `json:"spec"`
}
type ImageArtifact struct {
	Path               string `json:"-"`
	TemporaryDirectory string `json:"-"`
	Width              int    `json:"width"`
	Height             int    `json:"height"`
	Size               int64  `json:"size"`
	SHA256             string `json:"-"`
	JPEGScale          int    `json:"-"`
}
