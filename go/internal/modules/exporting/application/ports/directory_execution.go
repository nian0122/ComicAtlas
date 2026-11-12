package ports

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
)

type DirectoryExecutionFiles interface {
	ExecuteDirectory(context.Context, string, exportdomain.DirectoryPlanningResult, func([]exportdomain.DirectoryFileResult) error) (exportdomain.DirectoryReport, error)
}

type DirectoryExecutionRepository interface {
	LoadDirectoryRun(context.Context, string) (exportdomain.DirectoryTaskRun, error)
	GuardDirectoryRetry(string) persistence.WriteAction
	StartDirectoryRun(context.Context, exportdomain.DirectoryTaskRun) error
	SaveDirectoryResults(context.Context, string, []exportdomain.DirectoryFileResult) error
	CompleteDirectoryRun(exportdomain.DirectoryTaskRun, *exportdomain.DirectoryReport) persistence.WriteAction
	DirectoryResults(context.Context, string, int, int) (exportdomain.DirectoryResultPage, error)
}
