package sqlite

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	storageports "comicatlas.local/backend/internal/modules/storage/application/ports"
	storagedomain "comicatlas.local/backend/internal/modules/storage/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strings"
)

type repository struct{ database *persistence.Database }

func New(database *persistence.Database) *repository { return &repository{database: database} }

var _ storageports.Repository = (*repository)(nil)

// metricsColumns 与 comic_storage_metrics 的列顺序一致，供合并读取与批量写入复用。
const metricsColumns = "comic_id,chapters,images,videos,hq_files,hq_bytes,lq_files,lq_bytes,current_lq,available_images,released_images,releasing_images,missing_source_sha256_images,missing_lq,available_bytes,cover_files,cover_bytes"

// metricsAggregate 对一段漫画范围聚合占用；只用于脏漫画或批量重算，读路径不再全库聚合。
func metricsAggregate(where string) string {
	return `SELECT c.id AS comic_id,COUNT(DISTINCT ch.id) AS chapters,
	 COALESCE(SUM(m.media_type='image'),0) AS images,COALESCE(SUM(m.media_type='video'),0) AS videos,
	 COALESCE(SUM(m.hq_state<>'released'),0) AS hq_files,COALESCE(SUM(CASE WHEN m.hq_state<>'released' THEN m.size ELSE 0 END),0) AS hq_bytes,
	 COUNT(l.media_id) AS lq_files,COALESCE(SUM(l.size),0) AS lq_bytes,COALESCE(SUM(l.valid=1 AND l.source_matches=1),0) AS current_lq,
	 COALESCE(SUM(m.media_type='image' AND m.hq_state='available'),0) AS available_images,
	 COALESCE(SUM(m.media_type='image' AND m.hq_state='released'),0) AS released_images,
	 COALESCE(SUM(m.media_type='image' AND m.hq_state='releasing'),0) AS releasing_images,
	 COALESCE(SUM(m.media_type='image' AND m.hq_state='available' AND m.sha256=''),0) AS missing_source_sha256_images,
	 COALESCE(SUM(m.media_type='image' AND m.hq_state='available' AND (l.media_id IS NULL OR l.valid<>1 OR l.source_matches<>1)),0) AS missing_lq,
	 COALESCE(SUM(CASE WHEN m.media_type='image' AND m.hq_state='available' THEN m.size ELSE 0 END),0) AS available_bytes,
	 (SELECT COUNT(*) FROM comic_covers cv WHERE cv.comic_id=c.id) AS cover_files,
	 (SELECT COALESCE(SUM(cv.size),0) FROM comic_covers cv WHERE cv.comic_id=c.id) AS cover_bytes
	 FROM comics c
	 LEFT JOIN chapters ch ON ch.comic_id=c.id AND ch.lifecycle_state='active'
	 LEFT JOIN media m ON m.chapter_id=ch.id AND m.lifecycle_state='active'
	 LEFT JOIN image_lq_state l ON l.media_id=m.id
	 WHERE ` + where + `
	 GROUP BY c.id`
}

// metricsCombined 用物化结果回答干净漫画，只对脏漫画即时聚合，读事务不写数据库。
func metricsCombined() string {
	return `dirty AS (SELECT comic_id FROM storage_metrics_dirty),
	 fresh AS (` + metricsAggregate("c.id IN (SELECT comic_id FROM dirty)") + `),
	 combined AS (
	  SELECT ` + metricsColumns + ` FROM comic_storage_metrics WHERE comic_id NOT IN (SELECT comic_id FROM dirty)
	  UNION ALL
	  SELECT ` + metricsColumns + ` FROM fresh)`
}

// Inventory 读取物化占用；漫画与全局结果来自 comic_storage_metrics，章节按单章聚合。
func (repo *repository) Inventory(ctx context.Context, kind, id string) (storagedomain.Inventory, error) {
	switch kind {
	case "global":
		return repo.globalInventory(ctx)
	case "comic":
		return repo.comicInventory(ctx, id)
	case "chapter":
		return repo.chapterInventory(ctx, id)
	}
	return storagedomain.Inventory{}, storagedomain.ErrValidation
}

