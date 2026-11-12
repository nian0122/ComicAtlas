package domain

const DirectoryMarkerName = ".comicatlas-directory.json"

// DirectoryFileState 是唯一冻结计划中的文件属性；SHA256 是既有登记摘要，不是预检读取结果。
type DirectoryFileState struct {
	Identity string `json:"identity"`
	Modified int64  `json:"modified"`
	Size     int64  `json:"size"`
	SHA256   string `json:"sha256"`
	Missing  bool   `json:"missing"`
}

// DirectorySeal 仅绑定不可变计划和属性测量，不持久化第二份运行清单。
type DirectorySeal struct {
	TaskID        string `json:"taskId"`
	PlanSHA256    string `json:"planSha256"`
	FilesSHA256   string `json:"filesSha256"`
	FileCount     int    `json:"fileCount"`
	ObservedBytes int64  `json:"observedBytes"`
}

type DirectoryMarker struct {
	Version    int    `json:"version"`
	TaskID     string `json:"taskId"`
	PlanSHA256 string `json:"planSha256"`
}

type DirectoryExecutionStats struct {
	HashFiles      int   `json:"hashFiles"`
	HashBytes      int64 `json:"hashBytes"`
	Renames        int   `json:"renames"`
	DirectoryMoves int   `json:"directoryMoves"`
	ResultBatches  int   `json:"resultBatches"`
	SceneMillis    int64 `json:"sceneMillis"`
	ContentMillis  int64 `json:"contentMillis"`
	MoveMillis     int64 `json:"moveMillis"`
	PersistMillis  int64 `json:"persistMillis"`
	PublishMillis  int64 `json:"publishMillis"`
}

type DirectoryReport struct {
	ResultSHA256 string                  `json:"resultSha256"`
	FileCount    int                     `json:"fileCount"`
	Bytes        int64                   `json:"bytes"`
	Stats        DirectoryExecutionStats `json:"stats"`
}
