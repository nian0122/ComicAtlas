package sqlite

import (
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"errors"
)

// ImportedLQWrite 在调用方的短事务内登记，便于与主媒体及来源记录一起回滚。
// 捕获自己的参数副本，来源关联和碰撞检查仍在实际事务中完成。
func (repo *repository) ImportedLQWrite(record mediadomain.LQRecord, recordedAt string) persistence.WriteAction {
	if record.Spec != nil {
		spec := *record.Spec
		record.Spec = &spec
	}
	if record.Provenance != nil {
		provenance := *record.Provenance
		record.Provenance = &provenance
	}
	validation := mediadomain.ValidateImportedLQRegistration(record, recordedAt)
	return func(ctx context.Context, tx *sql.Tx) error {
		if validation != nil {
			return mediadomain.ErrLQValidation
		}
		var err error
		var hqState string
		if err = tx.QueryRowContext(ctx, "SELECT hq_state FROM active_media WHERE id=? AND media_type='image'", record.MediaID).Scan(&hqState); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return mediadomain.ErrLQNotFound
			}
			return err
		}
		if mediadomain.ValidateImportedLQSource(record, hqState) != nil {
			return mediadomain.ErrLQConflict
		}
		var exists bool
		if err = tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM image_lq WHERE media_id=? OR relative_path=?)", record.MediaID, record.Path).Scan(&exists); err != nil {
			return err
		}
		if exists {
			return mediadomain.ErrLQConflict
		}
		var edge, quality any
		if record.Spec != nil {
			edge, quality = record.Spec.MaxLongEdge, record.Spec.Quality
		}
		var originalState any
		if record.Provenance.OriginalState != "" {
			originalState = record.Provenance.OriginalState
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO image_lq(media_id,relative_path,sha256,size,width,height,max_long_edge,quality,valid,task_id,updated_at,origin,source_reference,original_state)
 VALUES(?,?,?,?,?,?,?,?,1,NULL,?,'imported',?,?)`, record.MediaID, record.Path, record.SHA256, record.Size, record.Width, record.Height, edge, quality, recordedAt, record.Provenance.ReferenceSHA256, originalState)
		if err != nil {
			return err
		}
		return nil
	}
}
