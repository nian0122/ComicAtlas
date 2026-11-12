package ports

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"comicatlas.local/backend/internal/platform/persistence"
	"context"
	"time"
)

type Repository interface {
	Target(context.Context, string, string) (lifecycledomain.Target, error)
	Entry(context.Context, string) (lifecycledomain.Entry, error)
	Entries(context.Context, lifecycledomain.EntryQuery) (lifecycledomain.EntryPage, error)
	SelectEntries(context.Context, lifecycledomain.EntrySelection) ([]lifecycledomain.SelectedEntry, error)
	SelectTrashTargets(context.Context, string, lifecycledomain.TargetSelection) ([]lifecycledomain.SelectedTarget, error)
	TrashTarget(context.Context, string, string, string) (lifecycledomain.Target, error)
	SelectPurgeEntries(context.Context, lifecycledomain.EntrySelection, int, time.Time) ([]lifecycledomain.SelectedPurgeEntry, error)
	PurgeTarget(context.Context, string, string, int) (lifecycledomain.Target, error)
	RestoreTarget(context.Context, string, string) (lifecycledomain.Target, error)
	CoverEntries(context.Context, []string) (map[string]bool, error)
	ActiveComicCovers(context.Context, []string) (map[string]bool, error)
	Admission(lifecycledomain.Target, lifecycledomain.Request, string) jobsports.Admission
	Run(context.Context, string) (lifecycledomain.Run, error)
	BeginTrashMove(context.Context, string) error
	FailTrashPreparation(string) persistence.WriteAction
	Completion(string) persistence.WriteAction
	PurgeScope(context.Context, string) (lifecycledomain.PurgeScope, error)
	BeginPurge(context.Context, string) error
	FailPurgePreflight(string) persistence.WriteAction
	ConfirmPurge(context.Context, lifecycledomain.PurgeScope, int, string, string) error
	PurgeAdmission(lifecycledomain.Request, int) jobsports.Admission
	PurgeSnapshotAdmission(lifecycledomain.Request, int) jobsports.Admission
	AuditSnapshot(context.Context, string) (lifecycledomain.AuditSnapshot, error)
	AuditAdmission(lifecycledomain.AuditSnapshot) jobsports.Admission
	BeginAudit(context.Context, string) (lifecycledomain.AuditSnapshot, error)
	RecordAudit(context.Context, string, []lifecycledomain.FileObservation) error
	AuditCompletion(string, lifecycledomain.AuditSummary) persistence.WriteAction
	AuditSummary(context.Context, string) (lifecycledomain.AuditSummary, error)
	AuditItems(context.Context, string, int, int) (lifecycledomain.AuditPage, error)
	RepairGuard(string) persistence.WriteAction
	BeginFileResults(context.Context, string, int, string, int) error
	RecordFileResults(context.Context, string, int, []lifecycledomain.FileResult) error
	FileResults(context.Context, string, int, int) (lifecycledomain.FileResultPage, error)
}

type Files interface {
	Prepare(context.Context, string, lifecycledomain.Target, []lifecycledomain.FileCandidate, func(lifecycledomain.FileResult) error) (lifecycledomain.FileJournal, error)
	Trash(context.Context, string, func(lifecycledomain.FileResult) error) error
	Restore(context.Context, string, func(lifecycledomain.FileResult) error) error
	Journal(context.Context, string) (lifecycledomain.FileJournal, error)
	RemoveJournal(context.Context, string) error
	Purge(context.Context, string) error
	RemoveDirectories(context.Context, []string) error
	Audit(context.Context, string, []lifecycledomain.FileCandidate, func(lifecycledomain.FileObservation) error) (lifecycledomain.JournalObservation, error)
}

type FileFence interface {
	AcquireFileMutation(context.Context) (func(), error)
}
