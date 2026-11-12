package sqlite

import (
	recoveryports "comicatlas.local/backend/internal/modules/recovery/application/ports"
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"database/sql"
)

type sqliteRepository struct{ database *persistence.Database }

func New(database *persistence.Database) *sqliteRepository {
	return &sqliteRepository{database: database}
}

var _ recoveryports.Records = (*sqliteRepository)(nil)

type rowReader interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}

func hasComic(ctx context.Context, query rowReader, comic string) (bool, error) {
	if err := ctx.Err(); err != nil {
		return false, err
	}
	if !recoverydomain.Key(comic) {
		return false, recoverydomain.ErrValidation
	}
	var exists bool
	// 恢复去重必须覆盖隐藏和 moving_out；只按精确主键返回存在性。
	err := query.QueryRowContext(ctx, "SELECT EXISTS(SELECT 1 FROM comics WHERE id=?)", comic).Scan(&exists)
	return exists, err
}

func (repo *sqliteRepository) HasComic(ctx context.Context, comic string) (bool, error) {
	return hasComic(ctx, repo.database.Reader, comic)
}

// GuardAbsent 与未来恢复写入组合在同一写事务中，读阶段不存在不能替代它。
func (repo *sqliteRepository) GuardAbsent(comic string) persistence.WriteAction {
	return func(ctx context.Context, tx *sql.Tx) error {
		exists, err := hasComic(ctx, tx, comic)
		if err != nil {
			return err
		}
		if exists {
			return recoverydomain.ErrExists
		}
		return nil
	}
}
