package domain

// VideoEncodeRequest 由受控文件存储提供本地路径与数据库中的原件摘要。
type VideoEncodeRequest struct {
	SourcePath      string
	SourceSize      int64
	SourceSHA256    string
	OutputDirectory string
}

// VideoEncodeArtifact 是尚未发布的私有产物；任务提交前不得替换媒体引用。
type VideoEncodeArtifact struct {
	Path   string
	Size   int64
	SHA256 string
	Video  Video
}

const MaxTranscodeBytes int64 = 20 << 30
const MaxTranscodePixels int64 = 16 << 20
