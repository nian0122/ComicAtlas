package sqlite

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"context"
	"database/sql"
	"encoding/json"
)

func loadDirectoryMedia(ctx context.Context, tx *sql.Tx, id string, value *exportdomain.DirectoryInventory) error {
	rows, err := tx.QueryContext(ctx, `SELECT m.id,m.chapter_id,m.page_number,m.file_name,m.relative_path,m.sha256,m.media_type,m.content_type,m.size,m.width,m.height,m.video_metadata,m.hq_state,m.lifecycle_state,
 l.relative_path,l.sha256,COALESCE(l.source_sha256,''),COALESCE(l.task_id,''),l.size,l.width,l.height,l.max_long_edge,l.quality,l.valid,l.origin,l.source_reference,l.original_state
 FROM media m JOIN chapters ch ON ch.id=m.chapter_id LEFT JOIN image_lq l ON l.media_id=m.id WHERE ch.comic_id=? ORDER BY ch.global_order,m.page_number,m.id LIMIT ?`, id, exportdomain.MaxDirectoryFiles+1)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var item exportdomain.DirectoryMedia
		var video, lqPath, lqHash, lqSource, lqTask *string
		var lqSize *int64
		var lqWidth, lqHeight, lqEdge, lqQuality *int
		var lqValid *bool
		var origin, reference, originalState *string
		err = rows.Scan(&item.ID, &item.ChapterID, &item.PageNumber, &item.FileName, &item.StoragePath, &item.Hash, &item.MediaType, &item.ContentType, &item.Size, &item.Width, &item.Height, &video, &item.HQState, &item.State, &lqPath, &lqHash, &lqSource, &lqTask, &lqSize, &lqWidth, &lqHeight, &lqEdge, &lqQuality, &lqValid, &origin, &reference, &originalState)
		if err != nil {
			return err
		}
		item.RelativePath, item.SHA256 = item.StoragePath, item.Hash
		if video != nil {
			if len(*video) > exportdomain.MaxDirectoryVideoBytes {
				return exportdomain.ErrValidation
			}
			item.Video = &mediadomain.Video{}
			if json.Unmarshal([]byte(*video), item.Video) != nil {
				return exportdomain.ErrManifest
			}
		}
		if lqPath != nil {
			item.LQFile = &librarydomain.StoredLQ{LQRecord: mediadomain.LQRecord{MediaID: item.ID, TaskID: *lqTask, Size: *lqSize, Width: *lqWidth, Height: *lqHeight, Valid: *lqValid}, StoragePath: *lqPath, Hash: *lqHash, SourceHash: *lqSource}
			if lqEdge != nil && lqQuality != nil {
				item.LQFile.Spec = &mediadomain.ImageSpec{MaxLongEdge: *lqEdge, Quality: *lqQuality}
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
		value.Media = append(value.Media, item)
	}
	return rows.Err()
}
