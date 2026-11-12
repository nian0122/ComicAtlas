package application

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	maintenanceports "comicatlas.local/backend/internal/modules/maintenance/application/ports"
	maintenancedomain "comicatlas.local/backend/internal/modules/maintenance/domain"
	"context"
)

type Dispatcher struct {
	services CommandServices
}

// CommandServices 显式组装消费侧接口，避免随业务增加而扩展位置参数及 nil 占位。
type CommandServices struct {
	Purge     maintenanceports.Purger
	LQ        maintenanceports.LQ
	Metadata  maintenanceports.MetadataRefresher
	Transcode maintenanceports.Transcoder
	HQ        maintenanceports.HQReleaser
	Trash     maintenanceports.Trasher
	Restore   maintenanceports.Restorer
}

func NewDispatcher(services CommandServices) *Dispatcher {
	return &Dispatcher{services: services}
}
func (dispatcher *Dispatcher) SelectTrashTargets(ctx context.Context, kind string, selection lifecycledomain.TargetSelection) ([]lifecycledomain.SelectedTarget, error) {
	if dispatcher.services.Trash == nil {
		return nil, jobsdomain.ErrStopped
	}
	return dispatcher.services.Trash.SelectTrashTargets(ctx, kind, selection)
}
func (dispatcher *Dispatcher) SelectPurgeEntries(ctx context.Context, selection lifecycledomain.EntrySelection) ([]lifecycledomain.SelectedPurgeEntry, error) {
	if dispatcher.services.Purge == nil {
		return nil, jobsdomain.ErrStopped
	}
	return dispatcher.services.Purge.SelectPurgeEntries(ctx, selection)
}
func (dispatcher *Dispatcher) SelectEntries(ctx context.Context, selection lifecycledomain.EntrySelection) ([]lifecycledomain.SelectedEntry, error) {
	if dispatcher.services.Restore == nil {
		return nil, jobsdomain.ErrStopped
	}
	return dispatcher.services.Restore.SelectEntries(ctx, selection)
}
func (dispatcher *Dispatcher) Submit(ctx context.Context, request maintenancedomain.BatchOptions, item maintenancedomain.Item, key string, guard jobsports.Admission) (jobsdomain.Task, error) {
	switch request.Operation {
	case "chapter_trash", "media_trash":
		if dispatcher.services.Trash != nil {
			return dispatcher.services.Trash.TrashSnapshotGuarded(ctx, maintenancedomain.TrashTargetType(request.Operation), item.TargetID, item.TargetStamp, key, guard)
		}
	case "trash_purge":
		if dispatcher.services.Purge != nil && item.Purge != nil && item.CoveredBy == nil {
			return dispatcher.services.Purge.PurgeGuarded(ctx, item.EntryID, item.EntryStamp, item.Purge.RetentionDays, key, guard)
		}
	case "image_lq":
		if dispatcher.services.LQ != nil && request.LQ != nil {
			return dispatcher.services.LQ.SubmitGuarded(ctx, "comic", item.ComicID, *request.LQ, key, guard)
		}
	case "metadata_refresh":
		if dispatcher.services.Metadata != nil {
			return dispatcher.services.Metadata.SubmitGuarded(ctx, item.ComicID, key, guard)
		}
	case "video_transcode":
		if dispatcher.services.Transcode != nil {
			return dispatcher.services.Transcode.SubmitGuarded(ctx, "comic", item.ComicID, key, guard)
		}
	case "hq_release":
		if dispatcher.services.HQ != nil {
			return dispatcher.services.HQ.SubmitGuarded(ctx, "comic", item.ComicID, key, guard)
		}
	case "comic_trash":
		if dispatcher.services.Trash != nil {
			return dispatcher.services.Trash.TrashGuarded(ctx, "comic", item.ComicID, key, guard)
		}
	case "trash_restore":
		if dispatcher.services.Restore != nil {
			return dispatcher.services.Restore.RestoreGuarded(ctx, item.EntryID, item.EntryStamp, key, guard)
		}
	}
	return jobsdomain.Task{}, jobsdomain.ErrStopped
}
