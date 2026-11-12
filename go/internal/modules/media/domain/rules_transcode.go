package domain

import (
	"encoding/hex"
	"time"
)

const (
	MinTranscodeTimeout = 2 * time.Hour
	MaxTranscodeTimeout = 10 * time.Hour
	// MaxTranscodeTaskTimeout 为任务外层摘要校验、发布和清理预留余量。
	MaxTranscodeTaskTimeout = MaxTranscodeTimeout + 2*time.Hour
)

// TranscodeTimeout 按源时长估算单项编码期限：2 倍时长加 30 分钟，限制在 2..10 小时。
func TranscodeTimeout(durationMS *int64) time.Duration {
	if durationMS == nil || *durationMS <= 0 {
		return MinTranscodeTimeout
	}
	if *durationMS >= int64(MaxTranscodeTimeout/time.Millisecond) {
		return MaxTranscodeTimeout
	}
	estimate := time.Duration(*durationMS)*time.Millisecond*2 + 30*time.Minute
	if estimate < MinTranscodeTimeout {
		return MinTranscodeTimeout
	}
	if estimate > MaxTranscodeTimeout {
		return MaxTranscodeTimeout
	}
	return estimate
}

func ValidateVideoEncodeRequest(request VideoEncodeRequest) error {
	digest, err := hex.DecodeString(request.SourceSHA256)
	if err != nil || len(digest) != 32 || request.SourceSize < 1 || request.SourceSize > MaxTranscodeBytes {
		return ErrTranscodeInvalid
	}
	for _, character := range request.SourceSHA256 {
		if character >= 'A' && character <= 'F' {
			return ErrTranscodeInvalid
		}
	}
	return nil
}

func ValidateTranscodeSource(video Video) error {
	if video.Width < 1 || video.Height < 1 || int64(video.Width)*int64(video.Height) > MaxTranscodePixels ||
		video.Width > 16384 || video.Height > 16384 {
		return ErrTranscodeLimit
	}
	return nil
}

// ValidateTranscodeOutput 防止编码器成功退出后登记错误格式、截断或丢失音轨的产物。
func ValidateTranscodeOutput(source, output Video) error {
	if output.Container != "mp4" || output.VideoCodec != "h264" || output.PixelFormat != "yuv420p" ||
		output.PlaybackHint != "browser_candidate" || output.Width != source.Width+(source.Width%2) ||
		output.Height != source.Height+(source.Height%2) {
		return ErrTranscodeOutput
	}
	if (len(source.AudioCodecs) == 0 && len(output.AudioCodecs) != 0) ||
		(len(source.AudioCodecs) != 0 && (len(output.AudioCodecs) != 1 || output.AudioCodecs[0] != "aac")) {
		return ErrTranscodeOutput
	}
	if source.DurationMS != nil {
		if output.DurationMS == nil {
			return ErrTranscodeOutput
		}
		difference := *output.DurationMS - *source.DurationMS
		if difference < -250 || difference > 1000 {
			return ErrTranscodeOutput
		}
	}
	return nil
}
