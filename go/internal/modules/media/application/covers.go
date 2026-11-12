package application

import (
	"context"
	"errors"
	"os"

	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
)

func (service *Covers) CoverSelection(ctx context.Context, id string) (mediadomain.CoverSelection, error) {
	if !mediadomain.StorageKey(id) {
		return mediadomain.CoverSelection{}, mediadomain.ErrLQValidation
	}
	return service.repository.CoverSelection(ctx, id)
}

func (service *Covers) SelectCover(ctx context.Context, id string, version int, media string) (mediadomain.CoverSelection, error) {
	if !mediadomain.StorageKey(id) || !mediadomain.StorageKey(media) || version < 1 || version >= 2147483647 {
		return mediadomain.CoverSelection{}, mediadomain.ErrLQValidation
	}
	release, err := service.AcquireFileMutation(ctx)
	if err != nil {
		return mediadomain.CoverSelection{}, err
	}
	defer release()
	selection, err := service.repository.SelectCover(ctx, id, version, media)
	if err != nil {
		return selection, err
	}
	// 封面变更时立即生成，读取路径只负责读取，不再兜底生成。
	file, _, err := service.buildCover(ctx, id)
	if err != nil {
		return selection, err
	}
	return selection, file.Close()
}

// Load 只读取已有的独立封面文件：不查缓存、不生成、不写数据库。
// 封面缺失时返回 404，由导入/补齐流程负责产出文件。
func (service *Covers) Load(ctx context.Context, id string) (*os.File, string, error) {
	if !mediadomain.StorageKey(id) {
		return nil, "", mediadomain.ErrLQNotFound
	}
	// 封面是导入时生成的独立文件，不因来源媒体回收或选择失效而隐藏。
	file, hash, err := service.files.OpenStoredCover(ctx, id)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) || errors.Is(err, mediadomain.ErrImageInvalid) || errors.Is(err, mediadomain.ErrImageOutput) {
			return nil, "", mediadomain.ErrLQNotFound
		}
		return nil, "", err
	}
	return file, hash, nil
}

func (service *Covers) buildCover(ctx context.Context, id string) (*os.File, string, error) {
	source, err := service.repository.CoverSource(ctx, id)
	if err != nil {
		return nil, "", err
	}
	if source.SHA256 == "" {
		resolved, err := service.files.ResolveImageSource(ctx, source)
		if err != nil {
			return nil, "", err
		}
		if err = service.repository.BindImageSource(ctx, source, resolved); err != nil {
			return nil, "", err
		}
		source, err = service.repository.CoverSource(ctx, id)
		if err != nil {
			return nil, "", err
		}
	}
	record, err := service.files.BuildCover(ctx, id, source)
	if err != nil {
		return nil, "", err
	}
	if err = service.repository.SaveCover(ctx, id, source, record); err != nil {
		return nil, "", err
	}
	file, err := service.files.OpenCover(ctx, record)
	return file, record.SHA256, err
}

// GenerateCover 在导入提交后为整本漫画生成独立封面。
// 候选顺序固定为命名封面、图片兜底、视频抽帧；生成失败不会回退到读取时生成。
func (service *Covers) GenerateCover(ctx context.Context, id string) error {
	if !mediadomain.StorageKey(id) {
		return mediadomain.ErrLQNotFound
	}
	candidates, err := service.repository.CoverCandidates(ctx, id)
	if err != nil {
		return err
	}
	ordered := mediadomain.OrderCoverCandidates(candidates)
	if len(ordered) == 0 {
		return mediadomain.ErrLQNotFound
	}
	release, err := service.AcquireFileMutation(ctx)
	if err != nil {
		return err
	}
	defer release()
	failures := make([]error, 0, len(ordered))
	for _, candidate := range ordered {
		switch candidate.MediaType {
		case "image":
			source, err := service.repository.ImageSource(ctx, candidate.MediaID)
			if err != nil {
				failures = append(failures, err)
				continue
			}
			if source.SHA256 == "" {
				resolved, err := service.files.ResolveImageSource(ctx, source)
				if err != nil {
					failures = append(failures, err)
					continue
				}
				if err = service.repository.BindImageSource(ctx, source, resolved); err != nil {
					failures = append(failures, err)
					continue
				}
				if source, err = service.repository.ImageSource(ctx, candidate.MediaID); err != nil {
					failures = append(failures, err)
					continue
				}
			}
			record, err := service.files.BuildCover(ctx, id, source)
			if err != nil {
				failures = append(failures, err)
				continue
			}
			// 先切换选择，SaveCover 才会接受新来源；已经是同一张图时不增加版本。
			current, selectionErr := service.repository.CoverSelectionRead(ctx, id)
			if selectionErr != nil {
				failures = append(failures, selectionErr)
				continue
			}
			if current == nil || current.MediaID != candidate.MediaID {
				if err = service.repository.SetCoverSelection(ctx, id, candidate.MediaID); err != nil {
					failures = append(failures, err)
					continue
				}
			}
			if err = service.repository.SaveCover(ctx, id, source, record); err != nil {
				return err
			}
			return nil
		case "video":
			if service.frames == nil {
				continue
			}
			filename, err := service.files.MediaPath(candidate.RelativePath)
			if err != nil {
				failures = append(failures, err)
				continue
			}
			frame, err := service.frames.Extract(ctx, filename)
			if err != nil {
				failures = append(failures, err)
				continue
			}
			record, err := service.files.BuildCoverFromFrame(ctx, id, candidate.MediaID, frame)
			if err != nil {
				failures = append(failures, err)
				continue
			}
			if err = service.repository.SaveFrameCover(ctx, id, record); err != nil {
				failures = append(failures, err)
				continue
			}
			return nil
		}
	}
	if len(failures) == 0 {
		return mediadomain.ErrLQNotFound
	}
	return errors.Join(failures...)
}
