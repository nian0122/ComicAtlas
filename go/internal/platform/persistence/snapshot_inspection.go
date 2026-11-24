package persistence

import (
	"context"
	"database/sql"
	"strings"
)

func inspectSnapshot(ctx context.Context, reader *sql.DB) (SnapshotInfo, error) {
	var info SnapshotInfo
	if err := reader.QueryRowContext(ctx, "SELECT value FROM app_metadata WHERE key='format'").Scan(&info.Format); err != nil {
		return info, snapshotFailure(ctx)
	}
	if info.Format != "comicatlas-go-v1" {
		return info, ErrSnapshotInvalid
	}
	entries, err := migrations.ReadDir("migrations")
	if err != nil {
		return info, ErrSnapshotInvalid
	}
	// embed 的目录枚举按名称排列；不把备份当成启动迁移。
	names := []string{}
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".sql") {
			continue
		}
		names = append(names, entry.Name())
	}
	rows, err := reader.QueryContext(ctx, "SELECT name FROM schema_migrations ORDER BY name")
	if err != nil {
		return info, snapshotFailure(ctx)
	}
	for rows.Next() {
		var name string
		if rows.Scan(&name) != nil || info.MigrationCount >= len(names) || name != names[info.MigrationCount] {
			rows.Close()
			return info, ErrSnapshotInvalid
		}
		info.MigrationCount++
	}
	err = rows.Err()
	rows.Close()
	if err != nil || info.MigrationCount == 0 {
		return info, snapshotFailure(ctx)
	}
	var integrity string
	if err = reader.QueryRowContext(ctx, "PRAGMA integrity_check").Scan(&integrity); err != nil {
		return info, snapshotFailure(ctx)
	}
	if integrity != "ok" {
		return info, ErrSnapshotInvalid
	}
	checks, err := reader.QueryContext(ctx, "PRAGMA foreign_key_check")
	if err != nil {
		return info, snapshotFailure(ctx)
	}
	violated := checks.Next()
	err = checks.Err()
	checks.Close()
	if err != nil {
		return info, snapshotFailure(ctx)
	}
	if violated {
		return info, ErrSnapshotInvalid
	}
	return info, ctx.Err()
}
