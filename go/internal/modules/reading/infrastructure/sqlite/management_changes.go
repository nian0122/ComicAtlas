package sqlite

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	readingdomain "comicatlas.local/backend/internal/modules/reading/domain"
	"context"
	"database/sql"
	"errors"
	"time"
)

const managedComicScope = `SELECT c.lifecycle_state,c.lifecycle_state,'' FROM comics c WHERE c.id=?`
const managedChapterScope = `SELECT ch.lifecycle_state,c.lifecycle_state,ch.lifecycle_state FROM chapters ch JOIN comics c ON c.id=ch.comic_id WHERE ch.id=?`
const managedMediaScope = `SELECT m.lifecycle_state,c.lifecycle_state,ch.lifecycle_state FROM media m JOIN chapters ch ON ch.id=m.chapter_id JOIN comics c ON c.id=ch.comic_id WHERE m.id=?`

func managedScope(ctx context.Context, tx *sql.Tx, kind, id string) (readingdomain.ReactionScope, error) {
	var scope readingdomain.ReactionScope
	query := managedComicScope
	if kind == "chapter" {
		query = managedChapterScope
	}
	if kind == "media" {
		query = managedMediaScope
	}
	err := tx.QueryRowContext(ctx, query, id).Scan(&scope.Target, &scope.Comic, &scope.Chapter)
	if errors.Is(err, sql.ErrNoRows) {
		err = librarydomain.ErrNotFound
	}
	return scope, err
}

func (repo *sqliteRepository) SetManagedReactions(ctx context.Context, changes []readingdomain.ReactionChange) ([]readingdomain.Reaction, error) {
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	now := time.Now().UTC().Format("2006-01-02T15:04:05.000000000Z")
	result := make([]readingdomain.Reaction, 0, len(changes))
	for _, change := range changes {
		scope, err := managedScope(ctx, tx, change.TargetType, change.TargetID)
		if err != nil {
			return nil, err
		}
		if !readingdomain.CanChangeManagedReaction(scope) {
			return nil, librarydomain.ErrConflict
		}
		if err = writeReaction(ctx, tx, change, now); err != nil {
			return nil, err
		}
	}
	// 同事务提交前复核整批，包含后项触发器可能影响的前项祖先。
	for _, change := range changes {
		scope, err := managedScope(ctx, tx, change.TargetType, change.TargetID)
		if err != nil {
			return nil, err
		}
		if !readingdomain.CanChangeManagedReaction(scope) {
			return nil, librarydomain.ErrConflict
		}
		saved, err := storedReaction(ctx, tx, change.TargetType, change.TargetID)
		if err != nil {
			return nil, err
		}
		result = append(result, saved)
	}
	if err = tx.Commit(); err != nil {
		return nil, err
	}
	return result, nil
}
