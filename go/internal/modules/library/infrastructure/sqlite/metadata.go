package sqlite

import (
	"context"
	"database/sql"
	"errors"
	"strings"

	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
)

func termTable(kind string) (string, error) {
	switch kind {
	case "categories", "tags":
		return kind, nil
	default:
		return "", librarydomain.ErrValidation
	}
}

func (service *sqliteRepository) Terms(ctx context.Context, kind string, page, size int, keyword string) (librarydomain.TermPage, error) {
	result := librarydomain.TermPage{Items: []librarydomain.Term{}, Page: page, PageSize: size}
	table, err := termTable(kind)
	if err != nil {
		return result, librarydomain.ErrValidation
	}
	tx, err := service.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return result, err
	}
	defer tx.Rollback()
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM "+table+" WHERE instr(name_key,?)>0", strings.ToLower(keyword)).Scan(&result.Total); err != nil {
		return result, err
	}
	rows, err := tx.QueryContext(ctx, "SELECT id,name,version FROM "+table+" WHERE instr(name_key,?)>0 ORDER BY name COLLATE COMIC_NATURAL,id LIMIT ? OFFSET ?", strings.ToLower(keyword), size, (page-1)*size)
	if err != nil {
		return result, err
	}
	for rows.Next() {
		var item librarydomain.Term
		if err = rows.Scan(&item.ID, &item.Name, &item.Version); err != nil {
			rows.Close()
			return result, err
		}
		result.Items = append(result.Items, item)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return result, err
	}
	return result, tx.Commit()
}

func (service *sqliteRepository) SaveTerm(ctx context.Context, kind, id, name string, version int) (librarydomain.Term, error) {
	name = strings.TrimSpace(name)
	table, err := termTable(kind)
	if err != nil {
		return librarydomain.Term{}, librarydomain.ErrValidation
	}
	tx, err := service.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return librarydomain.Term{}, err
	}
	defer tx.Rollback()
	var existing string
	err = tx.QueryRowContext(ctx, "SELECT id FROM "+table+" WHERE name_key=? AND id<>?", strings.ToLower(name), id).Scan(&existing)
	if err == nil {
		return librarydomain.Term{}, librarydomain.ErrConflict
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return librarydomain.Term{}, err
	}
	if id == "" {
		prefix := "tag_"
		if kind == "categories" {
			prefix = "category_"
		}
		id, err = librarydomain.NewID(prefix)
		if err != nil {
			return librarydomain.Term{}, err
		}
		version = 1
		_, err = tx.ExecContext(ctx, "INSERT INTO "+table+"(id,name,name_key,version) VALUES(?,?,?,1)", id, name, strings.ToLower(name))
	} else {
		var current int
		if err = tx.QueryRowContext(ctx, "SELECT version FROM "+table+" WHERE id=?", id).Scan(&current); errors.Is(err, sql.ErrNoRows) {
			return librarydomain.Term{}, librarydomain.ErrNotFound
		} else if err != nil {
			return librarydomain.Term{}, err
		}
		var locked bool
		if err = tx.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM directory_move_term_locks l JOIN directory_move_runs r ON r.task_id=l.task_id WHERE l.kind=? AND l.term_id=? AND r.completed=0)", kind, id).Scan(&locked); err != nil {
			return librarydomain.Term{}, err
		}
		if err = librarydomain.ValidateTermWrite(version, current, locked); err != nil {
			return librarydomain.Term{}, err
		}
		_, err = tx.ExecContext(ctx, "UPDATE "+table+" SET name=?,name_key=?,version=version+1 WHERE id=?", name, strings.ToLower(name), id)
		version++
	}
	if err != nil {
		return librarydomain.Term{}, err
	}
	if err = tx.Commit(); err != nil {
		return librarydomain.Term{}, err
	}
	return librarydomain.Term{ID: id, Name: name, Version: version}, nil
}

func (service *sqliteRepository) Term(ctx context.Context, kind, id string) (librarydomain.Term, error) {
	table, err := termTable(kind)
	if err != nil {
		return librarydomain.Term{}, err
	}
	var result librarydomain.Term
	err = service.database.Reader.QueryRowContext(ctx, "SELECT id,name,version FROM "+table+" WHERE id=?", id).Scan(&result.ID, &result.Name, &result.Version)
	if errors.Is(err, sql.ErrNoRows) {
		err = librarydomain.ErrNotFound
	}
	return result, err
}

