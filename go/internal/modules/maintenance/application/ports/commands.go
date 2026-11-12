package ports

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	maintenancedomain "comicatlas.local/backend/internal/modules/maintenance/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"time"
)

type LQ interface {
	SubmitGuarded(context.Context, string, string, mediadomain.LQRequest, string, jobsports.Admission) (jobsdomain.Task, error)
}
type MetadataRefresher interface {
	SubmitGuarded(context.Context, string, string, jobsports.Admission) (jobsdomain.Task, error)
}
type Transcoder interface {
	SubmitGuarded(context.Context, string, string, string, jobsports.Admission) (jobsdomain.Task, error)
}
type HQReleaser interface {
	SubmitGuarded(context.Context, string, string, string, jobsports.Admission) (jobsdomain.Task, error)
}
type Trasher interface {
	SelectTrashTargets(context.Context, string, lifecycledomain.TargetSelection) ([]lifecycledomain.SelectedTarget, error)
	TrashSnapshotGuarded(context.Context, string, string, string, string, jobsports.Admission) (jobsdomain.Task, error)
	TrashGuarded(context.Context, string, string, string, jobsports.Admission) (jobsdomain.Task, error)
}
type Restorer interface {
	SelectEntries(context.Context, lifecycledomain.EntrySelection) ([]lifecycledomain.SelectedEntry, error)
	RestoreGuarded(context.Context, string, string, string, jobsports.Admission) (jobsdomain.Task, error)
}
type Commands interface {
	SelectTrashTargets(context.Context, string, lifecycledomain.TargetSelection) ([]lifecycledomain.SelectedTarget, error)
	SelectPurgeEntries(context.Context, lifecycledomain.EntrySelection) ([]lifecycledomain.SelectedPurgeEntry, error)
	SelectEntries(context.Context, lifecycledomain.EntrySelection) ([]lifecycledomain.SelectedEntry, error)
	Submit(context.Context, maintenancedomain.BatchOptions, maintenancedomain.Item, string, jobsports.Admission) (jobsdomain.Task, error)
}
type Purger interface {
	SelectPurgeEntries(context.Context, lifecycledomain.EntrySelection) ([]lifecycledomain.SelectedPurgeEntry, error)
	PurgeGuarded(context.Context, string, string, int, string, jobsports.Admission) (jobsdomain.Task, error)
}
type CommandRepository interface {
	CommandParents(context.Context) ([]string, error)
	StartCommand(context.Context, string) (maintenancedomain.CommandParent, error)
	ReconcileCommand(context.Context, string) (bool, error)
	PendingCommand(context.Context, string, time.Time) (maintenancedomain.Item, error)
	CommandPrerequisites(context.Context, string, maintenancedomain.Item) error
	ActiveChildren(context.Context, string) ([]string, error)
	ChildAdmission(string, maintenancedomain.Item, string) jobsports.Admission
	DispatchResult(context.Context, string, maintenancedomain.Item, string, bool) error
	FailCommand(context.Context, string) error
	CommandRetry(string) persistence.WriteAction
}
