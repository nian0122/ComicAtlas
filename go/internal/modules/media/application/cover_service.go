package application

import (
	"context"

	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
)

type Covers struct {
	repository mediaports.CoverRepository
	files      mediaports.CoverStorage
	frames     mediaports.CoverFrames
	generate   chan struct{}
}

func NewCovers(repo mediaports.CoverRepository, files mediaports.CoverStorage, frames mediaports.CoverFrames) *Covers {
	return &Covers{repository: repo, files: files, frames: frames, generate: make(chan struct{}, 1)}
}

// 生命周期在受理隐藏后等待已经开始的封面生成/清理结束，再操作捕获的文件。
func (service *Covers) AcquireFileMutation(ctx context.Context) (func(), error) {
	select {
	case service.generate <- struct{}{}:
		return func() { <-service.generate }, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}
