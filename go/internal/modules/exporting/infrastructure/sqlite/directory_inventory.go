package sqlite

import (
	exportports "comicatlas.local/backend/internal/modules/exporting/application/ports"
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
)

var _ exportports.DirectoryInventoryRepository = (*repository)(nil)

func (repo *repository) DirectoryInventories(ctx context.Context, ids []string) ([]exportdomain.DirectoryInventorySnapshot, error) {
	if err := exportdomain.DirectoryIDs(ids); err != nil {
		return nil, err
	}
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	items := make([]exportdomain.DirectoryInventorySnapshot, 0, len(ids))
	bytes := 0
	for _, id := range ids {
		value, err := readDirectoryInventory(ctx, tx, id)
		if err != nil {
			return nil, err
		}
		stamp, size, err := exportdomain.DirectoryInventoryStamp(value)
		if err != nil {
			return nil, err
		}
		bytes += size
		if bytes > exportdomain.MaxDirectoryPlanBytes {
			return nil, exportdomain.ErrValidation
		}
		var revision int64
		if err = tx.QueryRowContext(ctx, "SELECT revision FROM comic_change_versions WHERE comic_id=?", id).Scan(&revision); err != nil {
			return nil, err
		}
		items = append(items, exportdomain.DirectoryInventorySnapshot{Inventory: value, SHA256: stamp, CanonicalBytes: size, MutationVersion: revision})
	}
	if err = tx.Commit(); err != nil {
		return nil, err
	}
	return items, nil
}

func readDirectoryInventory(ctx context.Context, tx *sql.Tx, id string) (exportdomain.DirectoryInventory, error) {

	value := exportdomain.DirectoryInventory{Version: 1, Catalogs: []librarydomain.Catalog{}, Chapters: []exportdomain.DirectoryChapter{}, Media: []exportdomain.DirectoryMedia{}, Retired: []exportdomain.DirectoryRetired{}, Trash: []exportdomain.DirectoryTrash{}, Reactions: []exportdomain.DirectoryReaction{}}
	if err := directoryInventoryBudget(ctx, tx, id); err != nil {
		return value, err
	}
	var activity exportdomain.DirectoryActivity
	err := tx.QueryRowContext(ctx, `SELECT
 (SELECT COUNT(*) FROM tasks WHERE comic_id=:comic AND status IN('queued','running','cancelling')),
 (SELECT COUNT(*) FROM upload_sessions WHERE comic_id=:comic AND status IN('active','submitted','activated')),
 (SELECT COUNT(*) FROM lifecycle_runs WHERE comic_id=:comic AND completed=0),
 (SELECT COUNT(*) FROM metadata_refresh_runs WHERE comic_id=:comic AND applied=1 AND completed=0),
 (SELECT COUNT(*) FROM video_transcode_items i JOIN tasks t ON t.id=i.task_id WHERE t.comic_id=:comic AND i.status='prepared'),
 (SELECT COUNT(*) FROM upload_retired r JOIN upload_sessions s ON s.task_id=r.task_id WHERE s.comic_id=:comic AND r.status='pending')`, sql.Named("comic", id)).Scan(&activity.Tasks, &activity.Uploads, &activity.LifecycleRuns, &activity.RefreshRuns, &activity.PreparedTranscodes, &activity.PendingRetired)
	if err != nil {
		return value, err
	}
	if err = exportdomain.DirectoryInactive(activity); err != nil {
		return value, err
	}
	if err = loadDirectoryComic(ctx, tx, id, &value); err != nil {
		return value, err
	}
	if err = loadDirectoryStructure(ctx, tx, id, &value); err != nil {
		return value, err
	}
	if err = loadDirectoryMedia(ctx, tx, id, &value); err != nil {
		return value, err
	}
	if err = loadDirectoryCover(ctx, tx, id, &value); err != nil {
		return value, err
	}
	if err = loadDirectorySuppressed(ctx, tx, id, &value); err != nil {
		return value, err
	}
	if err = loadDirectoryHistory(ctx, tx, id, &value); err != nil {
		return value, err
	}
	return value, nil
}

func loadDirectoryComic(ctx context.Context, tx *sql.Tx, id string, value *exportdomain.DirectoryInventory) error {
	c := &value.Comic
	var categoryID, categoryName *string
	var categoryVersion *int
	query := `SELECT c.id,c.title,c.created_at,c.author,c.description,c.original_title,c.version,c.category_name,c.structure_version,t.id,t.name,t.version FROM active_comics c LEFT JOIN categories t ON t.id=c.category_id WHERE c.id=?`
	args := []any{id}

	err := tx.QueryRowContext(ctx, query, args...).Scan(&c.ID, &c.Title, &c.CreatedAt, &c.Author, &c.Description, &c.OriginalTitle, &c.Version, &c.CategoryName, &value.StructureVersion, &categoryID, &categoryName, &categoryVersion)
	if errors.Is(err, sql.ErrNoRows) {
		return exportdomain.ErrNotFound
	}
	if err != nil {
		return err
	}
	if categoryID != nil {
		c.Category = &librarydomain.Term{ID: *categoryID, Name: *categoryName, Version: *categoryVersion}
	}
	c.Tags = []librarydomain.ComicTag{}
	rows, err := tx.QueryContext(ctx, `SELECT t.id,t.name,t.version,ct.source FROM comic_tags ct JOIN tags t ON t.id=ct.tag_id WHERE ct.comic_id=? ORDER BY t.id LIMIT 101`, id)
	if err != nil {
		return err
	}
	for rows.Next() {
		var tag librarydomain.ComicTag
		if err = rows.Scan(&tag.ID, &tag.Name, &tag.Version, &tag.Source); err != nil {
			rows.Close()
			return err
		}
		c.Tags = append(c.Tags, tag)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	var raw string
	err = tx.QueryRowContext(ctx, "SELECT comicinfo_json FROM comic_source_metadata WHERE comic_id=?", id).Scan(&raw)
	if errors.Is(err, sql.ErrNoRows) {
		return nil
	}
	if err != nil {
		return err
	}
	if len(raw) > exportdomain.MaxDirectoryComicInfoBytes {
		return exportdomain.ErrValidation
	}
	value.ComicInfo = &librarydomain.ComicInfo{}
	if err = json.Unmarshal([]byte(raw), value.ComicInfo); err != nil {
		return exportdomain.ErrManifest
	}
	return nil
}
