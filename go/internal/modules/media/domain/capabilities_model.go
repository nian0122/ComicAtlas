package domain

// ToolCapability 描述部署中固定媒体工具的可用状态，不包含可执行文件路径。
type ToolCapability struct {
	Configured bool    `json:"configured"`
	Available  bool    `json:"available"`
	Version    *string `json:"version"`
}

// Capabilities 是一次按需读取的媒体工具能力快照。
type Capabilities struct {
	FFmpeg  ToolCapability `json:"ffmpeg"`
	FFprobe ToolCapability `json:"ffprobe"`
	Cwebp   ToolCapability `json:"cwebp"`
	Djpeg   ToolCapability `json:"djpeg"`
}
