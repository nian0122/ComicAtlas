package sqlite

import (
	"context"
	"strings"

	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
)

func (repo *repository) BindImageSource(ctx context.Context, before, after mediadomain.ImageSource) error {
	if before.SHA256 != "" || len(after.SHA256) != 64 || before.MediaID != after.MediaID || before.Path != after.Path || (before.Size != 0 && before.Size != after.Size) {
		return mediadomain.ErrImageChanged
	}
	query := "UPDATE media SET sha256=?,size=? WHERE id=? AND relative_path=? AND sha256='' AND size=? AND hq_state='available'"
	if strings.HasPrefix(before.Path, "lq/") {
		query = "UPDATE image_lq SET sha256=?,size=? WHERE media_id=? AND relative_path=? AND sha256='' AND size=? AND origin='imported'"
	} else if !strings.HasPrefix(before.Path, "hq/") {
		return mediadomain.ErrImageInvalid
	}
	query += " AND EXISTS(SELECT 1 FROM legacy_page_sources s WHERE s.media_id=? AND json_extract(s.source_json,'$.metadataOnly')=1)"
	result, err := repo.database.Writer.ExecContext(ctx, query, after.SHA256, after.Size, before.MediaID, before.Path, before.Size, before.MediaID)
	if err != nil {
		return err
	}
	count, err := result.RowsAffected()
	if err != nil || count != 1 {
		return mediadomain.ErrLQConflict
	}
	return nil
}
