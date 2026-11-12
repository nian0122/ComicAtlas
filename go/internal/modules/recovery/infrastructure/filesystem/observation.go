package filesystem

import (
	recoveryports "comicatlas.local/backend/internal/modules/recovery/application/ports"
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"context"
	"errors"
	"os"
)

// observation 借用根，不持有逐文件句柄；只供一本漫画的顺序准备与复核使用。
type observation struct {
	*VideoFiles
	stamps map[string]os.FileInfo
}

var _ recoveryports.Observations = (*VideoFiles)(nil)

func (files *VideoFiles) Observe() recoveryports.SourceObservation {
	value := &observation{stamps: make(map[string]os.FileInfo)}
	value.VideoFiles = NewVideo(&Files{root: files.root, observe: value.record}, files.inspector)
	return value
}

func sameObservation(before, after os.FileInfo) bool {
	return before != nil && after != nil && os.SameFile(before, after) && before.Mode() == after.Mode() && before.Size() == after.Size() && before.ModTime().Equal(after.ModTime())
}

func (value *observation) record(name string, info os.FileInfo) error {
	if previous, found := value.stamps[name]; found {
		if previous == nil && info == nil {
			return nil
		}
		if !sameObservation(previous, info) {
			return recoverydomain.ErrChanged
		}
		return nil
	}
	if len(value.stamps) >= recoverydomain.MaxSourceObservations {
		return recoverydomain.ErrLimit
	}
	value.stamps[name] = info
	return nil
}

func (value *observation) Recheck(ctx context.Context) error {
	for name, before := range value.stamps {
		if err := ctx.Err(); err != nil {
			return err
		}
		current, err := value.root.Lstat(name)
		if before == nil && errors.Is(err, os.ErrNotExist) {
			continue
		}
		if err != nil {
			return errors.Join(recoverydomain.ErrChanged, err)
		}
		if !sameObservation(before, current) {
			return recoverydomain.ErrChanged
		}
	}
	return ctx.Err()
}
