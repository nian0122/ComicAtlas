package tools

import (
	"context"
	"strings"
	"time"

	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/process"
)

type executor interface {
	Run(context.Context, process.Request) (process.Result, error)
}

type CapabilityReader struct {
	runner  executor
	ffmpeg  string
	ffprobe string
	cwebp   string
	djpeg   string
}

func NewCapabilityReader(runner executor, ffmpeg, ffprobe, cwebp, djpeg string) *CapabilityReader {
	return &CapabilityReader{runner: runner, ffmpeg: ffmpeg, ffprobe: ffprobe, cwebp: cwebp, djpeg: djpeg}
}

var _ mediaports.CapabilityReader = (*CapabilityReader)(nil)

func (reader *CapabilityReader) Read(ctx context.Context) (mediadomain.Capabilities, error) {
	return mediadomain.Capabilities{
		FFmpeg:  reader.inspect(ctx, "ffmpeg", reader.ffmpeg),
		FFprobe: reader.inspect(ctx, "ffprobe", reader.ffprobe),
		Cwebp:   reader.inspect(ctx, "cwebp", reader.cwebp),
		Djpeg:   reader.inspect(ctx, "djpeg", reader.djpeg),
	}, nil
}

func (reader *CapabilityReader) inspect(ctx context.Context, name, executable string) mediadomain.ToolCapability {
	result := mediadomain.ToolCapability{Configured: executable != ""}
	if !result.Configured {
		return result
	}
	output, err := reader.runner.Run(ctx, process.Request{Executable: executable, Args: []string{"-version"}, Timeout: 3 * time.Second})
	if err != nil || output.StdoutTruncated || output.StderrTruncated || ctx.Err() != nil {
		return result
	}
	line, _, _ := strings.Cut(string(output.Stdout), "\n")
	version := toolVersion(name, strings.Fields(strings.TrimSpace(line)))
	if version == "" {
		line, _, _ = strings.Cut(string(output.Stderr), "\n")
		version = toolVersion(name, strings.Fields(strings.TrimSpace(line)))
	}
	if version == "" {
		return result
	}
	result.Available, result.Version = true, &version
	return result
}

func toolVersion(name string, fields []string) string {
	switch name {
	case "ffmpeg", "ffprobe":
		if len(fields) >= 3 && fields[0] == name && fields[1] == "version" && validToolVersion(fields[2]) {
			return fields[2]
		}
	case "cwebp":
		if len(fields) >= 1 && validToolVersion(fields[0]) {
			return fields[0]
		}
	case "djpeg":
		if len(fields) >= 3 && fields[0] == "libjpeg-turbo" && fields[1] == "version" && validToolVersion(fields[2]) {
			return fields[2]
		}
	}
	return ""
}

func validToolVersion(value string) bool {
	if len(value) == 0 || len(value) > 64 {
		return false
	}
	start := 0
	if value[0] == 'n' || value[0] == 'N' {
		start = 1
	}
	if start == len(value) || value[start] < '0' || value[start] > '9' {
		return false
	}
	containsDigit := false
	for _, char := range value {
		if char >= '0' && char <= '9' {
			containsDigit = true
			continue
		}
		if (char >= 'a' && char <= 'z') || (char >= 'A' && char <= 'Z') || strings.ContainsRune(".+_-", char) {
			continue
		}
		return false
	}
	return containsDigit
}
