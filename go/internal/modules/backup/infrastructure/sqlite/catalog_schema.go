package sqlite

import (
	"context"
	"database/sql"

	"comicatlas.local/backend/internal/modules/backup/domain"
)

func inspectCatalog(ctx context.Context, database *sql.DB) error {
	rows, err := database.QueryContext(ctx, "SELECT type,name,sql FROM sqlite_schema ORDER BY name")
	if err != nil {
		return catalogError(ctx, true)
	}
	want := map[string]string{"backup_entries": entriesDDL, "backup_info": infoDDL}
	count := 0
	for rows.Next() {
		var kind, name, statement string
		if rows.Scan(&kind, &name, &statement) != nil || kind != "table" || want[name] != statement {
			rows.Close()
			return domain.ErrInvalid
		}
		count++
	}
	err = rows.Err()
	rows.Close()
	if err != nil || count != len(want) {
		return catalogError(ctx, true)
	}
	var format string
	var version, entries int
	if err = database.QueryRowContext(ctx, "SELECT COUNT(*),MAX(format),MAX(version) FROM backup_info").Scan(&entries, &format, &version); err != nil || entries != 1 || format != domain.IndexFormat || version != domain.Version {
		return catalogError(ctx, true)
	}
	var integrity string
	if err := database.QueryRowContext(ctx, "PRAGMA integrity_check").Scan(&integrity); err != nil || integrity != "ok" {
		return catalogError(ctx, true)
	}
	return nil
}
