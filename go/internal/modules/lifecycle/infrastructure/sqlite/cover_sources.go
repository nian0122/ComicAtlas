package sqlite

import (
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"
)

func captureCover(ctx context.Context, tx *sql.Tx, target lifecycledomain.Target, entry string, plan []lifecycledomain.FileCandidate) error {
	if target.Type != "comic" {
		return nil
	}
	coverPath := "thumbs/" + target.ID + "/cover.webp"
	var mediaID, sha string
	var size int64
	err := tx.QueryRowContext(ctx, "SELECT media_id,sha256,size FROM comic_covers WHERE comic_id=?", target.ID).Scan(&mediaID, &sha, &size)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	for _, file := range plan {
		if file.Path != coverPath {
			continue
		}
		source := lifecycledomain.CoverSource{EntryID: entry, MediaID: mediaID, Candidates: []lifecycledomain.FileCandidate{file}}
		if lifecycledomain.ValidateCoverSource(source) == nil {
			return storeCoverSource(ctx, tx, entry, source)
		}
		break
	}
	return nil
}

func storeCoverSource(ctx context.Context, tx *sql.Tx, entry string, source lifecycledomain.CoverSource) error {
	key, err := lifecycledomain.CoverSourceKey(source)
	if err != nil {
		return err
	}
	data, err := json.Marshal(source)
	if err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, "INSERT INTO lifecycle_cover_sources(entry_id,source_json,source_key) VALUES(?,?,?)", entry, string(data), key)
	return err
}

func (repo *repository) ActiveComicCovers(ctx context.Context, comics []string) (map[string]bool, error) {
	result := map[string]bool{}
	if len(comics) == 0 {
		return result, nil
	}
	args := make([]any, len(comics))
	for index, id := range comics {
		args[index] = id
	}
	rows, err := repo.database.Reader.QueryContext(ctx, `SELECT c.comic_id FROM comic_covers c JOIN active_comics m ON m.id=c.comic_id
 WHERE c.comic_id IN(`+strings.TrimSuffix(strings.Repeat("?,", len(comics)), ",")+`)`, args...)
	if err != nil {
		return result, err
	}
	defer rows.Close()
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			return result, err
		}
		result[id] = true
	}
	return result, rows.Err()
}

func (repo *repository) CoverEntries(ctx context.Context, ids []string) (map[string]bool, error) {
	result := map[string]bool{}
	if len(ids) == 0 {
		return result, nil
	}
	args := make([]any, len(ids))
	for index, id := range ids {
		args[index] = id
	}
	rows, err := repo.database.Reader.QueryContext(ctx, "SELECT s.entry_id FROM lifecycle_cover_sources s JOIN lifecycle_entries e ON e.id=s.entry_id WHERE e.state='trashed' AND e.target_type='comic' AND s.entry_id IN("+strings.TrimSuffix(strings.Repeat("?,", len(ids)), ",")+")", args...)
	if err != nil {
		return result, err
	}
	defer rows.Close()
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			return result, err
		}
		result[id] = true
	}
	return result, rows.Err()
}
