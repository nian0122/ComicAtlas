package sqlite

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
)

func loadDirectoryHistory(ctx context.Context, tx *sql.Tx, id string, value *exportdomain.DirectoryInventory) error {
	if err := loadDirectoryRetired(ctx, tx, id, value); err != nil {
		return err
	}
	if err := loadDirectoryTrash(ctx, tx, id, value); err != nil {
		return err
	}
	rows, err := tx.QueryContext(ctx, `SELECT r.target_type,r.target_id,r.reaction,r.updated_at FROM content_reactions r WHERE
 (r.target_type='comic' AND r.target_id=?) OR
 (r.target_type='chapter' AND r.target_id IN(SELECT id FROM chapters WHERE comic_id=?)) OR
 (r.target_type='media' AND r.target_id IN(SELECT m.id FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE ch.comic_id=?))
 ORDER BY r.target_type,r.target_id LIMIT ?`, id, id, id, exportdomain.MaxDirectoryFiles+exportdomain.MaxNodes+2)
	if err != nil {
		return err
	}
	for rows.Next() {
		var item exportdomain.DirectoryReaction
		if err = rows.Scan(&item.TargetType, &item.TargetID, &item.Reaction, &item.UpdatedAt); err != nil {
			rows.Close()
			return err
		}
		value.Reactions = append(value.Reactions, item)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	p := &librarydomain.Progress{ComicID: id}
	err = tx.QueryRowContext(ctx, "SELECT chapter_id,page_number,updated_at,COALESCE(media_id,'') FROM reading_progress WHERE comic_id=?", id).Scan(&p.ChapterID, &p.PageNumber, &p.UpdatedAt, &p.MediaID)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	value.Progress = p
	return nil
}

func loadDirectoryRetired(ctx context.Context, tx *sql.Tx, id string, value *exportdomain.DirectoryInventory) error {
	rows, err := tx.QueryContext(ctx, `SELECT s.id,s.replace_media_id,r.kind,r.path,r.sha256,r.size,s.prior_json FROM upload_retired r JOIN upload_sessions s ON s.task_id=r.task_id JOIN media m ON m.id=s.replace_media_id JOIN chapters ch ON ch.id=m.chapter_id WHERE ch.comic_id=? AND r.status='moved' ORDER BY s.id,r.kind LIMIT ?`, id, exportdomain.MaxDirectoryFiles+1)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var item exportdomain.DirectoryRetired
		var prior *string
		if err = rows.Scan(&item.SessionID, &item.MediaID, &item.Kind, &item.OriginalPath, &item.File.SHA256, &item.File.Size, &prior); err != nil {
			return err
		}
		if prior != nil {
			if len(*prior) > exportdomain.MaxDirectoryRetiredMetadataBytes {
				return exportdomain.ErrValidation
			}
			item.OriginalMetadata = json.RawMessage(*prior)
		}
		item.File.Path, err = exportdomain.DirectoryRetiredPath(item.SessionID, item.Kind)
		if err != nil {
			return err
		}
		value.Retired = append(value.Retired, item)
	}
	return rows.Err()
}

func loadDirectoryTrash(ctx context.Context, tx *sql.Tx, id string, value *exportdomain.DirectoryInventory) error {
	rows, err := tx.QueryContext(ctx, `SELECT id,target_type,target_id,chapter_id,title,created_at,updated_at,task_id,state,plan_json FROM lifecycle_entries WHERE comic_id=? AND state NOT IN('restored','purged','detached') ORDER BY id LIMIT ?`, id, exportdomain.MaxNodes+1)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var item exportdomain.DirectoryTrash
		var raw string
		if err = rows.Scan(&item.ID, &item.TargetType, &item.TargetID, &item.ChapterID, &item.Title, &item.CreatedAt, &item.UpdatedAt, &item.TaskID, &item.State, &raw); err != nil {
			return err
		}
		if len(raw) > exportdomain.MaxSnapshotBytes {
			return exportdomain.ErrValidation
		}
		if json.Unmarshal([]byte(raw), &item.Candidates) != nil || item.Candidates == nil {
			return exportdomain.ErrManifest
		}
		value.Trash = append(value.Trash, item)
	}
	return rows.Err()
}
