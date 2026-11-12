package application

import (
	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	maintenanceports "comicatlas.local/backend/internal/modules/maintenance/application/ports"
	maintenancedomain "comicatlas.local/backend/internal/modules/maintenance/domain"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"time"
)

type Service struct {
	repository  maintenanceports.Repository
	library     maintenanceports.Library
	jobs        *jobsapp.Store
	commands    maintenanceports.Commands
	commandWake chan struct{}
}

func New(repo maintenanceports.Repository, library maintenanceports.Library, jobs *jobsapp.Store, commands maintenanceports.Commands) *Service {
	return &Service{repository: repo, library: library, jobs: jobs, commands: commands, commandWake: make(chan struct{}, 1)}
}
func (service *Service) Preview(ctx context.Context, request maintenancedomain.Request) (maintenancedomain.Preview, error) {
	request, err := maintenancedomain.NormalizeRequest(request)
	if err != nil {
		return maintenancedomain.Preview{}, err
	}
	if maintenancedomain.IsCommand(request.Operation) && service.commands == nil {
		return maintenancedomain.Preview{}, jobsdomain.ErrStopped
	}
	if request.Operation == "metadata_update" {
		if err = service.library.CheckPatchReferences(ctx, request.Patch); err != nil {
			return maintenancedomain.Preview{}, err
		}
	}
	targets, err := service.previewTargets(ctx, request)
	if err != nil {
		return maintenancedomain.Preview{}, err
	}
	var token [16]byte
	if _, err = rand.Read(token[:]); err != nil {
		return maintenancedomain.Preview{}, err
	}
	data, err := json.Marshal(request)
	if err != nil {
		return maintenancedomain.Preview{}, err
	}
	confirmation, hash := "", ""
	if maintenancedomain.RequiresConfirmation(request.Operation) {
		var value [32]byte
		if _, err = rand.Read(value[:]); err != nil {
			return maintenancedomain.Preview{}, err
		}
		confirmation = hex.EncodeToString(value[:])
		hash, err = maintenancedomain.ConfirmationHash(confirmation)
		if err != nil {
			return maintenancedomain.Preview{}, err
		}
	}
	preview, err := service.repository.CreatePreview(ctx, "preview_"+hex.EncodeToString(token[:]), string(data), hash, targets, time.Now().UTC())
	preview.ConfirmationToken = confirmation
	return preview, err
}
func (service *Service) previewTargets(ctx context.Context, request maintenancedomain.Request) ([]maintenancedomain.Item, error) {
	if maintenancedomain.UsesTargetSelection(request.Operation) {
		targets, err := service.commands.SelectTrashTargets(ctx, maintenancedomain.TrashTargetType(request.Operation), *request.TargetSelection)
		if err != nil {
			return nil, err
		}
		return maintenancedomain.TrashItems(targets), nil
	}
	if request.Operation == "trash_purge" {
		entries, err := service.commands.SelectPurgeEntries(ctx, *request.TrashSelection)
		if err != nil {
			return nil, err
		}
		return maintenancedomain.PurgeItems(entries), nil
	}
	if request.Operation == "trash_restore" {
		entries, err := service.commands.SelectEntries(ctx, *request.TrashSelection)
		if err != nil {
			return nil, err
		}
		return maintenancedomain.RestoreItems(entries), nil
	}
	selectComics := service.library.SelectComics
	if request.Operation == "hq_release" {
		selectComics = service.library.SelectHQComics
	}
	comics, err := selectComics(ctx, request.Selection)
	if err != nil {
		return nil, err
	}
	items := make([]maintenancedomain.Item, 0, len(comics))
	for position, comic := range comics {
		items = append(items, maintenancedomain.InitialItem(position+1, comic, request.Operation))
	}
	return items, nil
}
func (service *Service) Get(ctx context.Context, id string) (maintenancedomain.Preview, error) {
	if id == "" || len(id) > 128 {
		return maintenancedomain.Preview{}, librarydomain.ErrValidation
	}
	value, err := service.repository.Preview(ctx, id)
	return value.Preview, err
}
func (service *Service) Items(ctx context.Context, id string, page, size int) (maintenancedomain.ItemPage, error) {
	if id == "" || len(id) > 128 || !librarydomain.ValidPage(page, size) {
		return maintenancedomain.ItemPage{}, librarydomain.ErrValidation
	}
	return service.repository.Items(ctx, id, page, size)
}
func (service *Service) Confirm(ctx context.Context, id, key string, confirmation maintenancedomain.Confirmation) (jobsdomain.Task, error) {
	if !confirmation.Confirm || id == "" || len(id) > 128 {
		return jobsdomain.Task{}, librarydomain.ErrValidation
	}
	record, err := service.repository.Preview(ctx, id)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	hash := ""
	if confirmation.Token != "" {
		hash, err = maintenancedomain.ConfirmationHash(confirmation.Token)
		if err != nil {
			return jobsdomain.Task{}, err
		}
	}
	if err = maintenancedomain.ValidateDangerousConfirmation(record.Preview.Operation, record.ConfirmationHash, hash); err != nil {
		return jobsdomain.Task{}, err
	}
	task, err := service.jobs.CreateResource(ctx, maintenancedomain.BatchResource(record.Preview.Operation, id), key, service.repository.Admission(id, hash))
	if err == nil {
		service.WakeCommands()
	}
	return task, err
}
func (service *Service) WakeCommands() {
	select {
	case service.commandWake <- struct{}{}:
	default:
	}
}
func (service *Service) RetryCommand(ctx context.Context, id string) error {
	err := service.jobs.RetryGuarded(ctx, id, service.repository.CommandRetry(id))
	if err == nil {
		service.WakeCommands()
	}
	return err
}
func (service *Service) Run(ctx context.Context, task jobsdomain.Task) (completion jobsports.Completion, err error) {
	defer func() {
		if err != nil && ctx.Err() == nil {
			err = jobsdomain.NewFailure(jobsdomain.FailureMetadataBatch, err)
		}
	}()
	record, err := service.repository.Preview(ctx, task.TargetID)
	if err != nil {
		return completion, err
	}
	if record.Preview.TaskID == nil || *record.Preview.TaskID != task.ID {
		return completion, jobsdomain.ErrConflict
	}
	if task.Type != "metadata_batch" || record.Preview.Operation != "metadata_update" {
		return completion, jobsdomain.ErrValidation
	}
	var request maintenancedomain.BatchOptions
	if err = json.Unmarshal([]byte(record.OptionsJSON), &request); err != nil {
		return completion, err
	}
	request, err = maintenancedomain.NormalizeOptions(request)
	if err != nil {
		return completion, err
	}
	completed := record.Preview.SelectedCount - record.Preview.PendingCount
	if err = service.jobs.UpdateProgress(ctx, task.ID, "processing", record.Preview.SelectedCount, completed); err != nil {
		return completion, err
	}
	for {
		if err = ctx.Err(); err != nil {
			return completion, err
		}
		item, nextErr := service.repository.Next(ctx, task.ID)
		if errors.Is(nextErr, jobsdomain.ErrNotFound) {
			break
		}
		if nextErr != nil {
			return completion, nextErr
		}
		if err = service.repository.Apply(ctx, task.ID, item, service.library.MetadataPatchWriter(item.ComicID, item.Version, request.Patch)); err != nil {
			return completion, err
		}
	}
	return jobsports.Completion{Persist: service.repository.Completion(task.ID)}, nil
}
