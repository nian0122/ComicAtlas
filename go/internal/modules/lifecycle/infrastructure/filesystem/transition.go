package filesystem

import (
	"context"
	"errors"
	"os"
	"path"
	"path/filepath"
	"sort"

	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"comicatlas.local/backend/internal/platform/relocate"
)

func (files *Files) moveUnit(ctx context.Context, id, operation string, unit lifecycledomain.MoveUnit, verifyOnly bool) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	source, target := unit.Path, lifecycledomain.RetainedPath(id, unit.Path)
	if operation == "restore" {
		source, target = target, source
	}
	from, sourceErr := files.moveInfo(source, unit.Directory)
	to, targetErr := files.moveInfo(target, unit.Directory)
	if sourceErr != nil && !errors.Is(sourceErr, os.ErrNotExist) {
		return sourceErr
	}
	if targetErr != nil && !errors.Is(targetErr, os.ErrNotExist) {
		return targetErr
	}
	if sourceErr == nil && targetErr == nil {
		return lifecycledomain.ErrConflict
	}
	if targetErr == nil {
		if err := files.verifyMove(ctx, target, id, unit, to); err != nil {
			return err
		}
	} else {
		if sourceErr != nil || verifyOnly {
			return lifecycledomain.ErrMissing
		}
		if err := files.verifyMove(ctx, source, id, unit, from); err != nil {
			return err
		}
		if err := files.parents(target, true); err != nil {
			return err
		}
		if err := relocate.WithinRoot(files.root, source, target, from); err != nil {
			if errors.Is(err, os.ErrExist) {
				return lifecycledomain.ErrConflict
			}
			return err
		}
		observed, err := files.moveInfo(target, unit.Directory)
		if err != nil {
			return err
		}
		if unit.Directory {
			if err := files.verifyMove(ctx, target, id, unit, observed); err != nil {
				return err
			}
		} else if observed.Size() != unit.Size {
			return lifecycledomain.ErrChanged
		}
	}
	return errors.Join(files.syncDirectory(path.Dir(target)), files.syncDirectory(path.Dir(source)))
}

func (files *Files) applyMoves(ctx context.Context, value lifecycledomain.FileJournal, operation, phase string, visit func(lifecycledomain.FileResult) error) error {
	units := make(map[string]lifecycledomain.MoveUnit, len(value.Moves))
	for _, unit := range value.Moves {
		units[unit.Path] = unit
	}
	moved := map[string]bool{}
	for index, item := range value.Files {
		if err := ctx.Err(); err != nil {
			return err
		}
		var err error
		if item.Missing {
			for _, name := range []string{item.Source.Path, lifecycledomain.RetainedPath(value.ID, item.Source.Path)} {
				if _, statErr := files.stat(name); !errors.Is(statErr, os.ErrNotExist) {
					err = errors.Join(lifecycledomain.ErrConflict, statErr)
					break
				}
			}
		} else {
			unit, _ := lifecycledomain.MoveForPath(units, item.Source.Path)
			if !moved[unit.Path] {
				err = files.moveUnit(ctx, value.ID, operation, unit, phase == "verifying")
				moved[unit.Path] = err == nil
			}
			if err == nil && unit.Directory {
				name := item.Source.Path
				if operation == "trash" {
					name = lifecycledomain.RetainedPath(value.ID, name)
				}
				info, statErr := files.stat(name)
				err = statErr
				if err == nil && info.Size() != item.Size {
					err = lifecycledomain.ErrChanged
				}
			}
		}
		if err = reportFile(visit, value.ID, operation, phase, index+1, item, err); err != nil {
			return err
		}
	}
	for _, unit := range value.Moves {
		if !moved[unit.Path] {
			if err := files.moveUnit(ctx, value.ID, operation, unit, phase == "verifying"); err != nil {
				return err
			}
		}
	}
	return nil
}

func (files *Files) Trash(ctx context.Context, id string, visit func(lifecycledomain.FileResult) error) error {
	files.mutex.Lock()
	defer files.mutex.Unlock()
	value, err := files.load(id)
	if err != nil {
		return err
	}
	if value.Phase == "trashed" {
		return files.applyMoves(ctx, value, "trash", "verifying", visit)
	}
	if value.Phase != "prepared" {
		return lifecycledomain.ErrConflict
	}
	if err = files.applyMoves(ctx, value, "trash", "applying", visit); err != nil {
		return err
	}
	value.Phase = "trashed"
	return files.save(ctx, value, false)
}

