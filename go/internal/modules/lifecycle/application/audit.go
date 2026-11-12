package application

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
	"time"
)

func (service *Service) Reconcile(ctx context.Context, entry, key string) (jobsdomain.Task, error) {
	if !lifecycledomain.ValidJournalID(entry) {
		return jobsdomain.Task{}, lifecycledomain.ErrValidation
	}
	snapshot, err := service.repository.AuditSnapshot(ctx, entry)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	for _, item := range snapshot.Entries {
		if item.Entry.State == "purged" {
			return jobsdomain.Task{}, lifecycledomain.ErrPurged
		}
		if item.Entry.State == "restored" {
			return jobsdomain.Task{}, lifecycledomain.ErrRestored
		}
	}
	return service.jobs.CreateResource(ctx, jobsdomain.ResourceTask{Type: "lifecycle_audit", TargetType: "trash_entry", TargetID: entry, ComicID: snapshot.Requested.Target.ComicID, Title: snapshot.Requested.Target.Title, Payload: "{}"}, key, service.repository.AuditAdmission(snapshot))
}

func (service *Service) RunAudit(parent context.Context, task jobsdomain.Task) (jobsports.Completion, error) {
	ctx, cancel := context.WithTimeout(parent, 2*time.Hour)
	defer cancel()
	completion, err := service.runAudit(ctx, task.ID)
	if err != nil {
		if parent.Err() != nil {
			return completion, parent.Err()
		}
		return completion, jobsdomain.NewFailure(jobsdomain.FailureLifecycleAudit, err)
	}
	return completion, nil
}

func (service *Service) runAudit(ctx context.Context, task string) (jobsports.Completion, error) {
	release, err := service.fence.AcquireFileMutation(ctx)
	if err != nil {
		return jobsports.Completion{}, err
	}
	defer release()
	snapshot, err := service.repository.BeginAudit(ctx, task)
	if err != nil {
		return jobsports.Completion{}, err
	}
	_, total, err := lifecycledomain.AuditStamp(snapshot)
	if err != nil {
		return jobsports.Completion{}, err
	}
	summary := lifecycledomain.AuditSummary{TaskID: task, EntryID: snapshot.Requested.ID, OwnerTaskID: snapshot.OwnerTaskID, Operation: snapshot.Operation, EntryState: snapshot.Requested.State, OwnerState: snapshot.OwnerState, TotalFiles: total, Consistent: !snapshot.Superseded, Superseded: snapshot.Superseded}
	if err = service.jobs.UpdateProgress(ctx, task, "auditing", total, 0); err != nil {
		return jobsports.Completion{}, err
	}
	batch := []lifecycledomain.FileObservation{}
	flush := func() error {
		if len(batch) == 0 {
			return nil
		}
		if err := service.repository.RecordAudit(ctx, task, batch); err != nil {
			return err
		}
		batch = batch[:0]
		return service.jobs.UpdateProgress(ctx, task, "auditing", total, summary.CheckedFiles)
	}
	resumable := !snapshot.Superseded
	for _, entry := range snapshot.Entries {
		journal, err := service.files.Audit(ctx, entry.Entry.ID, entry.Candidates, func(item lifecycledomain.FileObservation) error {
			item.Position = summary.CheckedFiles + 1
			item = lifecycledomain.EvaluateFileObservation(item, entry.Entry.State)
			summary.CheckedFiles++
			if !item.Consistent {
				summary.IssueFiles++
				summary.Consistent = false
			}
			if !item.Resumable {
				resumable = false
			}
			batch = append(batch, item)
			if len(batch) >= 128 {
				return flush()
			}
			return nil
		})
		if err != nil {
			return jobsports.Completion{}, err
		}
		consistent, canResume := lifecycledomain.AuditEntryState(entry, journal)
		if !consistent {
			summary.EntryIssues++
			summary.Consistent = false
		}
		if !canResume {
			resumable = false
		}
	}
	if err = flush(); err != nil {
		return jobsports.Completion{}, err
	}
	now := time.Now().UTC().Format(jobsdomain.TimestampFormat)
	summary.Complete = true
	summary.ObservedAt = &now
	summary.RepairAvailable = lifecycledomain.Repairable(summary, resumable)
	if err = service.jobs.UpdateProgress(ctx, task, "publishing", total, total); err != nil {
		return jobsports.Completion{}, err
	}
	return jobsports.Completion{Persist: service.repository.AuditCompletion(task, summary)}, nil
}

func (service *Service) AuditReport(ctx context.Context, entry string) (lifecycledomain.AuditSummary, error) {
	if !lifecycledomain.ValidJournalID(entry) {
		return lifecycledomain.AuditSummary{}, lifecycledomain.ErrValidation
	}
	return service.repository.AuditSummary(ctx, entry)
}

func (service *Service) AuditItems(ctx context.Context, task string, page, size int) (lifecycledomain.AuditPage, error) {
	if !lifecycledomain.ValidJournalID(task) || page < 1 || page > 1000000 || size < 1 || size > 100 {
		return lifecycledomain.AuditPage{}, lifecycledomain.ErrValidation
	}
	return service.repository.AuditItems(ctx, task, page, size)
}

func (service *Service) Repair(ctx context.Context, entry string) (jobsdomain.Task, error) {
	summary, err := service.AuditReport(ctx, entry)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	if !summary.Complete || !summary.RepairAvailable {
		return jobsdomain.Task{}, lifecycledomain.ErrConflict
	}
	if err = service.jobs.RetryGuarded(ctx, summary.OwnerTaskID, service.repository.RepairGuard(summary.TaskID)); err != nil {
		return jobsdomain.Task{}, err
	}
	return service.jobs.Get(ctx, summary.OwnerTaskID)
}
