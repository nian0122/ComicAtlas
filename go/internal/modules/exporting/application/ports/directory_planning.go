package ports

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"context"
)

type DirectoryPlanningFiles interface {
	ResolveDirectoryTrash(context.Context, []exportdomain.DirectoryInventorySnapshot) ([]exportdomain.DirectoryTrashResolution, error)
	InspectDirectory(context.Context, string, []exportdomain.DirectoryPlan) (exportdomain.DirectoryInspection, error)
}
