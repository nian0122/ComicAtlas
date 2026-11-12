package sqlite

import (
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
	"database/sql"
	"errors"
)

func trashSnapshot(ctx context.Context, tx *sql.Tx, kind, id string) (lifecycledomain.SelectedTarget, error) {
	value, _, err := trashScopeSnapshot(ctx, tx, kind, id)
	return value, err
}

func trashScopeSnapshot(ctx context.Context, tx *sql.Tx, kind, id string) (lifecycledomain.SelectedTarget, []lifecycledomain.FileCandidate, error) {
	value := lifecycledomain.SelectedTarget{Target: lifecycledomain.Target{Type: kind, ID: id, State: "active"}}
	if kind != "chapter" && kind != "media" {
		return value, nil, lifecycledomain.ErrValidation
	}
	var catalog, reaction string
	err := tx.QueryRowContext(ctx, "SELECT q.comic_id,q.chapter_id,q.title,q.catalog_id,q.reaction FROM ("+trashRootsQuery(kind)+") q WHERE q.id=?", id).Scan(&value.Target.ComicID, &value.Target.ChapterID, &value.Target.Title, &catalog, &reaction)
	if errors.Is(err, sql.ErrNoRows) {
		return value, nil, lifecycledomain.ErrNotFound
	}
	if err != nil {
		return value, nil, err
	}
	snapshot := lifecycledomain.NewTrashSnapshot(value.Target, catalog, reaction)
	where, args := scopeWhere(value.Target)
	rows, err := tx.QueryContext(ctx, `SELECT m.id,m.chapter_id,m.file_name,m.relative_path,m.media_type,m.content_type,m.sha256,m.hq_state,m.size,m.width,m.height,m.video_metadata
 FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE `+where+" ORDER BY m.id LIMIT ?", append(args, lifecycledomain.MaxTrashMembers+1)...)
	if err != nil {
		return value, nil, err
	}
	for rows.Next() {
		var fact lifecycledomain.TrashMediaFact
		if err = rows.Scan(&fact.ID, &fact.ChapterID, &fact.FileName, &fact.Path, &fact.MediaType, &fact.ContentType, &fact.SHA256, &fact.HQState, &fact.Size, &fact.Width, &fact.Height, &fact.VideoMetadata); err == nil {
			err = snapshot.AddMedia(fact)
		}
		if err != nil {
			rows.Close()
			return value, nil, err
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return value, nil, err
	}
	files, err := candidates(ctx, tx, value.Target)
	if err != nil {
		return value, nil, err
	}
	value.Stamp, err = snapshot.Seal(files)
	if err != nil {
		return value, nil, err
	}
	value.FileCount = len(files)
	return value, files, nil
}
