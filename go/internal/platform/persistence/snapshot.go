package persistence

import (
	"context"
	"database/sql"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/gofrs/flock"
)

// OfflineSnapshot 只读连接并持有应用同一把实例锁，整个媒体复制期间不得提前 Close。
type OfflineSnapshot struct {
	reader   *sql.DB
	lock     *flock.Flock
	info     SnapshotInfo
	mu       sync.RWMutex
	closed   bool
	closeErr error
}

// OpenOfflineSnapshot 不创建数据库、不迁移、不启动任务；运行中应用持锁时直接拒绝。
func OpenOfflineSnapshot(ctx context.Context, directory string) (_ *OfflineSnapshot, result error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	directory, err := filepath.EvalSymlinks(directory)
	if err != nil {
		return nil, ErrSnapshotMissing
	}
	info, err := os.Stat(directory)
	if err != nil || !info.IsDir() {
		return nil, ErrSnapshotMissing
	}
	path := filepath.Join(directory, "comicatlas.sqlite3")
	if err = snapshotRegular(path); err != nil {
		return nil, err
	}
	lockPath := filepath.Join(directory, "app.lock")
	if info, err = os.Lstat(lockPath); err == nil && !info.Mode().IsRegular() {
		return nil, ErrSnapshotInvalid
	} else if err != nil && !errors.Is(err, os.ErrNotExist) {
		return nil, ErrSnapshotInvalid
	}
	source := &OfflineSnapshot{lock: flock.New(lockPath)}
	locked, err := source.lock.TryLock()
	if err != nil {
		return nil, ErrSnapshotInvalid
	}
	if !locked {
		return nil, ErrAlreadyRunning
	}
	defer func() {
		if result != nil {
			result = errors.Join(result, source.Close())
		}
	}()
	if err = snapshotRegular(path); err != nil {
		return nil, err
	}
	source.reader, err = snapshotReader(ctx, path)
	if err != nil {
		return nil, err
	}
	source.info, err = inspectSnapshot(ctx, source.reader)
	if err != nil {
		return nil, err
	}
	return source, nil
}

func (source *OfflineSnapshot) Info() SnapshotInfo { return source.info }

func (source *OfflineSnapshot) Close() error {
	source.mu.Lock()
	defer source.mu.Unlock()
	if source.closed {
		return source.closeErr
	}
	source.closed = true
	if source.reader != nil {
		source.closeErr = source.reader.Close()
	}
	if source.lock != nil {
		source.closeErr = errors.Join(source.closeErr, source.lock.Unlock())
	}
	return source.closeErr
}

// InspectSnapshotFile 只读校验可独立恢复的快照文件，不执行向前迁移。
func InspectSnapshotFile(ctx context.Context, filename string) (SnapshotInfo, error) {
	if err := ctx.Err(); err != nil {
		return SnapshotInfo{}, err
	}
	if err := snapshotRegular(filename); err != nil {
		return SnapshotInfo{}, err
	}
	for _, suffix := range []string{"-journal", "-wal", "-shm"} {
		if _, err := os.Lstat(filename + suffix); !errors.Is(err, os.ErrNotExist) {
			return SnapshotInfo{}, ErrSnapshotInvalid
		}
	}
	reader, err := snapshotReader(ctx, filename)
	if err != nil {
		return SnapshotInfo{}, err
	}
	defer reader.Close()
	var journal string
	if err = reader.QueryRowContext(ctx, "PRAGMA journal_mode").Scan(&journal); err != nil || journal != "delete" {
		return SnapshotInfo{}, snapshotFailure(ctx)
	}
	return inspectSnapshot(ctx, reader)
}

func snapshotRegular(filename string) error {
	info, err := os.Lstat(filename)
	if errors.Is(err, os.ErrNotExist) {
		return ErrSnapshotMissing
	}
	if err != nil || !info.Mode().IsRegular() {
		return ErrSnapshotInvalid
	}
	return nil
}

func snapshotReader(ctx context.Context, filename string) (*sql.DB, error) {
	for _, suffix := range []string{"-journal", "-wal", "-shm"} {
		if info, err := os.Lstat(filename + suffix); err == nil && !info.Mode().IsRegular() {
			return nil, ErrSnapshotInvalid
		} else if err != nil && !errors.Is(err, os.ErrNotExist) {
			return nil, ErrSnapshotInvalid
		}
	}
	reader, err := sql.Open("comicatlas_sqlite", connectionURI(filename, 100*time.Millisecond, true))
	if err != nil {
		return nil, ErrSnapshotInvalid
	}
	reader.SetMaxOpenConns(1)
	reader.SetMaxIdleConns(1)
	if err = reader.PingContext(ctx); err != nil {
		reader.Close()
		return nil, snapshotFailure(ctx)
	}
	return reader, nil
}

func snapshotFailure(ctx context.Context) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	return ErrSnapshotInvalid
}
