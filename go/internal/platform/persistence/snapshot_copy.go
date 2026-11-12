package persistence

import (
	"context"
	"database/sql"
	"errors"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/mattn/go-sqlite3"
)

// WriteSnapshot 使用原生增量备份 API 吸收 WAL 内容，不复制运行数据库文件或覆盖目标。
func (source *OfflineSnapshot) WriteSnapshot(ctx context.Context, filename string) error {
	source.mu.RLock()
	defer source.mu.RUnlock()
	if source.closed {
		return ErrSnapshotInvalid
	}
	_, err := writeSnapshot(ctx, source.reader, filename, &source.info)
	return err
}

// WriteOnlineSnapshot 只读打开正在服务的数据库，校验 SQLite 备份 API 生成的独立副本。
func WriteOnlineSnapshot(ctx context.Context, directory, destination string) (SnapshotInfo, error) {
	if err := ctx.Err(); err != nil {
		return SnapshotInfo{}, err
	}
	filename := filepath.Join(directory, "comicatlas.sqlite3")
	if err := snapshotRegular(filename); err != nil {
		return SnapshotInfo{}, err
	}
	reader, err := snapshotReader(ctx, filename)
	if err != nil {
		return SnapshotInfo{}, err
	}
	defer reader.Close()
	return writeSnapshot(ctx, reader, destination, nil)
}

func writeSnapshot(ctx context.Context, reader *sql.DB, filename string, expected *SnapshotInfo) (_ SnapshotInfo, result error) {
	if err := ctx.Err(); err != nil {
		return SnapshotInfo{}, err
	}
	for _, suffix := range []string{"", "-journal", "-wal", "-shm"} {
		if _, err := os.Lstat(filename + suffix); err == nil {
			return SnapshotInfo{}, ErrSnapshotExists
		} else if !errors.Is(err, os.ErrNotExist) {
			return SnapshotInfo{}, ErrSnapshotInvalid
		}
	}
	file, err := os.OpenFile(filename, os.O_CREATE|os.O_EXCL|os.O_RDWR, 0o600)
	if err != nil {
		if errors.Is(err, os.ErrExist) {
			return SnapshotInfo{}, ErrSnapshotExists
		}
		return SnapshotInfo{}, err
	}
	defer func() {
		result = errors.Join(result, file.Close())
		if result != nil {
			// 仅删除本次 O_EXCL 创建的文件及其 SQLite 日志，不递归删除目录。
			for _, suffix := range []string{"-journal", "-wal", "-shm", ""} {
				if err := os.Remove(filename + suffix); err != nil && !errors.Is(err, os.ErrNotExist) {
					result = errors.Join(result, err)
				}
			}
		}
	}()
	writer, err := sql.Open("comicatlas_sqlite", snapshotDestinationURI(filename))
	if err != nil {
		return SnapshotInfo{}, ErrSnapshotInvalid
	}
	writer.SetMaxOpenConns(1)
	defer func() { result = errors.Join(result, writer.Close()) }()
	if err = copySnapshotConnections(ctx, reader, writer); err != nil {
		return SnapshotInfo{}, err
	}
	// 原库可为 WAL，目标保持独立回滚日志格式，完成后不依赖旁路文件。
	var journal string
	if err = writer.QueryRowContext(ctx, "PRAGMA journal_mode=DELETE").Scan(&journal); err != nil || journal != "delete" {
		return SnapshotInfo{}, snapshotFailure(ctx)
	}
	info, err := inspectSnapshot(ctx, writer)
	if err != nil {
		return SnapshotInfo{}, err
	}
	if expected != nil && info != *expected {
		return SnapshotInfo{}, ErrSnapshotInvalid
	}
	if err = writer.Close(); err != nil {
		return SnapshotInfo{}, err
	}
	if err = ctx.Err(); err != nil {
		return SnapshotInfo{}, err
	}
	if err = file.Sync(); err != nil {
		return SnapshotInfo{}, err
	}
	return info, nil
}

func snapshotDestinationURI(filename string) string {
	path, _ := filepath.Abs(filename)
	path = filepath.ToSlash(path)
	if !strings.HasPrefix(path, "/") {
		path = "/" + path
	}
	parameters := url.Values{"mode": {"rw"}, "_journal_mode": {"DELETE"}, "_synchronous": {"FULL"}, "_busy_timeout": {"100"}, "_foreign_keys": {"on"}}
	return (&url.URL{Scheme: "file", Path: path, RawQuery: parameters.Encode()}).String()
}

func copySnapshotConnections(ctx context.Context, reader, writer *sql.DB) error {
	input, err := reader.Conn(ctx)
	if err != nil {
		return snapshotFailure(ctx)
	}
	defer input.Close()
	output, err := writer.Conn(ctx)
	if err != nil {
		return snapshotFailure(ctx)
	}
	defer output.Close()
	return output.Raw(func(destination any) error {
		return input.Raw(func(original any) error {
			src, ok := original.(*sqlite3.SQLiteConn)
			if !ok {
				return ErrSnapshotInvalid
			}
			dst, ok := destination.(*sqlite3.SQLiteConn)
			if !ok {
				return ErrSnapshotInvalid
			}
			return stepSnapshot(ctx, dst, src)
		})
	})
}

func stepSnapshot(ctx context.Context, destination, source *sqlite3.SQLiteConn) (result error) {
	backup, err := destination.Backup("main", source, "main")
	if err != nil {
		return snapshotFailure(ctx)
	}
	defer func() { result = errors.Join(result, backup.Finish()) }()
	for {
		if err = ctx.Err(); err != nil {
			return err
		}
		done, err := backup.Step(256)
		if err != nil {
			return snapshotFailure(ctx)
		}
		if done {
			return ctx.Err()
		}
		timer := time.NewTimer(2 * time.Millisecond)
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-timer.C:
		}
	}
}
