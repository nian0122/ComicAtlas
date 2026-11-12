package application

import (
	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	lifecycleports "comicatlas.local/backend/internal/modules/lifecycle/application/ports"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
	"encoding/json"
	"errors"
	"time"
)

type Service struct {
	repository    lifecycleports.Repository
	files         lifecycleports.Files
	jobs          *jobsapp.Store
	fence         lifecycleports.FileFence
	retentionDays int
}

func New(repository lifecycleports.Repository, files lifecycleports.Files, jobs *jobsapp.Store, fence lifecycleports.FileFence, retentionDays int) *Service {
	return &Service{repository: repository, files: files, jobs: jobs, fence: fence, retentionDays: retentionDays}
}

func (service *Service) Trash(ctx context.Context, kind, id, key string) (jobsdomain.Task, error) {
	return service.TrashGuarded(ctx, kind, id, key, nil)
}
func (service *Service) TrashGuarded(ctx context.Context, kind, id, key string, guard jobsports.Admission) (jobsdomain.Task, error) {
	if err := lifecycledomain.ValidateTarget(kind, id); err != nil {
		return jobsdomain.Task{}, err
	}
	target, err := service.repository.Target(ctx, kind, id)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	return service.submit(ctx, target, lifecycledomain.Request{Operation: "trash"}, key, guard, "")
}

func (service *Service) Restore(ctx context.Context, id, key string) (jobsdomain.Task, error) {
	return service.restore(ctx, id, key, "", nil)
}
func (service *Service) RestoreGuarded(ctx context.Context, id, stamp, key string, guard jobsports.Admission) (jobsdomain.Task, error) {
	if !lifecycledomain.ValidRestoreStamp(stamp) {
		return jobsdomain.Task{}, lifecycledomain.ErrValidation
	}
	return service.restore(ctx, id, key, stamp, guard)
}
func (service *Service) restore(ctx context.Context, id, key, stamp string, guard jobsports.Admission) (jobsdomain.Task, error) {
	request := lifecycledomain.Request{Operation: "restore", EntryID: id}
	if err := lifecycledomain.ValidateRequest(request); err != nil {
		return jobsdomain.Task{}, err
	}
	if stamp != "" {
		target, err := service.repository.RestoreTarget(ctx, id, stamp)
		if err != nil {
			return jobsdomain.Task{}, err
		}
		return service.submit(ctx, target, request, key, guard, stamp)
	}
	entry, err := service.repository.Entry(ctx, id)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	return service.submit(ctx, entry.Target, request, key, guard, stamp)
}

func (service *Service) submit(ctx context.Context, target lifecycledomain.Target, request lifecycledomain.Request, key string, guard jobsports.Admission, stamp string) (jobsdomain.Task, error) {
	data, err := json.Marshal(request)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	return service.jobs.CreateResource(ctx, jobsdomain.ResourceTask{Type: "lifecycle", TargetType: target.Type, TargetID: target.ID, ComicID: target.ComicID, Title: target.Title, Payload: string(data)}, key, jobsports.CombineAdmissions(guard, service.repository.Admission(target, request, stamp)))
}

