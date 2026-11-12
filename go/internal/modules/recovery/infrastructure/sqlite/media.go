package sqlite

import (
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"context"
	"database/sql"
	"encoding/json"
)

func restoreMedia(ctx context.Context, tx *sql.Tx, plan recoverydomain.RestorePlan) error {
	media, err := tx.PrepareContext(ctx, `INSERT INTO media(id,chapter_id,page_number,file_name,relative_path,media_type,content_type,size,width,height,sha256,video_metadata,hq_state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)`)
	if err != nil {
		return err
	}
	defer media.Close()
	lq, err := tx.PrepareContext(ctx, `INSERT INTO image_lq(media_id,source_sha256,relative_path,sha256,size,width,height,max_long_edge,quality,valid,task_id,updated_at,origin,source_reference,original_state) VALUES(?,?,?,?,?,?,?,?,?,1,?,?,?,?,?)`)
	if err != nil {
		return err
	}
	defer lq.Close()
	for _, item := range plan.Snapshot.Media {
		var video any
		if item.Video != nil {
			data, encodeErr := json.Marshal(item.Video)
			if encodeErr != nil {
				return encodeErr
			}
			video = string(data)
		}
		if _, err = media.ExecContext(ctx, item.ID, item.ChapterID, item.PageNumber, item.FileName, item.StoragePath, item.MediaType, item.ContentType, item.Size, item.Width, item.Height, item.Hash, video, item.HQState); err != nil {
			return err
		}
		if recoverydomain.CurrentLQ(item) {
			file := item.LQFile
			registration, prepareErr := recoverydomain.RestoredLQRegistration(file.LQRecord, plan.TaskID, plan.SnapshotSHA256)
			if prepareErr != nil {
				return prepareErr
			}
			var edge, quality, task, sourceHash, originalState any
			if file.SourceHash != "" {
				sourceHash = file.SourceHash
			}
			if registration.Provenance.OriginalState != "" {
				originalState = registration.Provenance.OriginalState
			}
			if registration.Spec != nil {
				edge, quality = registration.Spec.MaxLongEdge, registration.Spec.Quality
			}
			if registration.TaskID != "" {
				task = registration.TaskID
			}
			if _, err = lq.ExecContext(ctx, item.ID, sourceHash, file.StoragePath, file.Hash, file.Size, file.Width, file.Height, edge, quality, task, plan.RecordedAt, registration.Provenance.Origin, registration.Provenance.ReferenceSHA256, originalState); err != nil {
				return err
			}
		}
	}
	suppressed, err := tx.PrepareContext(ctx, "INSERT INTO comic_suppressed_paths(comic_id,relative_path) VALUES(?,?)")
	if err != nil {
		return err
	}
	defer suppressed.Close()
	for _, name := range plan.Snapshot.SuppressedPaths {
		if _, err = suppressed.ExecContext(ctx, plan.Snapshot.Detail.ID, name); err != nil {
			return err
		}
	}
	if choice := plan.Snapshot.CoverSelection; choice != nil {
		if _, err = tx.ExecContext(ctx, "INSERT INTO comic_cover_selection(comic_id,media_id,version) VALUES(?,?,?) ON CONFLICT(comic_id) DO UPDATE SET media_id=excluded.media_id,version=excluded.version", plan.Snapshot.Detail.ID, choice.MediaID, choice.Version); err != nil {
			return err
		}
	}

	return nil
}
