package application

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	recoveryports "comicatlas.local/backend/internal/modules/recovery/application/ports"
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"context"
	"errors"
	"fmt"
)

type MediaVerifier struct{ files recoveryports.MediaFiles }

func NewMediaVerifier(files recoveryports.MediaFiles) *MediaVerifier {
	return &MediaVerifier{files: files}
}

// Verify 在任何恢复写事务之前逐项核对字节；不会修改侧车或保存成功记录。
func (verifier *MediaVerifier) Verify(ctx context.Context, comic string, sidecar recoverydomain.Sidecar, progress func(int) error) (result recoverydomain.MediaVerification, err error) {
	defer func() {
		err = errors.Join(err, ctx.Err())
		if err != nil {
			result = recoverydomain.MediaVerification{}
		}
	}()
	if err = ctx.Err(); err != nil {
		return result, err
	}
	if err = recoverydomain.ValidateSidecar(comic, sidecar); err != nil {
		return result, err
	}
	for index, media := range sidecar.Snapshot.Media {
		if err = ctx.Err(); err != nil {
			return result, err
		}
		availability := librarydomain.OriginalAvailability(media)
		if availability == "available" {
			var measured recoverydomain.Measurement
			var readErr error
			if media.MediaType == "video" {
				measured, readErr = verifier.files.MeasureHQVideo(ctx, comic, media.StoragePath)
			} else {
				measured, readErr = verifier.files.MeasureHQ(ctx, comic, media.StoragePath)
			}
			if readErr == nil {
				readErr = recoverydomain.MatchHQMeasurement(media, measured)
			}
			if readErr != nil {
				return result, fmt.Errorf("核对原件 %s: %w", media.ID, readErr)
			}
			result.Originals++
			result.Bytes += measured.Size
		} else {
			// 已主动释放的 HQ 不要求重新出现，但必须有可核对的当前 LQ。
			if !recoverydomain.CurrentLQ(media) {
				return result, recoverydomain.ErrMetadata
			}
			if media.Hash == "" {
				if err = verifier.files.RequireHQAbsent(ctx, comic, media.StoragePath); err != nil {
					return result, err
				}
			}
			switch availability {
			case "released":
				result.Released++
			case "missing":
				result.MissingOriginals++
			case "unbound":
				result.UnboundOriginals++
			default:
				return result, recoverydomain.ErrMetadata
			}
		}
		if recoverydomain.CurrentLQ(media) {
			lq := media.LQFile
			measured, readErr := verifier.files.MeasureLQ(ctx, lq.StoragePath)
			if readErr == nil {
				readErr = recoverydomain.MatchLQMeasurement(*lq, measured)
			}
			if readErr != nil {
				return result, fmt.Errorf("核对派生文件 %s: %w", media.ID, readErr)
			}
			result.Derivatives++
			result.Bytes += measured.Size
		} else if media.LQFile != nil {
			result.IgnoredDerivatives++
		}
		if progress != nil {
			if err = progress(index + 1); err != nil {
				return result, err
			}
		}
	}
	return result, nil
}