func (service *sqliteRepository) DeleteTerm(ctx context.Context, kind, id string, version int) error {
	table, err := termTable(kind)
	if err != nil {
		return librarydomain.ErrValidation
	}
	tx, err := service.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var current int
	if err = tx.QueryRowContext(ctx, "SELECT version FROM "+table+" WHERE id=?", id).Scan(&current); errors.Is(err, sql.ErrNoRows) {
		return librarydomain.ErrNotFound
	} else if err != nil {
		return err
	}
	if current != version {
		return librarydomain.ErrConflict
	}
	var referenced int
	query := "SELECT COUNT(*) FROM comic_tags WHERE tag_id=?"
	if kind == "categories" {
		// 引用约束包含隐藏漫画，词条不能在对象恢复前被删除。
		query = "SELECT COUNT(*) FROM comics WHERE category_id=?"
	}
	if err = tx.QueryRowContext(ctx, query, id).Scan(&referenced); err != nil {
		return err
	}
	if referenced > 0 {
		return librarydomain.ErrConflict
	}
	if _, err = tx.ExecContext(ctx, "DELETE FROM "+table+" WHERE id=?", id); err != nil {
		return err
	}
	return tx.Commit()
}

func (service *sqliteRepository) UpdateMetadataBatch(ctx context.Context, changes []librarydomain.MetadataChange) ([]librarydomain.MetadataVersion, error) {
	tx, err := service.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	result := make([]librarydomain.MetadataVersion, 0, len(changes))
	for _, change := range changes {
		version, writeErr := writeMetadataCore(ctx, tx, change.ID, change.MetadataUpdate)
		if writeErr != nil {
			return nil, writeErr
		}
		if _, err = tx.ExecContext(ctx, "DELETE FROM comic_tags WHERE comic_id=?", change.ID); err != nil {
			return nil, err
		}
		for _, tagID := range change.TagIDs {
			if _, err = tx.ExecContext(ctx, "INSERT INTO comic_tags VALUES(?,?,'manual')", change.ID, tagID); err != nil {
				return nil, err
			}
		}
		result = append(result, version)
	}
	if err = tx.Commit(); err != nil {
		return nil, err
	}
	return result, nil
}

// loadMetadata 在同一个查询快照内装配词条，列表每页批量读取关联，避免逐漫画查询。
func loadMetadata(ctx context.Context, tx *sql.Tx, comics []*librarydomain.Comic) error {
	if len(comics) == 0 {
		return nil
	}
	byID := map[string]*librarydomain.Comic{}
	arguments := make([]any, 0, len(comics))
	placeholders := make([]string, 0, len(comics))
	for _, comic := range comics {
		comic.Tags = []librarydomain.ComicTag{}
		byID[comic.ID] = comic
		arguments = append(arguments, comic.ID)
		placeholders = append(placeholders, "?")
	}
	ids := strings.Join(placeholders, ",")
	rows, err := tx.QueryContext(ctx, `SELECT c.id,t.id,t.name,t.version FROM active_comics c JOIN categories t ON t.id=c.category_id WHERE c.id IN (`+ids+")", arguments...)
	if err != nil {
		return err
	}
	for rows.Next() {
		var comicID string
		var term librarydomain.Term
		if err = rows.Scan(&comicID, &term.ID, &term.Name, &term.Version); err != nil {
			rows.Close()
			return err
		}
		byID[comicID].Category = &term
		byID[comicID].CategoryName = term.Name
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	rows, err = tx.QueryContext(ctx, `SELECT ct.comic_id,t.id,t.name,t.version,ct.source FROM comic_tags ct JOIN tags t ON t.id=ct.tag_id WHERE ct.comic_id IN (`+ids+") ORDER BY t.name COLLATE COMIC_NATURAL,t.id", arguments...)
	if err != nil {
		return err
	}
	for rows.Next() {
		var comicID string
		var tag librarydomain.ComicTag
		if err = rows.Scan(&comicID, &tag.ID, &tag.Name, &tag.Version, &tag.Source); err != nil {
			rows.Close()
			return err
		}
		byID[comicID].Tags = append(byID[comicID].Tags, tag)
	}
	err = rows.Err()
	rows.Close()
	return err
}
