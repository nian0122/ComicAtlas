package sqlite

import (
	"context"
	"database/sql"
	"errors"
	"net/url"
	"os"
	"path/filepath"
	"strings"

	"comicatlas.local/backend/internal/modules/backup/application/ports"
	"comicatlas.local/backend/internal/modules/backup/domain"
	_ "github.com/mattn/go-sqlite3"
)

const entriesDDL = "CREATE TABLE backup_entries(path TEXT PRIMARY KEY NOT NULL COLLATE BINARY,kind TEXT NOT NULL,size INTEGER NOT NULL,sha256 TEXT NOT NULL,modified_at TEXT NOT NULL) WITHOUT ROWID"
const infoDDL = "CREATE TABLE backup_info(format TEXT NOT NULL,version INTEGER NOT NULL)"

type Catalogs struct{}

type catalogWriter struct {
	database *sql.DB
	filename string
	summary  domain.Summary
	closed   bool
	closeErr error
}

type catalogReader struct{ database *sql.DB }

var _ ports.Catalogs = Catalogs{}

func (Catalogs) Create(ctx context.Context, filename string) (ports.CatalogWriter, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if err := catalogFiles(filename, true); err != nil {
		return nil, err
	}
	file, err := os.OpenFile(filename, os.O_CREATE|os.O_EXCL|os.O_RDWR, 0o600)
	if err != nil {
		if errors.Is(err, os.ErrExist) {
			return nil, domain.ErrExists
		}
		return nil, domain.ErrIO
	}
	if err := file.Close(); err != nil {
		os.Remove(filename)
		return nil, domain.ErrIO
	}
	database, err := catalogDatabase(ctx, filename, false)
	if err != nil {
		os.Remove(filename)
		return nil, err
	}
	for _, statement := range []string{entriesDDL, infoDDL} {
		if _, err = database.ExecContext(ctx, statement); err != nil {
			database.Close()
			os.Remove(filename)
			return nil, catalogError(ctx, false)
		}
	}
	if _, err = database.ExecContext(ctx, "INSERT INTO backup_info VALUES(?,?)", domain.IndexFormat, domain.Version); err != nil {
		database.Close()
		os.Remove(filename)
		return nil, catalogError(ctx, false)
	}
	return &catalogWriter{database: database, filename: filename}, nil
}

func (Catalogs) Open(ctx context.Context, filename string) (ports.CatalogReader, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if err := catalogFiles(filename, false); err != nil {
		return nil, err
	}
	database, err := catalogDatabase(ctx, filename, true)
	if err != nil {
		return nil, err
	}
	reader := &catalogReader{database: database}
	if err = inspectCatalog(ctx, database); err == nil {
		err = catalogRoots(ctx, reader)
	}
	if err == nil {
		_, err = reader.Summary(ctx)
	}
	if err != nil {
		database.Close()
		return nil, err
	}
	return reader, nil
}

func catalogFiles(filename string, creating bool) error {
	for _, suffix := range []string{"", "-journal", "-wal", "-shm"} {
		info, err := os.Lstat(filename + suffix)
		if creating {
			if err == nil {
				return domain.ErrExists
			}
			if !errors.Is(err, os.ErrNotExist) {
				return domain.ErrIO
			}
		} else if suffix == "" {
			if err != nil || !info.Mode().IsRegular() {
				return domain.ErrInvalid
			}
		} else if !errors.Is(err, os.ErrNotExist) {
			// 已发布的索引必须独立，不能通过旁路 WAL 或恢复日志改变其内容。
			return domain.ErrInvalid
		}
	}
	return nil
}

func catalogDatabase(ctx context.Context, filename string, readOnly bool) (*sql.DB, error) {
	path, err := filepath.Abs(filename)
	if err != nil {
		return nil, domain.ErrValidation
	}
	path = filepath.ToSlash(path)
	if !strings.HasPrefix(path, "/") {
		path = "/" + path
	}
	parameters := url.Values{"mode": {"rw"}, "_busy_timeout": {"100"}, "_cache_size": {"-2048"}}
	if readOnly {
		parameters.Set("mode", "ro")
		parameters.Set("_query_only", "true")
	} else {
		// 私有暂存索引无共享写入；最终 Seal 后同步，发布前失败由工作区整体清理。
		parameters.Set("_journal_mode", "OFF")
		parameters.Set("_synchronous", "OFF")
	}
	uri := (&url.URL{Scheme: "file", Path: path, RawQuery: parameters.Encode()}).String()
	database, err := sql.Open("sqlite3", uri)
	if err != nil {
		return nil, catalogError(ctx, readOnly)
	}
	database.SetMaxOpenConns(1)
	database.SetMaxIdleConns(1)
	if err = database.PingContext(ctx); err != nil {
		database.Close()
		return nil, catalogError(ctx, readOnly)
	}
	return database, nil
}

func catalogError(ctx context.Context, reading bool) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if reading {
		return domain.ErrInvalid
	}
	return domain.ErrIO
}
