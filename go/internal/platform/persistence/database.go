// Package persistence 管理 SQLite 连接、实例锁和向前迁移。
package persistence

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"embed"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"

	"comicatlas.local/backend/internal/platform/natural"

	"github.com/gofrs/flock"
	"github.com/mattn/go-sqlite3"
)

func init() {
	sql.Register("comicatlas_sqlite", &sqlite3.SQLiteDriver{ConnectHook: func(connection *sqlite3.SQLiteConn) error {
		if err := connection.RegisterCollation("COMIC_NATURAL", natural.Compare); err != nil {
			return err
		}
		return connection.RegisterFunc("COMIC_LOWER", strings.ToLower, true)
	}})
}

//go:embed migrations/*.sql
var migrations embed.FS

var ErrAlreadyRunning = errors.New("数据库已被另一个应用实例使用")

type Database struct {
	Writer *sql.DB
	Reader *sql.DB
	lock   *flock.Flock
}

func Open(ctx context.Context, directory string, busyTimeout time.Duration) (_ *Database, err error) {
	if busyTimeout < time.Millisecond || busyTimeout > 30*time.Second {
		return nil, errors.New("数据库等待时间必须为 1ms..30s")
	}
	if err = os.MkdirAll(directory, 0o750); err != nil {
		return nil, fmt.Errorf("创建数据目录: %w", err)
	}
	// 解析父目录软链接，保证同一物理目录共用同一实例锁。
	directory, err = filepath.EvalSymlinks(directory)
	if err != nil {
		return nil, fmt.Errorf("解析数据目录: %w", err)
	}
	database := &Database{lock: flock.New(filepath.Join(directory, "app.lock"))}
	locked, err := database.lock.TryLock()
	if err != nil {
		return nil, fmt.Errorf("获取实例锁: %w", err)
	}
	if !locked {
		return nil, ErrAlreadyRunning
	}
	defer func() {
		if err != nil {
			err = errors.Join(err, database.Close())
		}
	}()
	path := filepath.Join(directory, "comicatlas.sqlite3")
	// 固定文件名不能是链接，避免不同数据目录别名绕过实例锁。
	if info, statErr := os.Lstat(path); statErr == nil && !info.Mode().IsRegular() {
		return nil, errors.New("数据库路径必须为普通文件")
	} else if statErr != nil && !errors.Is(statErr, os.ErrNotExist) {
		return nil, statErr
	}
	database.Writer, err = sql.Open("comicatlas_sqlite", connectionURI(path, busyTimeout, false))
	if err != nil {
		return nil, err
	}
	database.Writer.SetMaxOpenConns(1)
	database.Writer.SetMaxIdleConns(1)
	if err = database.Writer.PingContext(ctx); err != nil {
		return nil, fmt.Errorf("连接 SQLite: %w", err)
	}
	var journal string
	if err = database.Writer.QueryRowContext(ctx, "PRAGMA journal_mode").Scan(&journal); err != nil {
		return nil, fmt.Errorf("读取 SQLite WAL 配置: %w", err)
	}
	if journal != "wal" {
		return nil, fmt.Errorf("SQLite 未启用 WAL: %s", journal)
	}
	if err = migrate(ctx, database.Writer); err != nil {
		return nil, err
	}
	database.Reader, err = sql.Open("comicatlas_sqlite", connectionURI(path, busyTimeout, true))
	if err != nil {
		return nil, err
	}
	database.Reader.SetMaxOpenConns(4)
	database.Reader.SetMaxIdleConns(2)
	if err = database.Reader.PingContext(ctx); err != nil {
		return nil, fmt.Errorf("打开只读连接: %w", err)
	}
	return database, nil
}

// pageCacheKiB 是每个 SQLite 连接的页缓存（KiB）；媒体表与 LQ 状态合计约 32 MB。
const pageCacheKiB = 32768

func connectionURI(path string, timeout time.Duration, readOnly bool) string {
	path, _ = filepath.Abs(path)
	path = filepath.ToSlash(path)
	if !strings.HasPrefix(path, "/") {
		path = "/" + path
	}
	parameters := url.Values{
		"_foreign_keys": {"on"},
		"_busy_timeout": {strconv.FormatInt(timeout.Milliseconds(), 10)},
		"_cache_size":   {strconv.Itoa(-pageCacheKiB)},
	}
	if readOnly {
		parameters.Set("mode", "ro")
		parameters.Set("_query_only", "true")
	} else {
		parameters.Set("mode", "rwc")
		parameters.Set("_journal_mode", "WAL")
		parameters.Set("_synchronous", "FULL")
		parameters.Set("_txlock", "immediate")
	}
	return (&url.URL{Scheme: "file", Path: path, RawQuery: parameters.Encode()}).String()
}

