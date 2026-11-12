package application

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"context"
)

func (service *Service) MoveStructure(ctx context.Context, comicID string, change librarydomain.StructureMove) (librarydomain.StructureResult, error) {
	return service.mutateStructure(ctx, comicID, change.Version, func(tree *librarydomain.StructureTree) (*librarydomain.TreeNode, error) {
		return nil, tree.MoveGroup(change.ParentID, change.Items)
	})
}
