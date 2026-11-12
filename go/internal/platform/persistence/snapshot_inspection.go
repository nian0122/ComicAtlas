package persistence

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"fmt"
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
	names, checksums := []string{}, []string{}
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".sql") {
			continue
		}
		data, err := migrations.ReadFile("migrations/" + entry.Name())
		if err != nil {
			return info, ErrSnapshotInvalid
		}
		names = append(names, entry.Name())
		checksums = append(checksums, fmt.Sprintf("%x", sha256.Sum256(data)))
	}
	rows, err := reader.QueryContext(ctx, "SELECT name,checksum FROM schema_migrations ORDER BY name")
	if err != nil {
		return info, snapshotFailure(ctx)
	}
	digest := sha256.New()
	for rows.Next() {
		var name, checksum string
		if rows.Scan(&name, &checksum) != nil || info.MigrationCount >= len(names) || name != names[info.MigrationCount] || checksum != checksums[info.MigrationCount] {
			rows.Close()
			return info, ErrSnapshotInvalid
		}
		fmt.Fprintf(digest, "%s:%s\n", name, checksum)
		info.MigrationCount++
	}
	err = rows.Err()
	rows.Close()
	if err != nil || info.MigrationCount == 0 {
		return info, snapshotFailure(ctx)
	}
	info.MigrationChecksum = fmt.Sprintf("%x", digest.Sum(nil))
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
