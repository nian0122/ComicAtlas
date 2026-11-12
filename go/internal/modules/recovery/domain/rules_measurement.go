package domain

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"slices"
)

func ValidateHQPath(comic, name string) error {
	if !Key(comic) || !sourcePath(name, "hq/"+comic) {
		return ErrValidation
	}
	return nil
}

func ValidateLQPath(name string) error {
	if !sourcePath(name, "lq") {
		return ErrValidation
	}
	return nil
}

func MatchMeasurement(value Measurement, name, hash string, size int64) error {
	if value.Path != name || value.Size != size || value.SHA256 != hash {
		return ErrContentMismatch
	}
	return nil
}

func MatchHQMeasurement(media librarydomain.StoredMedia, value Measurement) error {
	if err := MatchMeasurement(value, media.StoragePath, media.Hash, media.Size); err != nil {
		return err
	}
	if media.MediaType == "video" {
		if value.Image != nil || value.Video == nil {
			return ErrContentMismatch
		}
		return matchVideo(media, *value.Video)
	}
	info := value.Image
	if media.MediaType != "image" || info == nil || info.MediaType != "image" || info.ContentType != media.ContentType || info.Width < 1 || info.Height < 1 || media.Width != nil && *media.Width != info.Width || media.Height != nil && *media.Height != info.Height {
		return ErrContentMismatch
	}
	return nil
}

func matchVideo(media librarydomain.StoredMedia, value mediadomain.Video) error {
	if value.Width < 1 || value.Height < 1 || value.VideoCodec == "" || mediadomain.ContentType(value.Container) == "application/octet-stream" || media.ContentType != mediadomain.ContentType(value.Container) || media.Width != nil && *media.Width != value.Width || media.Height != nil && *media.Height != value.Height {
		return ErrContentMismatch
	}
	if expected := media.Video; expected != nil {
		if expected.Container != value.Container || expected.VideoCodec != value.VideoCodec || expected.PixelFormat != value.PixelFormat || expected.Width != value.Width || expected.Height != value.Height || expected.DurationMS != nil && (value.DurationMS == nil || *expected.DurationMS != *value.DurationMS) {
			return ErrContentMismatch
		}
		before, after := slices.Clone(expected.AudioCodecs), slices.Clone(value.AudioCodecs)
		slices.Sort(before)
		slices.Sort(after)
		if !slices.Equal(before, after) {
			return ErrContentMismatch
		}
	}
	return nil
}

func MatchLQMeasurement(file librarydomain.StoredLQ, value Measurement) error {
	if err := MatchMeasurement(value, file.StoragePath, file.Hash, file.Size); err != nil {
		return err
	}
	info := value.Image
	if info == nil || info.MediaType != "image" || info.ContentType != "image/webp" || info.Width != file.Width || info.Height != file.Height {
		return ErrContentMismatch
	}
	return nil
}

// CurrentLQ 不将旧来源或已失效的派生文件重新标为可读。
func CurrentLQ(media librarydomain.StoredMedia) bool {
	return media.LQFile != nil && media.LQFile.Valid && media.LQFile.MatchesSource(media.Hash)
}
