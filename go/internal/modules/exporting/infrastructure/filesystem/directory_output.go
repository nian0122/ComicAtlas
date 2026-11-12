package filesystem

import (
	"bytes"
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"context"
	"crypto/rand"
	"errors"
	"io"
	"io/fs"
	"os"
	"path"
	"strings"
)

func (move *directoryExecution) prepareOutput(ctx context.Context) error {
	final, err := move.files.directoryEntry("exports/" + move.id)
	if err != nil {
		return err
	}
	stage, err := move.files.directoryEntry(move.base)
	if err != nil {
		return err
	}
	if final != nil {
		if stage != nil || !final.IsDir() {
			return exportdomain.ErrConflict
		}
		move.published = true
		move.base = "exports/" + move.id
		return move.checkControl(ctx, exportdomain.DirectoryMarkerName, move.controls[exportdomain.DirectoryMarkerName])
	}
	if stage != nil {
		if !stage.IsDir() {
			return exportdomain.ErrConflict
		}
		marker, err := move.files.directoryEntry(move.base + "/" + exportdomain.DirectoryMarkerName)
		if err != nil {
			return err
		}
		if marker == nil {
			folder, err := move.files.root.Open(move.base)
			if err != nil {
				return err
			}
			entries, readErr := folder.ReadDir(1)
			closeErr := folder.Close()
			if readErr != nil && !errors.Is(readErr, io.EOF) {
				return readErr
			}
			if closeErr != nil {
				return closeErr
			}
			if len(entries) != 0 {
				return exportdomain.ErrConflict
			}
		} else if err = move.checkControl(ctx, exportdomain.DirectoryMarkerName, move.controls[exportdomain.DirectoryMarkerName]); err != nil {
			return err
		}
	}
	if err = move.mkdirParents(move.base + "/placeholder"); err != nil {
		return err
	}
	if err = move.ensureControl(ctx, exportdomain.DirectoryMarkerName, move.controls[exportdomain.DirectoryMarkerName]); err != nil {
		return err
	}
	for name := range move.directories {
		if err = ctx.Err(); err != nil {
			return err
		}
		if err = move.mkdirParents(path.Join(move.base, name, "placeholder")); err != nil {
			return err
		}
	}
	for name, data := range move.controls {
		if name == exportdomain.DirectoryMarkerName {
			continue
		}
		if err = move.ensureControl(ctx, name, data); err != nil {
			return err
		}
	}
	return nil
}

func (move *directoryExecution) ensureControl(ctx context.Context, name string, data []byte) (err error) {
	target := move.base + "/" + name
	info, err := move.files.directoryEntry(target)
	if err != nil {
		return err
	}
	if info != nil {
		return move.checkControl(ctx, name, data)
	}
	temporary := "staging/exports/.directory-" + move.id + "-" + rand.Text() + ".tmp"
	output, err := move.files.root.OpenFile(temporary, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return err
	}
	defer func() {
		if output != nil {
			err = errors.Join(err, output.Close())
		}
		removeErr := move.files.root.Remove(temporary)
		if !errors.Is(removeErr, os.ErrNotExist) {
			err = errors.Join(err, removeErr)
		}
	}()
	if err = ctx.Err(); err != nil {
		return err
	}
	if _, err = output.Write(data); err != nil {
		return err
	}
	if err = output.Sync(); err != nil {
		return err
	}
	if err = output.Close(); err != nil {
		output = nil
		return err
	}
	output = nil
	if err = move.rename(ctx, temporary, target); err != nil {
		return err
	}
	return move.files.syncDirectory(path.Dir(target))
}

func (move *directoryExecution) checkControl(ctx context.Context, name string, data []byte) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	full := move.base + "/" + name
	info, err := move.files.directoryEntry(full)
	if err != nil {
		return err
	}
	if info == nil || !info.Mode().IsRegular() || info.Size() != int64(len(data)) {
		return exportdomain.ErrManifest
	}
	input, err := move.files.root.Open(full)
	if err != nil {
		return err
	}
	defer input.Close()
	before, err := input.Stat()
	if err != nil || !os.SameFile(info, before) {
		return exportdomain.ErrManifest
	}
	actual, err := io.ReadAll(io.LimitReader(input, int64(len(data))+1))
	if err != nil {
		return err
	}
	after, err := input.Stat()
	if err != nil || !os.SameFile(before, after) || !before.ModTime().Equal(after.ModTime()) || !bytes.Equal(actual, data) {
		return exportdomain.ErrManifest
	}
	current, err := move.files.directoryEntry(full)
	if err != nil || current == nil || !os.SameFile(before, current) || current.Size() != after.Size() || !current.ModTime().Equal(after.ModTime()) {
		return exportdomain.ErrManifest
	}
	return nil
}

func (move *directoryExecution) walkOutput(ctx context.Context, visit func(string, fs.DirEntry) error) error {
	count, seenDirectories, seenControls := 0, 0, 0
	err := fs.WalkDir(move.files.root.FS(), move.base, func(name string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		count++
		if count > len(move.directories)+len(move.controls)+len(move.items) {
			return exportdomain.ErrConflict
		}
		relative := "."
		if name != move.base {
			relative = strings.TrimPrefix(name, move.base+"/")
		}
		if entry.IsDir() {
			if !move.directories[relative] {
				return exportdomain.ErrConflict
			}
			seenDirectories++
			return nil
		}
		if !entry.Type().IsRegular() {
			return exportdomain.ErrSource
		}
		if data, ok := move.controls[relative]; ok {
			seenControls++
			return move.checkControl(ctx, relative, data)
		}
		return visit(relative, entry)
	})
	if err != nil {
		return err
	}
	if seenDirectories != len(move.directories) || seenControls != len(move.controls) {
		return exportdomain.ErrManifest
	}
	return nil
}

// 正常路径只进行这一次完整输出复核；同一文件锁保持到数据库提交。
func (move *directoryExecution) finishOutput(ctx context.Context) error {
	seen := map[string]bool{}
	byTarget := map[string]*directoryItem{}
	for _, item := range move.items {
		if !item.state.Missing {
			byTarget[item.target] = item
		}
	}
	if err := move.walkOutput(ctx, func(name string, entry fs.DirEntry) error {
		item := byTarget[name]
		if item == nil || seen[name] {
			return exportdomain.ErrConflict
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		if err := move.checkReceipt(move.base+"/"+name, item, info); err != nil {
			return err
		}
		seen[name] = true
		return nil
	}); err != nil {
		return err
	}
	if len(seen) != len(byTarget) {
		return exportdomain.ErrManifest
	}
	if err := move.verifySources(ctx); err != nil {
		return err
	}
	if move.published {
		return nil
	}
	info, err := move.entry(move.base)
	if err != nil {
		return err
	}
	from, err := move.files.root.Open("staging/exports")
	if err != nil {
		return err
	}
	defer from.Close()
	to, err := move.files.root.Open("exports")
	if err != nil {
		return err
	}
	defer to.Close()
	if err = sameDirectoryVolume(from, to); err != nil {
		return err
	}
	if err = ctx.Err(); err != nil {
		return err
	}
	if err = renameDirectoryNoReplace(from, to, move.id, info); err != nil {
		return err
	}
	move.stats.Renames++
	actual, err := move.files.directoryEntry("exports/" + move.id)
	if err != nil || actual == nil || !os.SameFile(info, actual) {
		return exportdomain.ErrManifest
	}
	for _, name := range []string{"exports/" + move.id, "exports", "staging/exports"} {
		if err = move.files.syncDirectory(name); err != nil {
			return err
		}
	}
	move.base = "exports/" + move.id
	move.published = true
	return nil
}
