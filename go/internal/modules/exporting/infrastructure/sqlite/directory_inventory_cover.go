package sqlite

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"context"
	"database/sql"
	"errors"
)

func loadDirectoryCover(ctx context.Context, tx *sql.Tx, id string, value *exportdomain.DirectoryInventory) error {
	selection := mediadomain.CoverSelection{ComicID: id}
	err := tx.QueryRowContext(ctx, "SELECT media_id,version FROM comic_cover_selection WHERE comic_id=?", id).Scan(&selection.MediaID, &selection.Version)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	if err == nil {
		value.CoverSelection = &selection
	}
	var cover exportdomain.DirectoryCover
	err = tx.QueryRowContext(ctx, "SELECT media_id,relative_path,size,sha256,source_key,width,height FROM comic_covers WHERE comic_id=?", id).Scan(&cover.MediaID, &cover.File.Path, &cover.File.Size, &cover.File.SHA256, &cover.SourceKey, &cover.Width, &cover.Height)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err == nil {
		value.Cover = &cover
	}
	return err
}
