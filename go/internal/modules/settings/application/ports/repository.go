package ports

import (
	"context"

	settingsdomain "comicatlas.local/backend/internal/modules/settings/domain"
)

// Repository 是业务层需要的持久化能力，不暴露连接或 SQL 事务。
type Repository interface {
	Reader(ctx context.Context) (settingsdomain.Reader, error)
	SaveReader(ctx context.Context, value settingsdomain.Reader) (settingsdomain.Reader, error)
}