func (files *Files) Restore(ctx context.Context, id string, visit func(lifecycledomain.FileResult) error) error {
	files.mutex.Lock()
	defer files.mutex.Unlock()
	value, err := files.load(id)
	if err != nil {
		return err
	}
	if value.Phase == "restored" {
		return files.applyMoves(ctx, value, "restore", "verifying", visit)
	}
	if value.Phase != "trashed" && value.Phase != "restoring" {
		return lifecycledomain.ErrConflict
	}
	if value.Phase == "trashed" {
		value.Phase = "restoring"
		if err = files.save(ctx, value, false); err != nil {
			return err
		}
	}
	if err = files.applyMoves(ctx, value, "restore", "applying", visit); err != nil {
		return err
	}
	value.Phase = "restored"
	return files.save(ctx, value, false)
}

// Purge 按条目整体删除回收文件目录，不再逐文件核对或删除。
// 清单在数据库收尾成功前继续保留，供失败重试；用户确认和保留期由上层准入完成。
func (files *Files) Purge(ctx context.Context, id string) error {
	files.mutex.Lock()
	defer files.mutex.Unlock()
	value, err := files.load(id)
	if err != nil {
		return err
	}
	if value.Phase != "trashed" && value.Phase != "purging" && value.Phase != "purged" {
		return lifecycledomain.ErrConflict
	}
	if value.Phase == "trashed" {
		value.Phase = "purging"
		if err = files.save(ctx, value, false); err != nil {
			return err
		}
	}
	if err = ctx.Err(); err != nil {
		return err
	}
	if err = files.root.RemoveAll(filepath.FromSlash("trash/" + id + "/files")); err != nil {
		return err
	}
	if err = files.syncDirectory("trash/" + id); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	if value.Phase == "purged" {
		return nil
	}
	value.Phase = "purged"
	return files.save(ctx, value, false)
}

// RemoveDirectories 递归删除漫画或章节的托管路径，包括目录内未登记的文件。
// 路径必须位于 hq、lq、thumbs、metadata、retired 之下；目标本身是符号链接时拒绝，目录内的符号链接只删除链接。
func (files *Files) RemoveDirectories(ctx context.Context, directories []string) error {
	files.mutex.Lock()
	defer files.mutex.Unlock()
	for _, name := range directories {
		if !lifecycledomain.ValidSourcePath(name) {
			return lifecycledomain.ErrValidation
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		if err := files.parents(name, false); err != nil {
			if errors.Is(err, os.ErrNotExist) {
				continue
			}
			return err
		}
		info, err := files.root.Lstat(filepath.FromSlash(name))
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			return err
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return lifecycledomain.ErrConflict
		}
		if err = files.root.RemoveAll(filepath.FromSlash(name)); err != nil {
			return err
		}
		if err = files.syncDirectory(path.Dir(name)); err != nil {
			return err
		}
	}
	return nil
}

// RemoveJournal 在数据库收尾成功后删除该条目的文件清单和空回收目录。
// 只清理清单所建的空父目录及 manifest.json。
func (files *Files) RemoveJournal(ctx context.Context, id string) error {
	files.mutex.Lock()
	defer files.mutex.Unlock()
	if !lifecycledomain.ValidJournalID(id) {
		return lifecycledomain.ErrValidation
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	entry := "trash/" + id
	retained := entry + "/files"
	journal, err := files.load(id)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	parents := map[string]bool{retained: true}
	for _, unit := range journal.Moves {
		if journal.Phase == "restored" && unit.Directory {
			if err := files.removeMoveMarker(unit.Path, id, unit.Proof); err != nil {
				return err
			}
		}
		for parent := path.Dir(lifecycledomain.RetainedPath(id, unit.Path)); parent != retained; parent = path.Dir(parent) {
			parents[parent] = true
		}
	}
	directories := make([]string, 0, len(parents))
	for parent := range parents {
		directories = append(directories, parent)
	}
	sort.Sort(sort.Reverse(sort.StringSlice(directories)))
	for _, parent := range directories {
		_ = files.removeEmptyDirectory(ctx, parent)
	}
	manifest := entry + "/manifest.json"
	info, err := files.root.Lstat(filepath.FromSlash(manifest))
	if err == nil {
		if !info.Mode().IsRegular() || info.Mode()&os.ModeSymlink != 0 {
			return lifecycledomain.ErrConflict
		}
		if err = files.root.Remove(filepath.FromSlash(manifest)); err != nil {
			return err
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return err
	}
	_ = files.removeEmptyDirectory(ctx, entry)
	if err = files.syncDirectory("trash"); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}

func (files *Files) removeEmptyDirectory(ctx context.Context, name string) error {
	if err := files.parents(name, false); err != nil {
		return err
	}
	info, err := files.root.Lstat(filepath.FromSlash(name))
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return lifecycledomain.ErrConflict
	}
	if err = ctx.Err(); err != nil {
		return err
	}
	if err = files.root.Remove(filepath.FromSlash(name)); err != nil {
		return err
	}
	return files.syncDirectory(path.Dir(name))
}
