package filesystem

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"context"
	"fmt"
	"io/fs"
	"path"
	"sort"
	"strings"
)

func temporaryPage(position int) string { return fmt.Sprintf(".comicatlas-page-%06d", position) }

// 先核对整批位置与归属，不读媒体字节；任何来源变化或未知输出都在本次移动前拒绝。
func (move *directoryExecution) locateScene(ctx context.Context) error {
	byID := map[string]*directoryItem{}
	for _, item := range move.items {
		if err := ctx.Err(); err != nil {
			return err
		}
		info, err := move.entry(item.entry.Source)
		if err != nil {
			return err
		}
		if item.state.Missing {
			if info != nil {
				return exportdomain.ErrSource
			}
			continue
		}
		byID[item.state.Identity] = item
		if info != nil {
			if move.published {
				return exportdomain.ErrConflict
			}
			if err = move.checkProperties(item.entry.Source, item, info); err != nil {
				return err
			}
			item.location = item.entry.Source
		}
	}
	if err := move.walkOutput(ctx, func(name string, entry fs.DirEntry) error {
		full := move.base + "/" + name
		id, err := move.identity(full)
		if err != nil {
			return err
		}
		item := byID[id]
		if item == nil || item.location != "" {
			return exportdomain.ErrConflict
		}
		final := item.target
		if move.published && name != final {
			return exportdomain.ErrConflict
		}
		if name != final && name != path.Dir(final)+"/"+path.Base(item.entry.Source) && name != path.Dir(final)+"/"+temporaryPage(item.position) {
			return exportdomain.ErrConflict
		}
		info, err := entry.Info()
		if err != nil || !info.Mode().IsRegular() || info.Size() != item.state.Size || info.ModTime().UnixNano() != item.state.Modified {
			return exportdomain.ErrSource
		}
		item.location = full
		return nil
	}); err != nil {
		return err
	}
	for _, item := range move.items {
		if !item.state.Missing && item.location == "" {
			return exportdomain.ErrSource
		}
	}
	return move.inspectSources(ctx, false, false, nil)
}

func (move *directoryExecution) inspectSources(ctx context.Context, requireAbsent, recheck bool, skipWhole map[string]bool) error {
	if err := move.files.verifyDirectoryAbsences(ctx, move.planning.Plans); err != nil {
		return err
	}
	expected := map[string]string{}
	sources := []string{}
	for _, item := range move.items {
		if item.state.Missing {
			continue
		}
		expected[item.entry.Source] = item.comic
		if !requireAbsent && item.location == item.entry.Source {
			sources = append(sources, item.entry.Source)
		}
	}
	if recheck {
		for _, item := range move.items {
			if err := ctx.Err(); err != nil {
				return err
			}
			if skipWhole != nil && directoryMovedOut(item.entry.Source, skipWhole) {
				continue
			}
			info, err := move.entry(item.entry.Source)
			if err != nil {
				return err
			}
			if info != nil {
				if requireAbsent || item.state.Missing {
					return exportdomain.ErrConflict
				}
			}
		}
	}
	sort.Strings(sources)
	visited := 0
	for _, plan := range move.planning.Plans {
		for _, directory := range plan.OwnedDirectories {
			if err := move.files.inspectOwnedDirectory(ctx, directory, plan.ComicID, expected, sources, &visited, 0); err != nil {
				return err
			}
		}
	}
	return nil
}

func (move *directoryExecution) verifySources(ctx context.Context) error {
	for directory := range move.wholeSources {
		info, err := move.entry(directory)
		if err != nil {
			return err
		}
		if info != nil {
			return exportdomain.ErrConflict
		}
	}
	return move.inspectSources(ctx, true, true, move.wholeSources)
}

func directoryMovedOut(source string, directories map[string]bool) bool {
	for directory := range directories {
		if source == directory || strings.HasPrefix(source, directory+"/") {
			return true
		}
	}
	return false
}
