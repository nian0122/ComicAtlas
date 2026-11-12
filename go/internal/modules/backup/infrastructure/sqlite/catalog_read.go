package sqlite

import (
	"context"
	"database/sql"
	"errors"

	"comicatlas.local/backend/internal/modules/backup/domain"
)

func (reader *catalogReader) Close() error {
	if reader.database.Close() != nil {
		return domain.ErrIO
	}
	return nil
}

func (reader *catalogReader) Summary(ctx context.Context) (domain.Summary, error) {
	var value domain.Summary
	var total int64
	err := reader.database.QueryRowContext(ctx, "SELECT COUNT(*),COALESCE(SUM(kind='file'),0),COALESCE(SUM(kind='directory'),0),COALESCE(SUM(CASE WHEN kind='file' THEN size ELSE 0 END),0) FROM backup_entries").Scan(&total, &value.Files, &value.Directories, &value.Bytes)
	if err != nil || total != value.Files+value.Directories || domain.ValidateSummary(value) != nil {
		return value, catalogError(ctx, true)
	}
	return value, nil
}

func (reader *catalogReader) Find(ctx context.Context, path string) (domain.Entry, error) {
	var entry domain.Entry
	if !domain.ValidPath(path) {
		return entry, domain.ErrInvalid
	}
	err := reader.database.QueryRowContext(ctx, "SELECT path,kind,size,sha256,modified_at FROM backup_entries WHERE path=?", path).Scan(&entry.Path, &entry.Kind, &entry.Size, &entry.SHA256, &entry.ModifiedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return entry, domain.ErrNotFound
	}
	if err != nil || domain.ValidateEntry(entry) != nil {
		return domain.Entry{}, catalogError(ctx, true)
	}
	return entry, nil
}

func (reader *catalogReader) Each(ctx context.Context, consume func(domain.Entry) error) error {
	summary, err := reader.Summary(ctx)
	if err != nil {
		return err
	}
	last, seen := "", int64(0)
	for {
		rows, err := reader.database.QueryContext(ctx, "SELECT path,kind,size,sha256,modified_at FROM backup_entries WHERE path>? ORDER BY path LIMIT 128", last)
		if err != nil {
			return catalogError(ctx, true)
		}
		batch := make([]domain.Entry, 0, 128)
		for rows.Next() {
			var entry domain.Entry
			if rows.Scan(&entry.Path, &entry.Kind, &entry.Size, &entry.SHA256, &entry.ModifiedAt) != nil || domain.ValidateEntry(entry) != nil {
				rows.Close()
				return domain.ErrInvalid
			}
			batch = append(batch, entry)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return catalogError(ctx, true)
		}
		if len(batch) == 0 {
			if seen != summary.Files+summary.Directories {
				return domain.ErrInvalid
			}
			return ctx.Err()
		}
		// 关闭读游标后再访问文件，避免文件处理占用 SQLite 事务或唯一连接。
		for _, entry := range batch {
			if err := ctx.Err(); err != nil {
				return err
			}
			seen++
			if seen > domain.MaxEntries || entry.Path <= last {
				return domain.ErrInvalid
			}
			if err := consume(entry); err != nil {
				return err
			}
			last = entry.Path
		}
	}
}

func catalogRoots(ctx context.Context, reader *catalogReader) error {
	data, err := reader.Find(ctx, "data")
	if err != nil {
		return missingCatalogRoot(err)
	}
	media, err := reader.Find(ctx, "media")
	if err != nil {
		return missingCatalogRoot(err)
	}
	database, err := reader.Find(ctx, domain.DatabasePath)
	if err != nil {
		return missingCatalogRoot(err)
	}
	if domain.ValidateRoots(data, media, database) != nil {
		return domain.ErrInvalid
	}
	return nil
}

func missingCatalogRoot(err error) error {
	if errors.Is(err, domain.ErrNotFound) {
		return domain.ErrInvalid
	}
	return err
}
