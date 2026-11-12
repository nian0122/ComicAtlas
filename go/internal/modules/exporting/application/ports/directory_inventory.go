package ports

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"context"
)

type DirectoryInventoryRepository interface {
	DirectoryInventories(context.Context, []string) ([]exportdomain.DirectoryInventorySnapshot, error)
}