func (repo *repository) globalInventory(ctx context.Context) (storagedomain.Inventory, error) {
	result := storagedomain.Inventory{ScopeType: "global"}
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return result, err
	}
	defer tx.Rollback()
	var chapters, images, videos, hqFiles, hqBytes, lqFiles, lqBytes, currentLQ, coverFiles, coverBytes int64
	var availableImages, availableBytes, missingLQ, missingSourceSha256Images, releasedImages, releasingImages int64
	if err = tx.QueryRowContext(ctx, `WITH `+metricsCombined()+`
	 SELECT (SELECT COUNT(*) FROM active_comics),
	 COALESCE(SUM(m.chapters),0),COALESCE(SUM(m.images),0),COALESCE(SUM(m.videos),0),
	 COALESCE(SUM(m.hq_files),0),COALESCE(SUM(m.hq_bytes),0),COALESCE(SUM(m.lq_files),0),COALESCE(SUM(m.lq_bytes),0),
	 COALESCE(SUM(m.current_lq),0),COALESCE(SUM(m.available_images),0),COALESCE(SUM(m.available_bytes),0),
	 COALESCE(SUM(m.missing_lq),0),COALESCE(SUM(m.missing_source_sha256_images),0),COALESCE(SUM(m.released_images),0),COALESCE(SUM(m.releasing_images),0),
	 COALESCE(SUM(m.cover_files),0),COALESCE(SUM(m.cover_bytes),0)
	 FROM active_comics c LEFT JOIN combined m ON m.comic_id=c.id`).Scan(
		&result.Comics, &chapters, &images, &videos, &hqFiles, &hqBytes, &lqFiles, &lqBytes, &currentLQ,
		&availableImages, &availableBytes, &missingLQ, &missingSourceSha256Images, &releasedImages, &releasingImages,
		&coverFiles, &coverBytes); err != nil {
		return result, err
	}
	result.Chapters, result.Images, result.Videos = chapters, images, videos
	result.HQ, result.LQ = storagedomain.Usage{Files: hqFiles, Bytes: hqBytes}, storagedomain.Usage{Files: lqFiles, Bytes: lqBytes}
	result.CurrentLQImages, result.Covers = currentLQ, storagedomain.Usage{Files: coverFiles, Bytes: coverBytes}
	result.AvailableImages, result.AvailableBytes = availableImages, availableBytes
	result.MissingLQ, result.MissingSourceSHA256Images = missingLQ, missingSourceSha256Images
	result.ReleasedImages, result.ReleasingImages = releasedImages, releasingImages
	return result, tx.Commit()
}

func (repo *repository) comicInventory(ctx context.Context, id string) (storagedomain.Inventory, error) {
	result := storagedomain.Inventory{ScopeType: "comic", ScopeID: &id, Comics: 1}
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return result, err
	}
	defer tx.Rollback()
	if err = tx.QueryRowContext(ctx, `WITH `+metricsCombined()+`
	 SELECT m.chapters,m.images,m.videos,m.hq_files,m.hq_bytes,m.lq_files,m.lq_bytes,m.current_lq,
	 m.available_images,m.available_bytes,m.missing_lq,m.missing_source_sha256_images,m.released_images,m.releasing_images,m.cover_files,m.cover_bytes
	 FROM active_comics c JOIN combined m ON m.comic_id=c.id WHERE c.id=?`, id).Scan(
		&result.Chapters, &result.Images, &result.Videos, &result.HQ.Files, &result.HQ.Bytes, &result.LQ.Files, &result.LQ.Bytes, &result.CurrentLQImages,
		&result.AvailableImages, &result.AvailableBytes, &result.MissingLQ, &result.MissingSourceSHA256Images, &result.ReleasedImages, &result.ReleasingImages,
		&result.Covers.Files, &result.Covers.Bytes); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return storagedomain.Inventory{}, storagedomain.ErrNotFound
		}
		return result, err
	}
	return result, tx.Commit()
}

// chapterInventory 只聚合单章媒体；章节范围受漫画限制，不参与全库扫描。
func (repo *repository) chapterInventory(ctx context.Context, id string) (storagedomain.Inventory, error) {
	result := storagedomain.Inventory{ScopeType: "chapter", ScopeID: &id, Comics: 1, Chapters: 1}
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return result, err
	}
	defer tx.Rollback()
	var exists int
	if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM active_chapters AS chapters WHERE id=?", id).Scan(&exists); err != nil {
		return result, err
	}
	if exists == 0 {
		return storagedomain.Inventory{}, storagedomain.ErrNotFound
	}
	if err = tx.QueryRowContext(ctx, `SELECT COALESCE(SUM(m.hq_state<>'released'),0),COALESCE(SUM(CASE WHEN m.hq_state<>'released' THEN m.size ELSE 0 END),0),COALESCE(SUM(m.media_type='image'),0),COALESCE(SUM(m.media_type='video'),0),
	 COUNT(l.media_id),COALESCE(SUM(l.size),0),COALESCE(SUM(l.valid=1 AND l.source_matches=1),0),
	 COALESCE(SUM(m.media_type='image' AND m.hq_state='available'),0),
	 COALESCE(SUM(CASE WHEN m.media_type='image' AND m.hq_state='available' THEN m.size ELSE 0 END),0),
	 COALESCE(SUM(m.media_type='image' AND m.hq_state='available' AND (l.media_id IS NULL OR l.valid<>1 OR l.source_matches<>1)),0),
	 COALESCE(SUM(m.media_type='image' AND m.hq_state='available' AND m.sha256=''),0),
	 COALESCE(SUM(m.media_type='image' AND m.hq_state='released'),0),
	 COALESCE(SUM(m.media_type='image' AND m.hq_state='releasing'),0)
	 FROM active_media m JOIN active_chapters ch ON ch.id=m.chapter_id LEFT JOIN image_lq_state l ON l.media_id=m.id WHERE ch.id=?`, id).Scan(
		&result.HQ.Files, &result.HQ.Bytes, &result.Images, &result.Videos, &result.LQ.Files, &result.LQ.Bytes, &result.CurrentLQImages,
		&result.AvailableImages, &result.AvailableBytes, &result.MissingLQ, &result.MissingSourceSHA256Images, &result.ReleasedImages, &result.ReleasingImages); err != nil {
		return result, err
	}
	return result, tx.Commit()
}

