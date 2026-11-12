package sqlite

import (
	"context"
	"database/sql"
	"errors"
	"path"
	"strings"

	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
)

func ensureCover(ctx context.Context, tx *sql.Tx, id string) error {
	var exists bool
	if err := tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM active_comics WHERE id=?)", id).Scan(&exists); err != nil {
		return err
	}
	if !exists {
		return mediadomain.ErrLQNotFound
	}
	_, err := tx.ExecContext(ctx, `INSERT INTO comic_cover_selection(comic_id,media_id,version)
 SELECT ?,m.id,1 FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id
 WHERE ch.comic_id=? AND m.media_type='image' ORDER BY ch.global_order,m.page_number,m.id LIMIT 1
 ON CONFLICT(comic_id) DO NOTHING`, id, id)
	return err
}
func readCoverSelection(ctx context.Context, tx *sql.Tx, id string) (mediadomain.CoverSelection, error) {
	result := mediadomain.CoverSelection{ComicID: id}
	err := tx.QueryRowContext(ctx, "SELECT media_id,version FROM comic_cover_selection WHERE comic_id=?", id).Scan(&result.MediaID, &result.Version)
	if errors.Is(err, sql.ErrNoRows) {
		err = mediadomain.ErrLQNotFound
	}
	return result, err
}
func coverSource(ctx context.Context, tx *sql.Tx, id string) (mediadomain.ImageSource, error) {
	if err := ensureCover(ctx, tx, id); err != nil {
		return mediadomain.ImageSource{}, err
	}
	selection, err := readCoverSelection(ctx, tx, id)
	if err != nil {
		return mediadomain.ImageSource{}, err
	}
	var owned bool
	if err = tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE m.id=? AND ch.comic_id=? AND m.media_type='image')`, selection.MediaID, id).Scan(&owned); err != nil {
		return mediadomain.ImageSource{}, err
	}
	if !owned {
		return mediadomain.ImageSource{}, mediadomain.ErrLQNotFound
	}
	return imageSource(ctx, tx, selection.MediaID)
}
func (repo *repository) CoverSource(ctx context.Context, id string) (mediadomain.ImageSource, error) {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return mediadomain.ImageSource{}, err
	}
	defer tx.Rollback()
	source, err := coverSource(ctx, tx, id)
	if err != nil {
		return source, err
	}
	return source, tx.Commit()
}
func (repo *repository) CoverSelection(ctx context.Context, id string) (mediadomain.CoverSelection, error) {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return mediadomain.CoverSelection{}, err
	}
	defer tx.Rollback()
	if err = ensureCover(ctx, tx, id); err != nil {
		return mediadomain.CoverSelection{}, err
	}
	result, err := readCoverSelection(ctx, tx, id)
	if err != nil {
		return result, err
	}
	return result, tx.Commit()
}

func (repo *repository) CoverCandidates(ctx context.Context, comic string) ([]mediadomain.CoverCandidate, error) {
	rows, err := repo.database.Reader.QueryContext(ctx, `SELECT m.id,m.media_type,m.file_name,m.relative_path,m.page_number,ch.global_order
	 FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id
	 WHERE ch.comic_id=? AND m.media_type IN ('image','video')
	 ORDER BY ch.global_order,m.page_number,m.id`, comic)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []mediadomain.CoverCandidate{}
	for rows.Next() {
		var candidate mediadomain.CoverCandidate
		if err = rows.Scan(&candidate.MediaID, &candidate.MediaType, &candidate.FileName, &candidate.RelativePath, &candidate.PageNumber, &candidate.GlobalOrder); err != nil {
			return nil, err
		}
		candidate.Depth = coverDepth(candidate.RelativePath)
		result = append(result, candidate)
	}
	return result, rows.Err()
}

func coverDepth(relativePath string) int {
	parts := strings.Split(path.Dir(relativePath), "/")
	if len(parts) <= 2 {
		return 0
	}
	return len(parts) - 2
}

func (repo *repository) SetCoverSelection(ctx context.Context, comic, media string) error {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var owned bool
	if err = tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE m.id=? AND ch.comic_id=? AND m.media_type='image')`, media, comic).Scan(&owned); err != nil {
		return err
	}
	if !owned {
		return mediadomain.ErrLQValidation
	}
	if _, err = tx.ExecContext(ctx, `INSERT INTO comic_cover_selection(comic_id,media_id,version) VALUES(?,?,1)
	 ON CONFLICT(comic_id) DO UPDATE SET media_id=excluded.media_id,version=comic_cover_selection.version+1`, comic, media); err != nil {
		return err
	}
	return tx.Commit()
}

