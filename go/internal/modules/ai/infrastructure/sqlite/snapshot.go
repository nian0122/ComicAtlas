package sqlite

import (
	aidomain "comicatlas.local/backend/internal/modules/ai/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"errors"
	"strings"
)

type Repository struct{ database *persistence.Database }

func New(database *persistence.Database) *Repository { return &Repository{database: database} }

func snapshotInTx(ctx context.Context, tx *sql.Tx, comic string, count int) (value aidomain.Snapshot, err error) {
	err = tx.QueryRowContext(ctx, "SELECT id,version,structure_version FROM active_comics WHERE id=?", comic).Scan(&value.ComicID, &value.MetadataVersion, &value.StructureVersion)
	if errors.Is(err, sql.ErrNoRows) {
		return value, aidomain.ErrNotFound
	}
	if err != nil {
		return value, err
	}
	var total int
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE ch.comic_id=? AND m.media_type='image'", comic).Scan(&total); err != nil {
		return value, err
	}
	ordinals, err := aidomain.SampleOrdinals(total, count)
	if err != nil {
		return value, err
	}
	arguments := []any{comic}
	for _, ordinal := range ordinals {
		arguments = append(arguments, ordinal)
	}
	rows, err := tx.QueryContext(ctx, `WITH ordered AS (
 SELECT m.id,ROW_NUMBER() OVER(ORDER BY ch.global_order,m.page_number,m.id) AS ordinal
 FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE ch.comic_id=? AND m.media_type='image'
 ) SELECT o.id,o.ordinal,m.relative_path,m.sha256,m.hq_state,COALESCE(l.relative_path,''),COALESCE(l.sha256,''),COALESCE(l.source_sha256,''),COALESCE(l.valid,0),COALESCE(l.task_id,''),COALESCE(l.origin,''),COALESCE(l.source_reference,''),COALESCE(l.original_state,'')
 FROM ordered o JOIN active_media m ON m.id=o.id LEFT JOIN image_lq l ON l.media_id=o.id WHERE o.ordinal IN (`+strings.TrimRight(strings.Repeat("?,", len(ordinals)), ",")+`) ORDER BY o.ordinal`, arguments...)
	if err != nil {
		return value, err
	}
	for rows.Next() {
		var choice aidomain.SourceChoice
		var provenance mediadomain.LQProvenance
		if err = rows.Scan(&choice.MediaID, &choice.Ordinal, &choice.HQPath, &choice.HQSHA256, &choice.HQState, &choice.LQPath, &choice.LQSHA256, &choice.LQSourceSHA256, &choice.LQValid, &choice.LQTaskID, &provenance.Origin, &provenance.ReferenceSHA256, &provenance.OriginalState); err != nil {
			rows.Close()
			return value, err
		}
		if provenance.Origin != "" && provenance.Origin != "generated" {
			choice.LQProvenance = &provenance
		}
		image, sourceErr := aidomain.SourceFrom(choice)
		if sourceErr != nil {
			rows.Close()
			return value, sourceErr
		}
		value.Images = append(value.Images, image)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return value, err
	}
	if len(value.Images) != len(ordinals) {
		return value, aidomain.ErrSource
	}
	if value.Categories, err = readTerms(ctx, tx, "SELECT id,name FROM categories ORDER BY name COLLATE COMIC_NATURAL,id LIMIT 201"); err != nil {
		return value, err
	}
	if value.Tags, err = readTerms(ctx, tx, "SELECT id,name FROM tags ORDER BY name COLLATE COMIC_NATURAL,id LIMIT 1001"); err != nil {
		return value, err
	}
	if err = aidomain.ValidateSnapshot(value); err != nil {
		return value, err
	}
	return value, nil
}

func readTerms(ctx context.Context, tx *sql.Tx, query string) ([]aidomain.Term, error) {
	value := []aidomain.Term{}
	rows, err := tx.QueryContext(ctx, query)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var term aidomain.Term
		if err = rows.Scan(&term.ID, &term.Name); err != nil {
			return nil, err
		}
		value = append(value, term)
	}
	return value, rows.Err()
}