func (service *Service) Run(ctx context.Context, task jobsdomain.Task) (jobsports.Completion, error) {
	run, cancel := context.WithTimeout(ctx, 2*time.Hour)
	defer cancel()
	release, err := service.fence.AcquireFileMutation(run)
	if err != nil {
		return jobsports.Completion{}, err
	}
	defer release()
	value, err := service.repository.Run(run, task.ID)
	if value.Operation == "purge" && value.Entry.State == "trashed" {
		if err == nil {
			err = service.checkPurgeJournals(run, value.PurgeItems, false)
		}
		if err == nil {
			err = service.repository.BeginPurge(run, task.ID)
		}
		if err != nil {
			return jobsports.Completion{PersistFailure: service.repository.FailPurgePreflight(task.ID)}, jobsdomain.NewFailure(jobsdomain.FailurePurgePreflight, err)
		}
	} else if err == nil && value.Operation == "purge" {
		err = service.checkPurgeJournals(run, value.PurgeItems, true)
	}
	if err != nil {
		return jobsports.Completion{}, jobsdomain.NewFailure(jobsdomain.FailureLifecycle, err)
	}
	total := len(value.Candidates)
	var recorder *resultRecorder
	if value.Operation != "purge" {
		if err = service.repository.BeginFileResults(run, task.ID, task.Attempt, value.Operation, total); err != nil {
			return jobsports.Completion{}, err
		}
		recorder = &resultRecorder{ctx: run, repository: service.repository, task: task.ID, attempt: task.Attempt}
	}
	returnToLibrary := false
	if value.Operation == "trash" {
		_, err = service.files.Prepare(run, value.Entry.ID, value.Entry.Target, value.Candidates, recorder.record)
		if value.Entry.Target.Type == "comic" && !value.TrashMoveStarted {
			returnToLibrary = err != nil
			if err == nil {
				err = service.repository.BeginTrashMove(run, task.ID)
			}
		}
		if err == nil {
			err = service.files.Trash(run, value.Entry.ID, recorder.record)
		}
	} else if value.Operation == "purge" {
		err = service.runPurge(run, value.PurgeItems)
	} else {
		var journal lifecycledomain.FileJournal
		journal, err = service.files.Journal(run, value.Entry.ID)
		if err == nil {
			err = lifecycledomain.MatchJournalCandidates(journal, value.Candidates)
		}
		if err == nil {
			err = lifecycledomain.MatchTrashDirectories(journal, value.Entry.Target)
		}
		if err == nil {
			err = service.files.Restore(run, value.Entry.ID, recorder.record)
		}
	}
	if recorder != nil {
		err = errors.Join(err, recorder.flush())
	}
	if err != nil {
		if ctx.Err() != nil {
			return jobsports.Completion{}, ctx.Err()
		}
		if returnToLibrary {
			return jobsports.Completion{PersistFailure: service.repository.FailTrashPreparation(task.ID)}, jobsdomain.NewFailure(jobsdomain.FailureTrashPreparation, err)
		}
		return jobsports.Completion{}, jobsdomain.NewFailure(jobsdomain.FailureLifecycle, err)
	}
	completion := jobsports.Completion{Persist: service.repository.Completion(task.ID)}
	if value.Operation == "purge" {
		entries := make([]string, 0, len(value.PurgeItems))
		for _, item := range value.PurgeItems {
			entries = append(entries, item.Entry.ID)
		}
		completion.AfterCommit = func(parent context.Context) error {
			cleanup, cancel := context.WithTimeout(context.WithoutCancel(parent), 30*time.Second)
			defer cancel()
			release, err := service.fence.AcquireFileMutation(cleanup)
			if err != nil {
				return err
			}
			defer release()
			failures := make([]error, 0)
			for _, id := range entries {
				if err = service.files.RemoveJournal(cleanup, id); err != nil {
					failures = append(failures, err)
				}
			}
			return errors.Join(failures...)
		}
	}
	if value.Operation == "restore" {
		entryID := value.Entry.ID
		completion.AfterCommit = func(parent context.Context) error {
			cleanup, cancel := context.WithTimeout(context.WithoutCancel(parent), 30*time.Second)
			defer cancel()
			release, err := service.fence.AcquireFileMutation(cleanup)
			if err != nil {
				return err
			}
			defer release()
			return service.files.RemoveJournal(cleanup, entryID)
		}
	}
	return completion, nil
}

func (service *Service) Entry(ctx context.Context, id string) (lifecycledomain.Entry, error) {
	if !lifecycledomain.ValidJournalID(id) {
		return lifecycledomain.Entry{}, lifecycledomain.ErrValidation
	}
	entry, err := service.repository.Entry(ctx, id)
	if err != nil {
		return entry, err
	}
	items := []lifecycledomain.Entry{entry}
	err = service.decorateCovers(ctx, items)
	return items[0], err
}

func (service *Service) Entries(ctx context.Context, query lifecycledomain.EntryQuery) (lifecycledomain.EntryPage, error) {
	if err := lifecycledomain.ValidateEntryQuery(query); err != nil {
		return lifecycledomain.EntryPage{}, err
	}
	page, err := service.repository.Entries(ctx, query)
	if err != nil {
		return page, err
	}
	err = service.decorateCovers(ctx, page.Items)
	return page, err
}
