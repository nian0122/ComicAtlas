package sqlite

import (
	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"context"
	"database/sql"
	"errors"
)

var _ mediaports.CoverRepository = (*repository)(nil)

type imageQuery interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}

func imageSource(ctx context.Context, db imageQuery, id string) (mediadomain.ImageSource, error) {
	where := "m.id=?"
	var candidate mediadomain.ImageCandidate
	var provenance mediadomain.LQProvenance
	err := db.QueryRowContext(ctx, `SELECT m.id,m.relative_path,m.sha256,m.sha256,m.size,m.hq_state,l.relative_path,l.sha256,l.source_sha256,l.size,l.valid,COALESCE(l.task_id,''),COALESCE(l.origin,''),COALESCE(l.source_reference,''),COALESCE(l.original_state,''),EXISTS(SELECT 1 FROM legacy_page_sources s WHERE s.media_id=m.id AND json_extract(s.source_json,'$.metadataOnly')=1)
	 FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id LEFT JOIN image_lq l ON l.media_id=m.id
	 WHERE `+where+` AND m.media_type='image' ORDER BY ch.global_order,m.page_number,m.id LIMIT 1`, id).Scan(&candidate.Source.MediaID, &candidate.Source.Path, &candidate.Source.SHA256, &candidate.Source.OriginalSHA256, &candidate.Source.Size, &candidate.HQState, &candidate.LQPath, &candidate.LQSHA256, &candidate.LQSourceSHA256, &candidate.LQSize, &candidate.LQValid, &candidate.LQTaskID, &provenance.Origin, &provenance.ReferenceSHA256, &provenance.OriginalState, &candidate.MetadataOnly)
	if errors.Is(err, sql.ErrNoRows) {
		err = mediadomain.ErrLQNotFound
	}
	if err != nil {
		return mediadomain.ImageSource{}, err
	}
	if provenance.Origin != "" && provenance.Origin != "generated" {
		candidate.LQProvenance = &provenance
	}
	return mediadomain.SelectImageSource(candidate)
}
func (repo *repository) ImageSource(ctx context.Context, id string) (mediadomain.ImageSource, error) {
	return imageSource(ctx, repo.database.Reader, id)
}
