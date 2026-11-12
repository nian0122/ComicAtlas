package persistence

// SnapshotInfo 不含业务行或本地路径，用于核对备份的格式与迁移前缀。
type SnapshotInfo struct {
	Format            string `json:"format"`
	MigrationCount    int    `json:"migrationCount"`
	MigrationChecksum string `json:"migrationChecksum"`
}
