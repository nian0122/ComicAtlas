package filesystem

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"context"
	"errors"
	"io"
	"path"
)

func (move *directoryExecution) moveGroup(ctx context.Context, group *directoryGroup) error {
	present := []*directoryItem{}
	for _, item := range group.items {
		if !item.state.Missing {
			present = append(present, item)
		}
	}
	if len(present) == 0 {
		return nil
	}
	target := move.base + "/" + group.target
	whole, err := move.wholeChapter(ctx, present)
	if err != nil {
		return err
	}
	parents := map[string]bool{path.Dir(target): true, target: true}
	if whole {
		source := path.Dir(present[0].entry.Source)
		if info, err := move.entry(target); err != nil {
			return err
		} else if info != nil {
			folder, err := move.files.root.Open(target)
			if err != nil {
				return err
			}
			names, readErr := folder.ReadDir(1)
			folder.Close()
			if readErr != nil && !errors.Is(readErr, io.EOF) {
				return readErr
			}
			if len(names) != 0 {
				return exportdomain.ErrConflict
			}
			if err = move.files.root.Remove(target); err != nil {
				return err
			}
		}
		if err = move.mkdirParents(target); err != nil {
			return err
		}
		if err = move.rename(ctx, source, target); err != nil {
			return err
		}
		move.stats.DirectoryMoves++
		move.wholeSources[source] = true
		parents[path.Dir(source)] = true
		for _, item := range present {
			item.location = target + "/" + path.Base(item.entry.Source)
		}
		if err = move.syncParents(parents); err != nil {
			return err
		}
	} else {
		for _, item := range present {
			if item.location != item.entry.Source {
				continue
			}
			destination := move.base + "/" + item.target
			if err = move.mkdirParents(destination); err != nil {
				return err
			}
			if err = move.rename(ctx, item.location, destination); err != nil {
				return err
			}
			parents[path.Dir(item.location)] = true
			item.location = destination
		}
	}
	if err = move.normalizeNames(ctx, present); err != nil {
		return err
	}
	return move.syncParents(parents)
}

// 仅完整物理章节才能整目录移动；分散文件继续按计划移动，不复制降级。
func (move *directoryExecution) wholeChapter(ctx context.Context, items []*directoryItem) (bool, error) {
	source, ok := exportdomain.DirectoryPhysicalChapter(items[0].entry.Source)
	if !ok {
		return false, nil
	}

	expected := map[string]bool{}
	for _, item := range items {
		if _, ok := exportdomain.DirectoryPhysicalChapter(item.entry.Source); !ok {
			return false, nil
		}
		if item.location != item.entry.Source || path.Dir(item.entry.Source) != source {
			return false, nil
		}
		expected[path.Base(item.entry.Source)] = true
	}
	if err := ctx.Err(); err != nil {
		return false, err
	}
	folder, err := move.files.root.Open(source)
	if err != nil {
		return false, err
	}
	defer folder.Close()
	names, readErr := folder.ReadDir(len(items) + 1)
	if readErr != nil && !errors.Is(readErr, io.EOF) {
		return false, readErr
	}
	if len(names) != len(items) {
		return false, nil
	}
	for _, name := range names {
		if !name.Type().IsRegular() || !expected[name.Name()] {
			return false, nil
		}
	}
	return true, nil
}

func (move *directoryExecution) normalizeNames(ctx context.Context, items []*directoryItem) error {
	pending := make([]*directoryItem, 0, len(items))
	for _, item := range items {
		if item.location != move.base+"/"+item.target {
			pending = append(pending, item)
		}
	}
	if len(pending) == 0 {
		return nil
	}
	items = pending
	for _, item := range items {
		temporary := path.Dir(item.location) + "/" + temporaryPage(item.position)
		if item.location != temporary {
			if err := move.rename(ctx, item.location, temporary); err != nil {
				return err
			}
			item.location = temporary
		}
	}
	if err := move.files.syncDirectory(path.Dir(items[0].location)); err != nil {
		return err
	}
	for _, item := range items {
		target := move.base + "/" + item.target
		if err := move.rename(ctx, item.location, target); err != nil {
			return err
		}
		item.location = target
	}
	return nil
}
