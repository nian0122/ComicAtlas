package domain

import librarydomain "comicatlas.local/backend/internal/modules/library/domain"

// RestorePlan 是写事务前冻结的资料；不会持有调用者原快照的切片或指针。
type RestorePlan struct {
	TaskID, RecordedAt string
	SnapshotSHA256     string
	Snapshot           librarydomain.Snapshot
}
