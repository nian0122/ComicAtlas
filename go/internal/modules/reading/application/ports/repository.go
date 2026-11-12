package ports

import (
	"context"

	readingdomain "comicatlas.local/backend/internal/modules/reading/domain"
)

// Repository 是业务层需要的持久化能力，不暴露连接或 SQL 事务。
type Repository interface {
	ManagedReactions(context.Context, readingdomain.ManagementQuery) (readingdomain.ManagedReactionPage, error)
	SetManagedReactions(context.Context, []readingdomain.ReactionChange) ([]readingdomain.Reaction, error)
	Reaction(ctx context.Context, kind, id string) (readingdomain.Reaction, error)
	SetReactions(ctx context.Context, changes []readingdomain.ReactionChange) ([]readingdomain.Reaction, error)
	Reactions(ctx context.Context, kind, value string, page, size int, oldest bool) (readingdomain.ReactionPage, error)
	History(ctx context.Context, page, size int) (readingdomain.HistoryPage, error)
}
