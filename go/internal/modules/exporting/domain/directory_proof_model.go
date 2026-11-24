package domain

const DirectoryMarkerName = ".comicatlas-directory.json"

// DirectoryFileState 是唯一冻结计划中的文件属性；完整性由身份、大小和修改时间确定。
type DirectoryFileState struct {
	Identity string `json:"identity"`
	Modified int64  `json:"modified"`
	Size     int64  `json:"size"`
	Missing  bool   `json:"missing"`
}

// DirectorySeal 仅绑定不可变计划和属性测量，不持久化第二份运行清单。
type DirectorySeal struct {
	TaskID        string `json:"taskId"`
	FileCount     int    `json:"fileCount"`
	ObservedBytes int64  `json:"observedBytes"`
}

type DirectoryMarker struct {
	Version int    `json:"version"`
	TaskID  string `json:"taskId"`
}

type DirectoryExecutionStats struct {
	Renames        int   `json:"renames"`
	DirectoryMoves int   `json:"directoryMoves"`
	ResultBatches  int   `json:"resultBatches"`
	SceneMillis    int64 `json:"sceneMillis"`

	MoveMillis    int64 `json:"moveMillis"`
	PersistMillis int64 `json:"persistMillis"`
	PublishMillis int64 `json:"publishMillis"`
}

type DirectoryReport struct {
	FileCount int                     `json:"fileCount"`
	Bytes     int64                   `json:"bytes"`
	Stats     DirectoryExecutionStats `json:"stats"`
}
