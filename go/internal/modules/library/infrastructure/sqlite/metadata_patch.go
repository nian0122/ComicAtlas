package sqlite

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"
)

// 两条资料入口共用版本、引用和主记录写入；批量补丁只增删指定标签，保留其他标签来源。
func writeMetadataCore(ctx context.Context, tx *sql.Tx, id string, update librarydomain.MetadataUpdate) (librarydomain.MetadataVersion, error) {
	var current int
	if err := tx.QueryRowContext(ctx, "SELECT version FROM active_comics AS comics WHERE id=?", id).Scan(&current); errors.Is(err, sql.ErrNoRows) {
		return librarydomain.MetadataVersion{}, librarydomain.ErrNotFound
	} else if err != nil {
		return librarydomain.MetadataVersion{}, err
	}
	if err := librarydomain.ValidateMetadataVersion(current, update.Version); err != nil {
		return librarydomain.MetadataVersion{}, err
	}
	categoryName, err := metadataReferences(ctx, tx, update)
	if err != nil {
		return librarydomain.MetadataVersion{}, err
	}
	_, err = tx.ExecContext(ctx, `UPDATE comics SET title=?,original_title=?,author=?,description=?,category_id=?,category_name=?,version=version+1 WHERE id=?`, strings.TrimSpace(update.Title), librarydomain.CleanOriginalTitle(update.OriginalTitle), strings.TrimSpace(update.Author), update.Description, update.CategoryID, categoryName, id)
	return librarydomain.MetadataVersion{ID: id, Version: current + 1}, err
}

func metadataReferences(ctx context.Context, tx *sql.Tx, update librarydomain.MetadataUpdate) (string, error) {
	data, err := json.Marshal(update.TagIDs)
	if err != nil {
		return "", err
	}
	var count int
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM tags WHERE id IN(SELECT value FROM json_each(?) WHERE type='text')", string(data)).Scan(&count); err != nil {
		return "", err
	}
	if err = librarydomain.ValidateMetadataReferences(count, len(update.TagIDs)); err != nil {
		return "", err
	}
	name := ""
	if update.CategoryID != nil {
		if err = tx.QueryRowContext(ctx, "SELECT name FROM categories WHERE id=?", *update.CategoryID).Scan(&name); errors.Is(err, sql.ErrNoRows) {
			return "", librarydomain.ErrValidation
		} else if err != nil {
			return "", err
		}
	}
	return name, nil
}
func (repo *sqliteRepository) CheckPatchReferences(ctx context.Context, patch librarydomain.MetadataPatch) error {
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = metadataReferences(ctx, tx, librarydomain.MetadataUpdate{CategoryID: patch.CategoryID, TagIDs: patch.AddTagIDs}); err != nil {
		return err
	}
	return tx.Commit()
}
func (repo *sqliteRepository) MetadataPatchWriter(id string, version int, patch librarydomain.MetadataPatch) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		if err := librarydomain.ValidateMetadataPatch(patch); err != nil {
			return err
		}
		current := librarydomain.Comic{}
		err := tx.QueryRowContext(ctx, "SELECT id,title,original_title,author,description,version FROM active_comics WHERE id=?", id).Scan(&current.ID, &current.Title, &current.OriginalTitle, &current.Author, &current.Description, &current.Version)
		if errors.Is(err, sql.ErrNoRows) {
			return librarydomain.ErrNotFound
		}
		if err != nil {
			return err
		}
		if err := librarydomain.ValidateMetadataVersion(current.Version, version); err != nil {
			return err
		}
		if err = loadMetadata(ctx, tx, []*librarydomain.Comic{&current}); err != nil {
			return err
		}
		update, err := librarydomain.ApplyMetadataPatch(current, patch)
		if err != nil {
			return err
		}
		if _, err = writeMetadataCore(ctx, tx, id, update); err != nil {
			return err
		}
		for _, tag := range patch.RemoveTagIDs {
			if _, err = tx.ExecContext(ctx, "DELETE FROM comic_tags WHERE comic_id=? AND tag_id=?", id, tag); err != nil {
				return err
			}
		}
		for _, tag := range patch.AddTagIDs {
			if _, err = tx.ExecContext(ctx, "INSERT INTO comic_tags VALUES(?,?,'manual') ON CONFLICT(comic_id,tag_id) DO NOTHING", id, tag); err != nil {
				return err
			}
		}
		return nil
	}
}
