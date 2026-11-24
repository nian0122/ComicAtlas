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

	err := db.QueryRowContext(ctx, `SELECT m.id,m.relative_path,m.modified_ns,m.size,CASE WHEN m.hq_state='available' AND m.size=0 AND l.original_state IN ('missing','unbound') THEN 'released' ELSE m.hq_state END,l.relative_path,l.size,l.valid
	 FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id LEFT JOIN image_lq l ON l.media_id=m.id
	 WHERE `+where+` AND m.media_type='image' ORDER BY ch.global_order,m.page_number,m.id LIMIT 1`, id).Scan(&candidate.Source.MediaID, &candidate.Source.Path, &candidate.Source.Modified, &candidate.Source.Size, &candidate.HQState, &candidate.LQPath, &candidate.LQSize, &candidate.LQValid)
	if errors.Is(err, sql.ErrNoRows) {
		err = mediadomain.ErrLQNotFound
	}
	if err != nil {
		return mediadomain.ImageSource{}, err
	}
	return mediadomain.SelectImageSource(candidate)
}
func (repo *repository) ImageSource(ctx context.Context, id string) (mediadomain.ImageSource, error) {
	return imageSource(ctx, repo.database.Reader, id)
}
