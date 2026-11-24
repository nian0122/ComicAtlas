package ports

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
)

type DirectoryFileFence interface {
	AcquireFileMutation(context.Context) (func(), error)
}

type DirectoryExecutionFiles interface {
	ExecuteDirectory(context.Context, string, exportdomain.DirectoryPlanningResult, func([]exportdomain.DirectoryFileResult) error) (exportdomain.DirectoryReport, error)
}

type DirectoryMoveRepository interface {
	AdmitDirectoryMove(exportdomain.DirectoryMoveRequest) jobsports.Admission
	LoadDirectoryMoveRequest(context.Context, string, string) (exportdomain.DirectoryMoveRequest, error)
	LoadDirectoryTaskRun(context.Context, string) (exportdomain.DirectoryTaskRun, error)
	SaveDirectoryMovePlan(context.Context, string, exportdomain.DirectoryMoveRequest, exportdomain.DirectoryPlanningResult) (exportdomain.DirectoryTaskRun, error)
	GuardDirectoryRetry(string) persistence.WriteAction
	StartDirectoryRun(context.Context, exportdomain.DirectoryTaskRun) error
	SaveDirectoryResults(context.Context, string, []exportdomain.DirectoryFileResult) error
	CompleteDirectoryRun(exportdomain.DirectoryTaskRun, *exportdomain.DirectoryReport) persistence.WriteAction
	DirectoryResults(context.Context, string, int, int) (exportdomain.DirectoryResultPage, error)
}