// RefreshMetrics 在单个短写事务内重算一批脏漫画并撤销脏标记；由后台维护循环调用。
func (repo *repository) RefreshMetrics(ctx context.Context, limit int) (int, error) {
	if limit < 1 {
		limit = 1
	}
	tx, err := repo.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	rows, err := tx.QueryContext(ctx, "SELECT comic_id FROM storage_metrics_dirty ORDER BY comic_id LIMIT ?", limit)
	if err != nil {
		return 0, err
	}
	var ids []any
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			rows.Close()
			return 0, err
		}
		ids = append(ids, id)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return 0, err
	}
	if len(ids) == 0 {
		return 0, tx.Commit()
	}
	marks := strings.TrimSuffix(strings.Repeat("?,", len(ids)), ",")
	if _, err = tx.ExecContext(ctx, "DELETE FROM comic_storage_metrics WHERE comic_id IN ("+marks+")", ids...); err != nil {
		return 0, err
	}
	if _, err = tx.ExecContext(ctx, "INSERT INTO comic_storage_metrics("+metricsColumns+") SELECT "+metricsColumns+" FROM ("+
		metricsAggregate("c.id IN ("+marks+")")+")", ids...); err != nil {
		return 0, err
	}
	if _, err = tx.ExecContext(ctx, "DELETE FROM storage_metrics_dirty WHERE comic_id IN ("+marks+")", ids...); err != nil {
		return 0, err
	}
	return len(ids), tx.Commit()
}

// MetricsDirty 只做一次存在性检查，供自动触发在无变化时跳过空跑。
func (repo *repository) MetricsDirty(ctx context.Context) (bool, error) {
	var exists int
	err := repo.database.Reader.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM storage_metrics_dirty)").Scan(&exists)
	return exists == 1, err
}

// GlobalDetails 读取不参与物化的扫描观测与最近刷新任务。
func (repo *repository) GlobalDetails(ctx context.Context) (*storagedomain.Observation, *storagedomain.Refresh, error) {
	tx, err := repo.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return nil, nil, err
	}
	defer tx.Rollback()
	var observation *storagedomain.Observation
	var data string
	value := storagedomain.Observation{}
	err = tx.QueryRowContext(ctx, "SELECT task_id,started_at,finished_at,result_json FROM storage_observations WHERE singleton=1").Scan(&value.TaskID, &value.StartedAt, &value.FinishedAt, &data)
	if err == nil {
		if err = json.Unmarshal([]byte(data), &value.Usage); err != nil {
			return nil, nil, err
		}
		observation = &value
	} else if !errors.Is(err, sql.ErrNoRows) {
		return nil, nil, err
	}
	var refresh *storagedomain.Refresh
	var task jobsdomain.Task
	err = tx.QueryRowContext(ctx, "SELECT id,status,stage,completed_count,error_code FROM tasks WHERE type='storage_refresh' ORDER BY created_at DESC,id DESC LIMIT 1").Scan(&task.ID, &task.Status, &task.Stage, &task.CompletedCount, &task.ErrorCode)
	if err == nil {
		task = jobsdomain.WithDiagnostics(task)
		refresh = &storagedomain.Refresh{TaskID: task.ID, Status: task.Status, CompletedCount: task.CompletedCount, ErrorCode: task.ErrorCode, CanCancel: jobsdomain.CanCancel(task), CanRetry: task.CanRetry}
	} else if !errors.Is(err, sql.ErrNoRows) {
		return nil, nil, err
	}
	return observation, refresh, tx.Commit()
}

func (repo *repository) Admission() jobsports.Admission {
	return func(string) persistence.WriteAction { return func(context.Context, *sql.Tx) error { return nil } }
}
func (repo *repository) Completion(observation storagedomain.Observation) persistence.WriteAction {
	data, err := json.Marshal(observation.Usage)
	return func(ctx context.Context, tx *sql.Tx) error {
		if err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `INSERT INTO storage_observations(singleton,task_id,started_at,finished_at,result_json) VALUES(1,?,?,?,?)
		 ON CONFLICT(singleton) DO UPDATE SET task_id=excluded.task_id,started_at=excluded.started_at,finished_at=excluded.finished_at,result_json=excluded.result_json`, observation.TaskID, observation.StartedAt, observation.FinishedAt, string(data))
		return err
	}
}
