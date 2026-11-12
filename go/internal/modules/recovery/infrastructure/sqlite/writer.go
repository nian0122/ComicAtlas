package sqlite

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"encoding/json"
	"errors"

	"github.com/mattn/go-sqlite3"
)

// RestoreWriter 只提供事务提交动作；文件核对与任务结果由上层在提交前后组合。
func (repo *sqliteRepository) RestoreWriter(comic string, sidecar recoverydomain.Sidecar, task, recordedAt string) persistence.WriteAction {
	plan, prepareErr := recoverydomain.PrepareRestore(comic, sidecar, task, recordedAt)
	guard := repo.GuardAbsent(comic)
	return func(ctx context.Context, tx *sql.Tx) (err error) {
		defer func() {
			var constraint sqlite3.Error
			if errors.As(err, &constraint) && constraint.Code == sqlite3.ErrConstraint {
				err = errors.Join(recoverydomain.ErrConflict, err)
			}
		}()
		if prepareErr != nil {
			return prepareErr
		}
		if err = guard(ctx, tx); err != nil {
			return err
		}
		var running bool
		if err = tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM tasks WHERE id=? AND status='running')", plan.TaskID).Scan(&running); err != nil {
			return err
		}
		if !running {
			return recoverydomain.ErrConflict
		}
		if err = restoreMetadata(ctx, tx, plan.Snapshot.Detail, true); err != nil {
			return err
		}
		if err = restoreStructure(ctx, tx, plan.Snapshot); err != nil {
			return err
		}
		if err = restoreMedia(ctx, tx, plan); err != nil {
			return err
		}
		return err
	}
}

func restoreMetadata(ctx context.Context, tx *sql.Tx, book librarydomain.Detail, create bool) error {
	category, tags, err := restoreTerms(ctx, tx, book)
	if err != nil {
		return err
	}
	if create {
		_, err = tx.ExecContext(ctx, `INSERT INTO comics(id,title,created_at,author,description,category_id,category_name,version,structure_version,original_title) VALUES(?,?,?,?,?,?,?,?,?,?)`, book.ID, book.Title, book.CreatedAt, book.Author, book.Description, category, book.CategoryName, book.Version, book.StructureVersion, book.OriginalTitle)
	} else {
		_, err = tx.ExecContext(ctx, `UPDATE comics SET title=?,created_at=?,author=?,description=?,category_id=?,category_name=?,version=?,structure_version=?,original_title=? WHERE id=?`, book.Title, book.CreatedAt, book.Author, book.Description, category, book.CategoryName, book.Version, book.StructureVersion, book.OriginalTitle, book.ID)
		if err == nil {
			_, err = tx.ExecContext(ctx, "DELETE FROM comic_tags WHERE comic_id=?", book.ID)
		}
		if err == nil {
			_, err = tx.ExecContext(ctx, "DELETE FROM comic_source_metadata WHERE comic_id=?", book.ID)
		}
	}
	if err != nil {
		return err
	}
	if book.ComicInfo != nil {
		data, encodeErr := json.Marshal(book.ComicInfo)
		if encodeErr != nil {
			return encodeErr
		}
		if _, err = tx.ExecContext(ctx, "INSERT INTO comic_source_metadata(comic_id,comicinfo_json) VALUES(?,?)", book.ID, string(data)); err != nil {
			return err
		}
	}
	for i, tag := range book.Tags {
		if _, err = tx.ExecContext(ctx, "INSERT INTO comic_tags(comic_id,tag_id,source) VALUES(?,?,?)", book.ID, tags[i], tag.Source); err != nil {
			return err
		}
	}
	return nil
}

func restoreTerms(ctx context.Context, tx *sql.Tx, detail librarydomain.Detail) (category any, tags []string, err error) {
	if detail.Category != nil {
		category, err = restoreTerm(ctx, tx, "categories", *detail.Category)
		if err != nil {
			return nil, nil, err
		}
	}
	tags = make([]string, 0, len(detail.Tags))
	for _, tag := range detail.Tags {
		id, err := restoreTerm(ctx, tx, "tags", tag.Term)
		if err != nil {
			return nil, nil, err
		}
		tags = append(tags, id)
	}
	return category, tags, nil
}

func restoreTerm(ctx context.Context, tx *sql.Tx, table string, source librarydomain.Term) (string, error) {
	if table != "categories" && table != "tags" {
		return "", recoverydomain.ErrValidation
	}
	key := recoverydomain.TermNameKey(source.Name)
	var id string
	err := tx.QueryRowContext(ctx, "SELECT id FROM "+table+" WHERE name_key=?", key).Scan(&id)
	if err == nil {
		resolved, _, resolveErr := recoverydomain.RestoreTermIdentity(source.ID, id, false, "")
		return resolved, resolveErr
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return "", err
	}
	var used bool
	if err = tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM "+table+" WHERE id=?)", source.ID).Scan(&used); err != nil {
		return "", err
	}
	replacement := ""
	if used {
		replacement, err = librarydomain.NewID("recovered_")
		if err != nil {
			return "", err
		}
	}
	id, create, err := recoverydomain.RestoreTermIdentity(source.ID, "", used, replacement)
	if err != nil || !create {
		return id, err
	}
	_, err = tx.ExecContext(ctx, "INSERT INTO "+table+"(id,name,name_key,version) VALUES(?,?,?,?)", id, source.Name, key, source.Version)
	return id, err
}
