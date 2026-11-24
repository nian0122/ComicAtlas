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
	byPath := map[string][]*directoryItem{}
	for _, group := range move.groups {
		for _, item := range group.items {
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

			if info != nil {
				if move.published {
					return exportdomain.ErrConflict
				}
				if err = move.checkProperties(item.entry.Source, item, info); err != nil {
					return err
				}
				item.location = item.entry.Source
				continue
			}
			if group.phase != "whole" || move.published {
				byPath[item.target] = append(byPath[item.target], item)
			}
			if !move.published {
				parent := path.Dir(item.target)
				candidates := []string{temporaryPage(item.position)}
				if group.receipt == "" || group.phase == "whole" {
					candidates = append(candidates, path.Base(item.entry.Source))
				}
				for _, candidate := range candidates {
					name := path.Join(parent, candidate)
					if name != item.target || group.phase == "whole" {
						byPath[name] = append(byPath[name], item)
					}
				}
			}
		}
	}
	if err := move.walkOutput(ctx, func(name string, entry fs.DirEntry) error {
		full := move.base + "/" + name
		info, err := entry.Info()
		if err != nil {
			return err
		}
		var item *directoryItem
		for _, candidate := range byPath[name] {
			if candidate.location == "" && move.checkReceipt(full, candidate, info) == nil {
				if item != nil {
					return exportdomain.ErrConflict
				}
				item = candidate
			}
		}
		if item == nil {
			return exportdomain.ErrConflict
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
