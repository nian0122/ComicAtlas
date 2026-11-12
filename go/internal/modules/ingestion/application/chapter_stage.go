package application

import (
	ingestiondomain "comicatlas.local/backend/internal/modules/ingestion/domain"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	"context"
	"path"
	"strings"
)

// 规范化后的纯章节目录直接进入发布布局，恢复时拒绝来源和目标同时存在。
func (directory *Directory) stageChapters(ctx context.Context, task jobsdomain.Task, root, stage string, journal ingestiondomain.Preparation) error {
	bySource := map[string]ingestiondomain.PreparedFile{}
	for _, file := range journal.Files {
		bySource[file.Source] = file
	}
	type pair struct{ source, target string }
	groups := map[string][]pair{}
	order := []string{}
	for _, item := range journal.Layout.Plan.Media {
		file, ok := bySource[journal.Layout.Sources[item.ID]]
		if !ok {
			return ingestiondomain.ErrManifest
		}
		target := stage + "/" + strings.TrimPrefix(item.RelativePath, "hq/"+task.ComicID+"/")
		key := path.Dir(target)
		if _, ok = groups[key]; !ok {
			order = append(order, key)
		}
		groups[key] = append(groups[key], pair{root + "/" + file.Normalized, target})
	}
	for _, target := range order {
		if err := ctx.Err(); err != nil {
			return err
		}
		entries := groups[target]
		source := path.Dir(entries[0].source)
		whole := true
		for _, entry := range entries {
			if path.Dir(entry.source) != source || path.Base(entry.source) != path.Base(entry.target) {
				whole = false
			}
		}
		exists, err := directory.exists(target)
		if err != nil {
			return err
		}
		if !exists && whole && pureChapter(directory.media, source, len(entries)) {
			if err = directory.moveBetween(directory.media, source, target); err != nil {
				return storageFailure(err)
			}
			continue
		}
		if err = directory.media.MkdirAll(target, 0750); err != nil {
			return storageFailure(err)
		}
		for _, entry := range entries {
			if err = ctx.Err(); err != nil {
				return err
			}
			exists, err = directory.exists(entry.target)
			if err != nil {
				return err
			}
			if exists {
				present, statErr := directory.exists(entry.source)
				if statErr != nil {
					return statErr
				}
				if present {
					return ingestiondomain.ErrPublishConflict
				}
				continue
			}
			if err = directory.renameBetween(directory.media, entry.source, entry.target); err != nil {
				return storageFailure(err)
			}
		}
		if err = directory.syncMediaParents(target); err != nil {
			return storageFailure(err)
		}
		parents := map[string]bool{}
		for _, entry := range entries {
			parents[path.Dir(entry.source)] = true
		}
		for parent := range parents {
			exists, statErr := directory.exists(parent)
			if statErr != nil {
				return statErr
			}
			if !exists {
				continue
			}
			if err = directory.syncMediaDirectory(parent); err != nil {
				return storageFailure(err)
			}
		}
	}
	return nil
}
