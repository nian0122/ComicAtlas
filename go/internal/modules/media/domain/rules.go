package domain

import (
	"math"
	"sort"
	"strconv"
	"strings"
)

// Analyze 只解释探测数据，不执行工具或读写文件。
func Analyze(probe Probe) (Video, error) {
	result := Video{Container: probe.Container, AudioCodecs: []string{}, PlaybackHint: "transcode_recommended"}
	if len(probe.Streams) == 0 || len(probe.Streams) > 64 {
		return result, ErrInvalidVideo
	}
	videoCount := 0
	audio := map[string]bool{}
	for _, stream := range probe.Streams {
		if len(stream.Codec) > 64 || len(stream.PixelFormat) > 64 {
			return result, ErrInvalidVideo
		}
		switch stream.Type {
		case "video":
			if stream.AttachedPicture {
				continue
			}
			videoCount++
			if videoCount == 1 {
				result.VideoCodec, result.PixelFormat = stream.Codec, stream.PixelFormat
				result.Width, result.Height = stream.Width, stream.Height
				result.DurationMS = durationMS(stream.Duration)
			}
		case "audio":
			audio[stream.Codec] = true
		}
	}
	if videoCount == 0 || result.Width < 1 || result.Height < 1 || result.Width > 65536 || result.Height > 65536 || result.VideoCodec == "" {
		return result, ErrInvalidVideo
	}
	if result.DurationMS == nil {
		result.DurationMS = durationMS(probe.Duration)
	}
	for codec := range audio {
		result.AudioCodecs = append(result.AudioCodecs, codec)
	}
	sort.Strings(result.AudioCodecs)
	mp4 := probe.Extension == ".mp4" && strings.Contains(","+probe.Container+",", ",mp4,")
	webm := probe.Extension == ".webm" && strings.Contains(","+probe.Container+",", ",webm,")
	switch {
	case strings.Contains(","+probe.Container+",", ",mp4,"):
		result.Container = "mp4"
		if probe.Extension == ".mov" {
			result.Container = "quicktime"
		}
	case strings.Contains(","+probe.Container+",", ",matroska,"):
		result.Container = "matroska"
		if webm {
			result.Container = "webm"
		}
	case probe.Container == "avi":
		result.Container = "avi"
	default:
		return result, ErrInvalidVideo
	}
	compatible := videoCount == 1 && ((mp4 && result.VideoCodec == "h264") || (webm && (result.VideoCodec == "vp8" || result.VideoCodec == "vp9"))) && result.PixelFormat == "yuv420p"
	for codec := range audio {
		if (mp4 && codec != "aac" && codec != "mp3") || (webm && codec != "opus" && codec != "vorbis") || codec == "" {
			compatible = false
		}
	}
	if compatible {
		result.PlaybackHint = "browser_candidate"
	}
	return result, nil
}

func durationMS(value string) *int64 {
	seconds, err := strconv.ParseFloat(value, 64)
	if err != nil || math.IsNaN(seconds) || math.IsInf(seconds, 0) || seconds <= 0 || seconds >= float64(math.MaxInt64/1000-1) {
		return nil
	}
	milliseconds := int64(math.Round(seconds * 1000))
	if milliseconds <= 0 {
		return nil
	}
	return &milliseconds
}

func ContentType(container string) string {
	switch container {
	case "mp4":
		return "video/mp4"
	case "quicktime":
		return "video/quicktime"
	case "webm":
		return "video/webm"
	case "matroska":
		return "video/x-matroska"
	case "avi":
		return "video/x-msvideo"
	default:
		return "application/octet-stream"
	}
}
