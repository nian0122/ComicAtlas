package sqlite

import (
	"context"
	"database/sql"
	"errors"
	"math"

	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
)

// ReorderMedia 保留媒体 ID 与文件定位，仅更新页码；进度跟随原媒体而非原页码。
func (service *sqliteRepository) ReorderMedia(ctx context.Context, chapterID string, version int, ids []string) (librarydomain.MediaOrder, error) {
	tx, err := service.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return librarydomain.MediaOrder{}, err
	}
	defer tx.Rollback()
	result, err := reorderMedia(ctx, tx, chapterID, version, ids)
	if err != nil {
		return result, err
	}
	return result, tx.Commit()
}

func reorderMedia(ctx context.Context, tx *sql.Tx, chapterID string, version int, ids []string) (librarydomain.MediaOrder, error) {
	var current int
	err := tx.QueryRowContext(ctx, "SELECT media_version FROM active_chapters AS chapters WHERE id=?", chapterID).Scan(&current)
	if errors.Is(err, sql.ErrNoRows) {
		return librarydomain.MediaOrder{}, librarydomain.ErrNotFound
	} else if err != nil {
		return librarydomain.MediaOrder{}, err
	}
	if current != version {
		return librarydomain.MediaOrder{}, librarydomain.ErrConflict
	}
	rows, err := tx.QueryContext(ctx, "SELECT id FROM active_media AS media WHERE chapter_id=? LIMIT 10001", chapterID)
	if err != nil {
		return librarydomain.MediaOrder{}, err
	}
	actual := []string{}
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			rows.Close()
			return librarydomain.MediaOrder{}, err
		}
		actual = append(actual, id)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return librarydomain.MediaOrder{}, err
	}
	if err = librarydomain.ValidateMediaSet(ids, actual); err != nil {
		return librarydomain.MediaOrder{}, err
	}
	var progressMedia string
	err = tx.QueryRowContext(ctx, `SELECT media_id FROM reading_progress WHERE chapter_id=? AND media_id IS NOT NULL`, chapterID).Scan(&progressMedia)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return librarydomain.MediaOrder{}, err
	}
	// 原始表的停放页也占唯一位置；只移动可见媒体，临时位置避开所有记录。
	var maximum int64
	if err = tx.QueryRowContext(ctx, "SELECT COALESCE(MAX(page_number),0) FROM media WHERE chapter_id=?", chapterID).Scan(&maximum); err != nil {
		return librarydomain.MediaOrder{}, err
	}
	if maximum > (math.MaxInt64-int64(len(ids))-1)/2 {
		return librarydomain.MediaOrder{}, librarydomain.ErrConflict
	}
	offset := maximum + int64(len(ids)) + 1
	if _, err = tx.ExecContext(ctx, "UPDATE media SET page_number=page_number+? WHERE chapter_id=? AND id IN(SELECT id FROM active_media WHERE chapter_id=?)", offset, chapterID, chapterID); err != nil {
		return librarydomain.MediaOrder{}, err
	}
	result := librarydomain.MediaOrder{Version: version + 1, Items: []librarydomain.MediaPosition{}}
	statement, err := tx.PrepareContext(ctx, "UPDATE media SET page_number=? WHERE id=? AND chapter_id=?")
	if err != nil {
		return librarydomain.MediaOrder{}, err
	}
	defer statement.Close()
	for index, id := range ids {
		page := index + 1
		if _, err = statement.ExecContext(ctx, page, id, chapterID); err != nil {
			return librarydomain.MediaOrder{}, err
		}
		result.Items = append(result.Items, librarydomain.MediaPosition{ID: id, PageNumber: page})
		if id == progressMedia {
			if _, err = tx.ExecContext(ctx, "UPDATE reading_progress SET page_number=? WHERE media_id=?", page, id); err != nil {
				return librarydomain.MediaOrder{}, err
			}
		}
	}
	if _, err = tx.ExecContext(ctx, "UPDATE chapters SET media_version=media_version+1 WHERE id=?", chapterID); err != nil {
		return librarydomain.MediaOrder{}, err
	}
	return result, nil
}
