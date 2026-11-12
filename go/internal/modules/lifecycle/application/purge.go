package application

import (
	"sort"

	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"time"
)

func (service *Service) SelectPurgeEntries(ctx context.Context, selection lifecycledomain.EntrySelection) ([]lifecycledomain.SelectedPurgeEntry, error) {
	value, err := lifecycledomain.NormalizeEntrySelection(selection)
	if err != nil {
		return nil, err
	}
	return service.repository.SelectPurgeEntries(ctx, value, service.retentionDays, time.Now().UTC())
}
func (service *Service) PurgeGuarded(ctx context.Context, id, stamp string, days int, key string, guard jobsports.Admission) (jobsdomain.Task, error) {
	if !lifecycledomain.ValidJournalID(id) || !lifecycledomain.ValidRestoreStamp(stamp) || guard == nil {
		return jobsdomain.Task{}, lifecycledomain.ErrValidation
	}
	if days != service.retentionDays {
		return jobsdomain.Task{}, lifecycledomain.ErrScopeChanged
	}
	target, err := service.repository.PurgeTarget(ctx, id, stamp, days)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	request := lifecycledomain.Request{Operation: "purge", EntryID: id, Confirmation: stamp}
	data, err := json.Marshal(request)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	return service.jobs.CreateResource(ctx, jobsdomain.ResourceTask{Type: "lifecycle", TargetType: target.Type, TargetID: target.ID, ComicID: target.ComicID, Title: target.Title, Payload: string(data)}, key, jobsports.CombineAdmissions(guard, service.repository.PurgeSnapshotAdmission(request, days)))
}

func (service *Service) PreviewPurge(ctx context.Context, id string) (lifecycledomain.PurgePreview, error) {
	if !lifecycledomain.ValidJournalID(id) {
		return lifecycledomain.PurgePreview{}, lifecycledomain.ErrValidation
	}
	scope, err := service.repository.PurgeScope(ctx, id)
	if err != nil {
		return lifecycledomain.PurgePreview{}, err
	}
	now := time.Now().UTC()
	preview, err := lifecycledomain.EvaluatePurge(scope, service.retentionDays, now)
	if err != nil || !preview.Allowed {
		return preview, err
	}
	var token [32]byte
	if _, err = rand.Read(token[:]); err != nil {
		return preview, err
	}
	preview.Token = hex.EncodeToString(token[:])
	preview.ExpiresAt = now.Add(lifecycledomain.ConfirmationLifetime).Format(jobsdomain.TimestampFormat)
	digest, _ := lifecycledomain.ConfirmationHash(preview.Token)
	if err = service.repository.ConfirmPurge(ctx, scope, service.retentionDays, digest, preview.ExpiresAt); err != nil {
		return lifecycledomain.PurgePreview{}, err
	}
	return preview, nil
}

func (service *Service) Purge(ctx context.Context, id, token, key string) (jobsdomain.Task, error) {
	if !lifecycledomain.ValidJournalID(id) {
		return jobsdomain.Task{}, lifecycledomain.ErrValidation
	}
	digest, err := lifecycledomain.ConfirmationHash(token)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	entry, err := service.repository.Entry(ctx, id)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	request := lifecycledomain.Request{Operation: "purge", EntryID: id, Confirmation: digest}
	data, err := json.Marshal(request)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	return service.jobs.CreateResource(ctx, jobsdomain.ResourceTask{Type: "lifecycle", TargetType: entry.Target.Type, TargetID: entry.Target.ID, ComicID: entry.Target.ComicID, Title: entry.Target.Title, Payload: string(data)}, key, service.repository.PurgeAdmission(request, service.retentionDays))
}

func (service *Service) checkPurgeJournals(ctx context.Context, items []lifecycledomain.PurgeItem, started bool) error {
	for _, item := range items {
		journal, err := service.files.Journal(ctx, item.Entry.ID)
		if err != nil {
			return err
		}
		if journal.Phase != "trashed" && (!started || journal.Phase != "purging" && journal.Phase != "purged") {
			return lifecycledomain.ErrJournal
		}
		if err = lifecycledomain.MatchJournalCandidates(journal, item.Candidates); err != nil {
			return err
		}
		if err = lifecycledomain.MatchTrashDirectories(journal, item.Entry.Target); err != nil {
			return err
		}
	}
	return nil
}

func (service *Service) runPurge(ctx context.Context, items []lifecycledomain.PurgeItem) error {
	// 先清理子条目，最后清理根条目，使根目录看到完整结果。
	order := map[string]int{"media": 0, "chapter": 1, "comic": 2}
	sort.SliceStable(items, func(i, j int) bool {
		return order[items[i].Entry.Target.Type] < order[items[j].Entry.Target.Type]
	})
	for _, item := range items {
		if err := service.files.Purge(ctx, item.Entry.ID); err != nil {
			return err
		}
		// 漫画和章节按托管目录整体删除；媒体只删除清单文件，保留章节目录中的其他内容。
		if directories := purgeRemoveDirectories(item.Entry.Target); len(directories) > 0 {
			if err := service.files.RemoveDirectories(ctx, directories); err != nil {
				return err
			}
		}
	}
	return nil
}

// purgeRemoveDirectories 返回永久清理后必须整体删除的漫画或章节托管路径。
// 只删除托管根下由该对象独占的路径；媒体不在这里处理，避免误删章节中的其他媒体。
func purgeRemoveDirectories(target lifecycledomain.Target) []string {
	if target.ComicID == "" {
		return nil
	}
	switch target.Type {
	case "comic":
		return []string{
			"hq/" + target.ComicID, "lq/" + target.ComicID, "thumbs/" + target.ComicID,
			"metadata/" + target.ComicID + ".json", "metadata/" + target.ComicID,
		}
	case "chapter":
		if target.ChapterID == "" {
			return nil
		}
		return []string{
			"hq/" + target.ComicID + "/" + target.ChapterID,
			"lq/" + target.ComicID + "/" + target.ChapterID,
		}
	default:
		return nil
	}
}
