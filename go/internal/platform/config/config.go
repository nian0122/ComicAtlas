// Package config 集中读取并校验应用配置。
package config

import (
	"fmt"
	"log/slog"
	"net"
	"net/url"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	HTTPAddress           string
	ReadingHTTPAddress    string
	DataDirectory         string
	ImportDirectory       string
	ImportHostRoot        string
	MediaDirectory        string
	LogLevel              slog.Level
	BusyTimeout           time.Duration
	ShutdownTimeout       time.Duration
	FFprobePath           string
	FFmpegPath            string
	TranscodeEncoder      string
	TranscodeNVENCQuality int
	CwebpPath             string
	DjpegPath             string
	ImagePixels           int64
	UploadMinFreeBytes    int64
	UploadMinFreePercent  int
	TrashRetentionDays    int
	AIEndpoint            string
	AIRequestTimeout      time.Duration
}

func Load(getenv func(string) string) (Config, error) {
	value := func(name, fallback string) string {
		if v := strings.TrimSpace(getenv("COMICATLAS_" + name)); v != "" {
			return v
		}
		return fallback
	}
	configuration := Config{HTTPAddress: value("HTTP_ADDR", "127.0.0.1:8080")}
	configuration.ImportHostRoot = value("IMPORT_HOST_ROOT", "")
	configuration.AIEndpoint = value("AI_ENDPOINT", "")
	if configuration.AIEndpoint != "" {
		endpoint, err := url.Parse(configuration.AIEndpoint)
		if err != nil || len(configuration.AIEndpoint) > 2048 || endpoint.Hostname() == "" || endpoint.User != nil || endpoint.Opaque != "" || endpoint.RawPath != "" || endpoint.RawQuery != "" || endpoint.ForceQuery || endpoint.Fragment != "" || endpoint.Scheme != "http" && endpoint.Scheme != "https" {
			return Config{}, fmt.Errorf("AI_ENDPOINT 必须为不含凭据、查询或片段的 HTTP 服务地址")
		}
		if endpoint.Port() != "" {
			port, err := strconv.Atoi(endpoint.Port())
			if err != nil || port < 1 || port > 65535 {
				return Config{}, fmt.Errorf("AI_ENDPOINT 端口必须为 1..65535")
			}
		}
	}
	retention, retentionErr := strconv.Atoi(value("TRASH_RETENTION_DAYS", "0"))
	if retentionErr != nil || retention < 0 || retention > 3650 {
		return Config{}, fmt.Errorf("TRASH_RETENTION_DAYS 必须为 0..3650 的整数")
	}
	configuration.TrashRetentionDays = retention
	uploadBytes, uploadBytesErr := strconv.ParseInt(value("UPLOAD_MIN_FREE_BYTES", "5368709120"), 10, 64)
	uploadPercent, uploadPercentErr := strconv.Atoi(value("UPLOAD_MIN_FREE_PERCENT", "10"))
	if uploadBytesErr != nil || uploadPercentErr != nil || uploadBytes < 0 || uploadBytes > 1<<40 || uploadPercent < 0 || uploadPercent > 50 {
		return Config{}, fmt.Errorf("上传空间保留参数无效")
	}
	configuration.UploadMinFreeBytes = uploadBytes
	configuration.UploadMinFreePercent = uploadPercent
	configuration.ReadingHTTPAddress = value("READING_HTTP_ADDR", "")
	if configuration.ReadingHTTPAddress != "" {
		_, readingPort, err := net.SplitHostPort(configuration.ReadingHTTPAddress)
		if err != nil {
			return Config{}, fmt.Errorf("READING_HTTP_ADDR 必须是 host:port")
		}
		port, err := strconv.Atoi(readingPort)
		if err != nil || port < 1 || port > 65535 {
			return Config{}, fmt.Errorf("READING_HTTP_ADDR 端口必须为 1..65535")
		}
		if configuration.ReadingHTTPAddress == configuration.HTTPAddress {
			return Config{}, fmt.Errorf("管理与阅读监听地址必须不同")
		}
	}
	configuration.FFprobePath = value("FFPROBE_PATH", "")
	configuration.FFmpegPath = value("FFMPEG_PATH", "")
	if configuration.FFmpegPath != "" && !filepath.IsAbs(configuration.FFmpegPath) {
		return Config{}, fmt.Errorf("FFMPEG_PATH 必须为绝对路径")
	}
	configuration.TranscodeEncoder = value("TRANSCODE_ENCODER", "auto")
	if configuration.TranscodeEncoder != "auto" && configuration.TranscodeEncoder != "libx264" && configuration.TranscodeEncoder != "h264_nvenc" {
		return Config{}, fmt.Errorf("TRANSCODE_ENCODER 必须为 auto、libx264 或 h264_nvenc")
	}
	nvencQuality, nvencQualityErr := strconv.Atoi(value("TRANSCODE_NVENC_CQ", "31"))
	if nvencQualityErr != nil || nvencQuality < 1 || nvencQuality > 51 {
		return Config{}, fmt.Errorf("TRANSCODE_NVENC_CQ 必须为 1..51 的整数")
	}
	configuration.TranscodeNVENCQuality = nvencQuality
	configuration.CwebpPath = value("CWEBP_PATH", "")
	configuration.DjpegPath = value("DJPEG_PATH", "")
	for _, path := range []string{configuration.CwebpPath, configuration.DjpegPath} {
		if path != "" && !filepath.IsAbs(path) {
			return Config{}, fmt.Errorf("图片工具必须为绝对路径")
		}
	}
	pixels, errPixels := strconv.ParseInt(value("IMAGE_MAX_DECODE_PIXELS", "16000000"), 10, 64)
	configuration.ImagePixels = pixels
	if errPixels != nil || configuration.ImagePixels < 1 || configuration.ImagePixels > 64000000 {
		return Config{}, fmt.Errorf("IMAGE_MAX_DECODE_PIXELS 必须为 1..64000000")
	}
	if configuration.FFprobePath != "" && !filepath.IsAbs(configuration.FFprobePath) {
		return Config{}, fmt.Errorf("FFPROBE_PATH 必须为绝对路径")
	}
	_, port, err := net.SplitHostPort(configuration.HTTPAddress)
	if err != nil {
		return Config{}, fmt.Errorf("HTTP_ADDR 必须是 host:port: %w", err)
	}
	portNumber, err := strconv.Atoi(port)
	if err != nil || portNumber < 1 || portNumber > 65535 {
		return Config{}, fmt.Errorf("HTTP_ADDR 端口必须为 1..65535")
	}
	directories, err := LoadDirectories(getenv)
	if err != nil {
		return Config{}, err
	}
	configuration.DataDirectory, configuration.ImportDirectory, configuration.MediaDirectory = directories.Data, directories.Import, directories.Media
	if err = configuration.LogLevel.UnmarshalText([]byte(value("LOG_LEVEL", "INFO"))); err != nil {
		return Config{}, fmt.Errorf("LOG_LEVEL 无效: %w", err)
	}
	for _, item := range []struct {
		name     string
		fallback string
		maximum  time.Duration
		target   *time.Duration
	}{
		{"DB_BUSY_TIMEOUT", "3s", 30 * time.Second, &configuration.BusyTimeout},
		{"SHUTDOWN_TIMEOUT", "15s", 2 * time.Minute, &configuration.ShutdownTimeout},
		{"AI_REQUEST_TIMEOUT", "5m", 30 * time.Minute, &configuration.AIRequestTimeout},
	} {
		duration, parseErr := time.ParseDuration(value(item.name, item.fallback))
		if parseErr != nil || duration < time.Millisecond || duration > item.maximum {
			return Config{}, fmt.Errorf("%s 必须为 1ms..%s 的时长", item.name, item.maximum)
		}
		*item.target = duration
	}
	return configuration, nil
}
