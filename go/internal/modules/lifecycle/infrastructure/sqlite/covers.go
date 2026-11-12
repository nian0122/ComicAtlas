package sqlite

import (
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
)

func (repo *repository) Cover(ctx context.Context, id string) (lifecycledomain.CoverSource, error) {
	var source lifecycledomain.CoverSource
	var data, key string
	err := repo.database.Reader.QueryRowContext(ctx, "SELECT s.source_json,s.source_key FROM lifecycle_cover_sources s JOIN lifecycle_entries e ON e.id=s.entry_id WHERE s.entry_id=? AND e.state='trashed' AND e.target_type='comic'", id).Scan(&data, &key)
	if errors.Is(err, sql.ErrNoRows) {
		return source, lifecycledomain.ErrNotFound
	}
	if err != nil {
		return source, err
	}
	if len(data) > lifecycledomain.MaxCoverSourceBytes || json.Unmarshal([]byte(data), &source) != nil {
		return source, lifecycledomain.ErrJournal
	}
	expected, err := lifecycledomain.CoverSourceKey(source)
	if err != nil || source.EntryID != id || key != expected {
		return source, lifecycledomain.ErrJournal
	}
	return source, nil
}
