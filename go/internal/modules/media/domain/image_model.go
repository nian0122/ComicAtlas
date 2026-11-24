package domain

const DefaultImagePixels int64 = 16000000
const MaxImageInputBytes int64 = 1 << 30
const MaxImageOutputBytes int64 = 128 << 20

// MaxImageDecodePixels 是单张图片整帧解码像素的硬上限。
// 共享预算按解码像素权重分配，超限输入仍拒绝，避免单图耗尽进程或容器内存。
const MaxImageDecodePixels int64 = 128 << 20

type ImageSpec struct {
	MaxLongEdge int `json:"maxLongEdge"`
	Quality     int `json:"quality"`
}
type ImageHeader struct {
	Width, Height   int
	Format          string
	Orientation     int
	SimpleLossyWebP bool
}
type ImagePlan struct {
	Width, Height             int
	DecodeWidth, DecodeHeight int
	JPEGScale                 int
	ScaleWebP                 bool
}
type ImageRequest struct {
	SourcePath string    `json:"-"`
	WorkRoot   string    `json:"-"`
	Spec       ImageSpec `json:"spec"`
}
type ImageArtifact struct {
	Path               string `json:"-"`
	TemporaryDirectory string `json:"-"`
	Width              int    `json:"width"`
	Height             int    `json:"height"`
	Size               int64  `json:"size"`
	JPEGScale          int    `json:"-"`
}
