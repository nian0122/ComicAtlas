package application

import (
	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"context"
	"time"
)

// ImageService 由入口共享，LQ、缩略图及回收封面共用同一个串行像素处理许可。
type ImageService struct {
	renderer mediaports.ImageRenderer
	slot     chan struct{}
}

func NewImages(renderer mediaports.ImageRenderer) *ImageService {
	return &ImageService{renderer: renderer, slot: make(chan struct{}, 1)}
}
func (service *ImageService) Render(parent context.Context, request mediadomain.ImageRequest) (mediadomain.ImageArtifact, error) {
	if err := mediadomain.ValidateImageSpec(request.Spec); err != nil {
		return mediadomain.ImageArtifact{}, err
	}
	if service.renderer == nil {
		return mediadomain.ImageArtifact{}, mediadomain.ErrImageUnavailable
	}
	ctx, cancel := context.WithTimeout(parent, 2*time.Minute)
	defer cancel()
	select {
	case service.slot <- struct{}{}:
		defer func() { <-service.slot }()
	case <-ctx.Done():
		return mediadomain.ImageArtifact{}, ctx.Err()
	}
	if err := ctx.Err(); err != nil {
		return mediadomain.ImageArtifact{}, err
	}
	return service.renderer.Render(ctx, request)
}
