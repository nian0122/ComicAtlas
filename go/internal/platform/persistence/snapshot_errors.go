package persistence

import "errors"

var (
	ErrSnapshotMissing = errors.New("备份源数据库或数据目录不存在")
	ErrSnapshotInvalid = errors.New("SQLite 快照格式、迁移或完整性无效")
	ErrSnapshotExists  = errors.New("SQLite 快照目标或日志文件已存在")
)
