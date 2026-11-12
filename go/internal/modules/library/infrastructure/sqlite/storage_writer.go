package sqlite

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"encoding/json"
)

func (repo *sqliteRepository) StorageWriter(expected, next librarydomain.Snapshot) persistence.WriteAction {
	guard := repo.SnapshotGuard(expected)
	delta, deltaErr := librarydomain.StorageChanges(expected, next)
	return func(ctx context.Context, tx *sql.Tx) error {
		if deltaErr != nil {
			return deltaErr
		}
		if err := guard(ctx, tx); err != nil {
			return err
		}
		statement, err := tx.PrepareContext(ctx, "UPDATE media SET size=?,sha256=?,width=?,height=?,content_type=?,video_metadata=? WHERE id=?")
		if err != nil {
			return err
		}
		defer statement.Close()
		for _, item := range delta.Changed {
			var video any
			if item.Video != nil {
				data, err := json.Marshal(item.Video)
				if err != nil {
					return err
				}
				video = string(data)
			}
			if _, err = statement.ExecContext(ctx, item.Size, item.Hash, item.Width, item.Height, item.ContentType, video, item.ID); err != nil {
				return err
			}
			if item.LQFile != nil {
				if _, err = tx.ExecContext(ctx, "UPDATE image_lq SET valid=? WHERE media_id=? AND relative_path=? AND sha256=?", item.LQFile.Valid, item.ID, item.LQFile.StoragePath, item.LQFile.Hash); err != nil {
					return err
				}
			}
		}
		for _, item := range delta.Added {
			var video any
			if item.Video != nil {
				data, err := json.Marshal(item.Video)
				if err != nil {
					return err
				}
				video = string(data)
			}
			if _, err = tx.ExecContext(ctx, `INSERT INTO media(id,chapter_id,page_number,file_name,relative_path,media_type,content_type,size,width,height,sha256,video_metadata,hq_state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'available')`, item.ID, item.ChapterID, item.PageNumber, item.FileName, item.StoragePath, item.MediaType, item.ContentType, item.Size, item.Width, item.Height, item.Hash, video); err != nil {
				return err
			}
		}
		for _, chapter := range delta.Chapters {
			if _, err = tx.ExecContext(ctx, "UPDATE chapters SET media_version=? WHERE id=?", chapter.MediaVersion, chapter.ID); err != nil {
				return err
			}
		}
		return nil
	}
}
