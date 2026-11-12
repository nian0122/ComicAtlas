package application

import (
	lifecycleports "comicatlas.local/backend/internal/modules/lifecycle/application/ports"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
)

type Covers struct {
	repository lifecycleports.CoverRepository
	reader     lifecycleports.CoverReader
}

func NewCovers(repo lifecycleports.CoverRepository, reader lifecycleports.CoverReader) *Covers {
	return &Covers{repository: repo, reader: reader}
}

// Open 直接读取导入时生成的封面文件，不在回收阶段重新渲染或缓存。
func (service *Covers) Open(ctx context.Context, id string) (lifecycledomain.Cover, error) {
	if !lifecycledomain.ValidJournalID(id) {
		return lifecycledomain.Cover{}, lifecycledomain.ErrValidation
	}
	source, err := service.repository.Cover(ctx, id)
	if err != nil {
		return lifecycledomain.Cover{}, err
	}
	return service.reader.ReadCover(ctx, source)
}

func (service *Service) decorateCovers(ctx context.Context, entries []lifecycledomain.Entry) error {
	ids := []string{}
	comicIDs := []string{}
	for _, entry := range entries {
		if entry.State != "trashed" {
			continue
		}
		ids = append(ids, entry.ID)
		if entry.Target.Type != "comic" {
			comicIDs = append(comicIDs, entry.Target.ComicID)
		}
	}
	available, err := service.repository.CoverEntries(ctx, ids)
	if err != nil {
		return err
	}
	comicCovers, err := service.repository.ActiveComicCovers(ctx, comicIDs)
	if err != nil {
		return err
	}
	for index := range entries {
		entry := &entries[index]
		if entry.State != "trashed" {
			continue
		}
		if entry.Target.Type == "comic" && available[entry.ID] {
			url := "/api/v1/trash/" + entry.ID + "/cover"
			entry.CoverURL = &url
			continue
		}
		if entry.Target.Type != "comic" && comicCovers[entry.Target.ComicID] {
			url := "/api/v1/comics/" + entry.Target.ComicID + "/cover"
			entry.CoverURL = &url
		}
	}
	return nil
}
