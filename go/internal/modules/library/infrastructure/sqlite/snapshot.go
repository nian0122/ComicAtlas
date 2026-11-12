package sqlite

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
)

func readSnapshot(ctx context.Context, tx *sql.Tx, id string) (librarydomain.Snapshot, error) {
	value := librarydomain.Snapshot{Version: 1, Media: []librarydomain.StoredMedia{}}
	var err error
	value.Detail, err = loadDetail(ctx, tx, id)
	if err != nil {
		return value, err
	}
	rows, err := tx.QueryContext(ctx, `SELECT m.id,m.chapter_id,m.page_number,m.file_name,m.relative_path,m.sha256,m.media_type,m.content_type,m.size,m.width,m.height,m.video_metadata,m.hq_state,CASE WHEN m.hq_state='released' THEN 'released' WHEN obs.source_sha256=m.sha256 THEN obs.status ELSE 'unknown' END,
 l.relative_path,l.sha256,COALESCE(l.task_id,''),l.size,l.width,l.height,l.max_long_edge,l.quality,l.valid,l.origin,l.source_reference,l.original_state
	 FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id LEFT JOIN image_lq l ON l.media_id=m.id LEFT JOIN media_observations obs ON obs.media_id=m.id WHERE ch.comic_id=? ORDER BY ch.global_order,m.page_number,m.id LIMIT ?`, id, librarydomain.MaxSnapshotMedia+1)
	if err != nil {
		return value, err
	}
	defer rows.Close()
	for rows.Next() {
		var item librarydomain.StoredMedia
		var video, lqPath, lqHash, lqTask *string
		var size *int64
		var width, height, edge, quality *int
		var valid *bool
		var origin, reference, originalState *string
		err = rows.Scan(&item.ID, &item.ChapterID, &item.PageNumber, &item.FileName, &item.StoragePath, &item.Hash, &item.MediaType, &item.ContentType, &item.Size, &item.Width, &item.Height, &video, &item.HQState, &item.StorageState, &lqPath, &lqHash, &lqTask, &size, &width, &height, &edge, &quality, &valid, &origin, &reference, &originalState)
		if err != nil {
			return value, err
		}
		item.RelativePath, item.SHA256 = item.StoragePath, item.Hash
		if video != nil {
			item.Video = &mediadomain.Video{}
			if err = json.Unmarshal([]byte(*video), item.Video); err != nil {
				return value, err
			}
		}
		if lqPath != nil {
			item.LQFile = &librarydomain.StoredLQ{LQRecord: mediadomain.LQRecord{MediaID: item.ID, TaskID: *lqTask, Size: *size, Width: *width, Height: *height, Valid: *valid}, StoragePath: *lqPath, Hash: *lqHash}
			if edge != nil && quality != nil {
				item.LQFile.Spec = &mediadomain.ImageSpec{MaxLongEdge: *edge, Quality: *quality}
			}
			if origin != nil && *origin != "generated" {
				item.LQFile.Provenance = &mediadomain.LQProvenance{Origin: *origin}
				if originalState != nil {
					item.LQFile.Provenance.OriginalState = *originalState
				}
				if reference != nil {
					item.LQFile.Provenance.ReferenceSHA256 = *reference
				}
			}
		}
		if item.LQFile != nil {
			record := item.LQFile.LQRecord
			item.StorageState = mediadomain.OriginalStorageState(item.Hash, item.StorageState, &record)
		}
		value.Media = append(value.Media, item)
	}
	if err = rows.Err(); err != nil {
		return value, err
	}
	rows.Close()
	// 隐藏记录仍占用路径；刷新不得把尚未移动或外部放回的原件重新登记。
	rows, err = tx.QueryContext(ctx, `SELECT relative_path FROM (
 SELECT m.relative_path FROM media m JOIN chapters ch ON ch.id=m.chapter_id WHERE ch.comic_id=? AND (m.lifecycle_state<>'active' OR ch.lifecycle_state<>'active')
 UNION SELECT relative_path FROM comic_suppressed_paths WHERE comic_id=?
 ) ORDER BY relative_path LIMIT ?`, id, id, librarydomain.MaxSnapshotMedia+1)
	if err != nil {
		return value, err
	}
	defer rows.Close()
	for rows.Next() {
		var name string
		if err = rows.Scan(&name); err != nil {
			return value, err
		}
		value.SuppressedPaths = append(value.SuppressedPaths, name)
	}
	if err = rows.Err(); err != nil {
		return value, err
	}
	selection := mediadomain.CoverSelection{ComicID: id}
	err = tx.QueryRowContext(ctx, "SELECT s.media_id,s.version FROM comic_cover_selection s JOIN active_media m ON m.id=s.media_id JOIN active_chapters ch ON ch.id=m.chapter_id WHERE s.comic_id=? AND ch.comic_id=?", id, id).Scan(&selection.MediaID, &selection.Version)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return value, err
	}
	if err == nil {
		value.CoverSelection = &selection
	}
	return value, nil
}

func loadSnapshot(ctx context.Context, tx *sql.Tx, id string) (librarydomain.Snapshot, error) {
	value, err := readSnapshot(ctx, tx, id)
	if err == nil {
		_, _, err = librarydomain.SnapshotData(value)
	}
	return value, err
}
func (repo *sqliteRepository) Snapshot(ctx context.Context, id string) (librarydomain.Snapshot, error) {
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return librarydomain.Snapshot{}, err
	}
	defer tx.Rollback()
	value, err := loadSnapshot(ctx, tx, id)
	if err != nil {
		return value, err
	}
	return value, tx.Commit()
}

func (repo *sqliteRepository) SnapshotForSidecar(ctx context.Context, id string) (librarydomain.SidecarSnapshot, error) {
	document := librarydomain.SidecarSnapshot{ComicID: id}
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return document, err
	}
	defer tx.Rollback()
	var placeholder bool
	if err = tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM comic_recovery_placeholders WHERE comic_id=?)", id).Scan(&placeholder); err != nil {
		return document, err
	}
	if placeholder {
		return document, librarydomain.ErrConflict
	}
	if err = tx.QueryRowContext(ctx, "SELECT revision FROM comic_sidecars WHERE comic_id=?", id).Scan(&document.Revision); err != nil {
		return document, err
	}
	value, err := readSnapshot(ctx, tx, id)
	if err != nil {
		return document, err
	}
	document.Data, document.SHA256, err = librarydomain.SnapshotData(value)
	if err != nil {
		return document, err
	}
	return document, tx.Commit()
}
func (repo *sqliteRepository) SnapshotGuard(expected librarydomain.Snapshot) persistence.WriteAction {
	_, hash, marshalErr := librarydomain.SnapshotData(expected)
	return func(ctx context.Context, tx *sql.Tx) error {
		if marshalErr != nil {
			return marshalErr
		}
		current, err := loadSnapshot(ctx, tx, expected.Detail.ID)
		if errors.Is(err, librarydomain.ErrNotFound) {
			return librarydomain.ErrConflict
		}
		if err != nil {
			return err
		}
		_, actual, err := librarydomain.SnapshotData(current)
		if err != nil {
			return err
		}
		if hash != actual {
			return librarydomain.ErrConflict
		}
		return nil
	}
}
