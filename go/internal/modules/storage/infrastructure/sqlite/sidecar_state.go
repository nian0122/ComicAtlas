package sqlite

import (
	storagedomain "comicatlas.local/backend/internal/modules/storage/domain"
	"context"
	"database/sql"
	"errors"
)

const sidecarColumns = "c.id,c.title,s.revision,s.published_revision,s.requested_at,s.published_at,s.last_task_id,COALESCE(t.status,''),COALESCE(t.attempt,0),t.finished_at,t.error_code"
const sidecarJoin = " FROM comic_sidecars s JOIN active_comics c ON c.id=s.comic_id LEFT JOIN tasks t ON t.id=s.last_task_id "

func scanSidecar(row interface{ Scan(...any) error }) (storagedomain.SidecarState, error) {
	var state storagedomain.SidecarState
	err := row.Scan(&state.ComicID, &state.Title, &state.Revision, &state.PublishedRevision, &state.RequestedAt, &state.PublishedAt, &state.TaskID, &state.TaskStatus, &state.Attempt, &state.FinishedAt, &state.ErrorCode)
	if errors.Is(err, sql.ErrNoRows) {
		err = storagedomain.ErrNotFound
	}
	return state, err
}

type sidecarQuery interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}

func sidecarBlocked(ctx context.Context, query sidecarQuery, comic, exclude string) (bool, error) {
	var busy bool
	err := query.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM tasks WHERE comic_id=? AND id<>? AND status IN('queued','running','cancelling'))
 OR EXISTS(SELECT 1 FROM lifecycle_runs WHERE comic_id=? AND completed=0)
 OR EXISTS(SELECT 1 FROM upload_sessions WHERE comic_id=? AND status IN('active','submitted','activated'))
 OR EXISTS(SELECT 1 FROM metadata_refresh_runs WHERE comic_id=? AND applied=1 AND completed=0)
 OR EXISTS(SELECT 1 FROM video_transcode_items i JOIN tasks t ON t.id=i.task_id WHERE t.comic_id=? AND i.status='prepared')
 OR EXISTS(SELECT 1 FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id WHERE ch.comic_id=? AND m.hq_state='releasing')
 OR EXISTS(SELECT 1 FROM comic_recovery_placeholders WHERE comic_id=?)`, comic, exclude, comic, comic, comic, comic, comic, comic).Scan(&busy)
	return busy, err
}

func (repo *repository) SidecarState(ctx context.Context, id string) (storagedomain.SidecarState, error) {
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return storagedomain.SidecarState{}, err
	}
	defer tx.Rollback()
	state, err := scanSidecar(tx.QueryRowContext(ctx, "SELECT "+sidecarColumns+sidecarJoin+"WHERE c.id=?", id))
	if err != nil {
		return state, err
	}
	exclude := ""
	if state.TaskID != nil {
		exclude = *state.TaskID
	}
	state.Blocked, err = sidecarBlocked(ctx, tx, id, exclude)
	if err != nil {
		return state, err
	}
	return state, tx.Commit()
}

func (repo *repository) SidecarCandidates(ctx context.Context, after string) ([]storagedomain.SidecarState, error) {
	rows, err := repo.database.Reader.QueryContext(ctx, "SELECT "+sidecarColumns+sidecarJoin+"WHERE s.revision>s.published_revision AND s.comic_id>? AND NOT EXISTS(SELECT 1 FROM comic_recovery_placeholders p WHERE p.comic_id=s.comic_id) ORDER BY s.comic_id LIMIT 8", after)
	if err != nil {
		return nil, err
	}
	values := []storagedomain.SidecarState{}
	for rows.Next() {
		state, err := scanSidecar(rows)
		if err != nil {
			rows.Close()
			return nil, err
		}
		values = append(values, state)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return nil, err
	}
	for index := range values {
		exclude := ""
		if values[index].TaskID != nil {
			exclude = *values[index].TaskID
		}
		values[index].Blocked, err = sidecarBlocked(ctx, repo.database.Reader, values[index].ComicID, exclude)
		if err != nil {
			return nil, err
		}
	}
	return values, nil
}