func (repo *repository) CoverSelectionRead(ctx context.Context, id string) (*mediadomain.CoverSelection, error) {
	result := mediadomain.CoverSelection{ComicID: id}
	err := repo.database.Reader.QueryRowContext(ctx, "SELECT media_id,version FROM comic_cover_selection WHERE comic_id=?", id).Scan(&result.MediaID, &result.Version)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &result, nil
}

func (repo *repository) CoverMediaActive(ctx context.Context, comicID, media string) (bool, error) {
	var active bool
	err := repo.database.Reader.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE m.id=? AND ch.comic_id=? AND m.media_type='image')`, media, comicID).Scan(&active)
	return active, err
}
func (repo *repository) SelectCover(ctx context.Context, id string, version int, media string) (mediadomain.CoverSelection, error) {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return mediadomain.CoverSelection{}, err
	}
	defer tx.Rollback()
	if err = ensureCover(ctx, tx, id); err != nil {
		return mediadomain.CoverSelection{}, err
	}
	var owned bool
	if err = tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE m.id=? AND ch.comic_id=? AND m.media_type='image')`, media, id).Scan(&owned); err != nil {
		return mediadomain.CoverSelection{}, err
	}
	if !owned {
		return mediadomain.CoverSelection{}, mediadomain.ErrLQValidation
	}
	result, err := tx.ExecContext(ctx, "UPDATE comic_cover_selection SET media_id=?,version=version+1 WHERE comic_id=? AND version=?", media, id, version)
	if err != nil {
		return mediadomain.CoverSelection{}, err
	}
	count, err := result.RowsAffected()
	if err != nil {
		return mediadomain.CoverSelection{}, err
	}
	if count != 1 {
		return mediadomain.CoverSelection{}, mediadomain.ErrLQConflict
	}
	value, err := readCoverSelection(ctx, tx, id)
	if err != nil {
		return value, err
	}
	return value, tx.Commit()
}
func (repo *repository) Cover(ctx context.Context, id string) (*mediadomain.CoverRecord, error) {
	var record mediadomain.CoverRecord
	err := repo.database.Reader.QueryRowContext(ctx, "SELECT media_id,source_key,relative_path,sha256,size,width,height FROM comic_covers WHERE comic_id=?", id).Scan(&record.MediaID, &record.SourceKey, &record.Path, &record.SHA256, &record.Size, &record.Width, &record.Height)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return &record, err
}
func (repo *repository) SaveFrameCover(ctx context.Context, id string, record mediadomain.CoverRecord) error {
	if !mediadomain.ValidFrameCover(record, id) {
		return mediadomain.ErrImageOutput
	}
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	_, err = tx.ExecContext(ctx, `INSERT INTO comic_covers(comic_id,media_id,source_key,relative_path,sha256,size,width,height) VALUES(?,?,?,?,?,?,?,?)
 ON CONFLICT(comic_id) DO UPDATE SET media_id=excluded.media_id,source_key=excluded.source_key,relative_path=excluded.relative_path,sha256=excluded.sha256,size=excluded.size,width=excluded.width,height=excluded.height`, id, record.MediaID, record.SourceKey, record.Path, record.SHA256, record.Size, record.Width, record.Height)
	if err != nil {
		return err
	}
	return tx.Commit()
}

func (repo *repository) SaveCover(ctx context.Context, id string, source mediadomain.ImageSource, record mediadomain.CoverRecord) error {
	if !mediadomain.ValidCover(record, source, id) {
		return mediadomain.ErrImageOutput
	}
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	current, err := coverSource(ctx, tx, id)
	if err != nil {
		return err
	}
	if current != source {
		return mediadomain.ErrLQConflict
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO comic_covers(comic_id,media_id,source_key,relative_path,sha256,size,width,height) VALUES(?,?,?,?,?,?,?,?)
 ON CONFLICT(comic_id) DO UPDATE SET media_id=excluded.media_id,source_key=excluded.source_key,relative_path=excluded.relative_path,sha256=excluded.sha256,size=excluded.size,width=excluded.width,height=excluded.height`, id, record.MediaID, record.SourceKey, record.Path, record.SHA256, record.Size, record.Width, record.Height)
	if err != nil {
		return err
	}
	return tx.Commit()
}