func (database *Database) Ready(ctx context.Context) error {
	var format string
	if err := database.Reader.QueryRowContext(ctx, "SELECT value FROM app_metadata WHERE key='format'").Scan(&format); err != nil {
		return err
	}
	if format != "comicatlas-go-v1" {
		return errors.New("不支持的数据库格式")
	}
	return nil
}

func (database *Database) Close() error {
	var failures []error
	if database.Reader != nil {
		failures = append(failures, database.Reader.Close())
	}
	if database.Writer != nil {
		failures = append(failures, database.Writer.Close())
	}
	if database.lock != nil {
		failures = append(failures, database.lock.Unlock())
	}
	return errors.Join(failures...)
}

func migrate(ctx context.Context, writer *sql.DB) (result error) {
	// 启动期尚未接入读池或业务请求，在专用连接上按 SQLite 重建表协议执行。
	connection, err := writer.Conn(ctx)
	if err != nil {
		return err
	}
	defer connection.Close()
	if _, err = connection.ExecContext(ctx, "PRAGMA foreign_keys=OFF"); err != nil {
		return err
	}
	defer func() {
		restore, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_, err := connection.ExecContext(restore, "PRAGMA foreign_keys=ON")
		var enabled int
		if err == nil {
			err = connection.QueryRowContext(restore, "PRAGMA foreign_keys").Scan(&enabled)
		}
		if err == nil && enabled != 1 {
			err = errors.New("迁移后外键未恢复")
		}
		result = errors.Join(result, err)
	}()
	transaction, err := connection.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer transaction.Rollback()
	if _, err = transaction.ExecContext(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (
		name TEXT PRIMARY KEY NOT NULL, checksum TEXT NOT NULL, applied_at TEXT NOT NULL
	)`); err != nil {
		return err
	}
	entries, err := migrations.ReadDir("migrations")
	if err != nil {
		return err
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].Name() < entries[j].Name() })
	known := make(map[string]bool)
	applied := false
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".sql") {
			continue
		}
		known[entry.Name()] = true
		content, readErr := migrations.ReadFile("migrations/" + entry.Name())
		if readErr != nil {
			return readErr
		}
		checksum := fmt.Sprintf("%x", sha256.Sum256(content))
		var existing string
		err = transaction.QueryRowContext(ctx, "SELECT checksum FROM schema_migrations WHERE name=?", entry.Name()).Scan(&existing)
		if err == nil {
			if existing != checksum {
				return fmt.Errorf("已执行迁移校验不一致: %s", entry.Name())
			}
			continue
		}
		if !errors.Is(err, sql.ErrNoRows) {
			return err
		}
		if _, err = transaction.ExecContext(ctx, string(content)); err != nil {
			return fmt.Errorf("执行迁移 %s: %w", entry.Name(), err)
		}
		applied = true
		if _, err = transaction.ExecContext(ctx, "INSERT INTO schema_migrations VALUES (?, ?, ?)", entry.Name(), checksum, time.Now().UTC().Format(time.RFC3339Nano)); err != nil {
			return err
		}
	}
	rows, err := transaction.QueryContext(ctx, "SELECT name FROM schema_migrations")
	if err != nil {
		return err
	}
	for rows.Next() {
		var name string
		if err = rows.Scan(&name); err != nil {
			rows.Close()
			return err
		}
		if !known[name] {
			rows.Close()
			return fmt.Errorf("数据库包含当前程序不认识的迁移: %s", name)
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	// 仅实际升级时检查整库，普通重启不重复扫描全部业务记录。
	if applied {
		checks, err := transaction.QueryContext(ctx, "PRAGMA foreign_key_check")
		if err != nil {
			return err
		}
		violated := checks.Next()
		err = checks.Err()
		checks.Close()
		if err != nil {
			return err
		}
		if violated {
			return errors.New("迁移后外键完整性检查失败")
		}
	}
	return transaction.Commit()
}
