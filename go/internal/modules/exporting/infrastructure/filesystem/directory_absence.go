package filesystem

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"context"
)

func (files *Files) verifyDirectoryAbsences(ctx context.Context, plans []exportdomain.DirectoryPlan) error {
	for _, plan := range plans {
		for _, source := range plan.AbsentSources {
			if err := ctx.Err(); err != nil {
				return err
			}
			info, err := files.directoryEntry(source)
			if err != nil {
				return err
			}
			if info != nil {
				return exportdomain.ErrConflict
			}
		}
	}
	return nil
}
