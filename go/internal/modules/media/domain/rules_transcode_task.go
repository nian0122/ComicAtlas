package domain

import (
	"path"
	"strings"
)

func TranscodedName(name string) string { return strings.TrimSuffix(name, path.Ext(name)) + ".mp4" }

func ValidateTranscodeInput(item TranscodeItem) error {
	if item.SourceSize < 1 {
		return ErrTranscodeInvalid
	}
	if item.SourceSize > MaxTranscodeBytes {
		return ErrTranscodeLimit
	}
	return nil
}

func ValidateTranscodeScope(kind, id string) error {
	if (kind != "comic" && kind != "chapter" && kind != "media") || id == "" || len(id) > 128 {
		return ErrLQValidation
	}
	return nil
}
func ValidateTranscodeSelection(count, active, incomplete int) error {
	if active > 0 || incomplete > 0 {
		return ErrLQConflict
	}
	if count > MaxTranscodeItems {
		return ErrTranscodeLimit
	}
	return nil
}
func ValidateTranscodeRecord(record TranscodeRecord) error {
	if err := ValidateVideoEncodeRequest(VideoEncodeRequest{SourceSize: record.Size, SourceSHA256: record.SHA256}); err != nil {
		return ErrTranscodeOutput
	}
	if err := ValidateTranscodeSource(record.Video); err != nil {
		return ErrTranscodeOutput
	}
	if err := ValidateTranscodeOutput(record.Video, record.Video); err != nil {
		return ErrTranscodeOutput
	}
	if !strings.HasPrefix(record.StagePath, "staging/transcode/") || !ValidHQPath(record.Path) || strings.Contains(record.Path, "\\") ||
		path.Clean(record.StagePath) != record.StagePath || strings.Contains(record.StagePath, "\\") ||
		record.Size < 1 || record.Size > MaxTranscodeBytes || len(record.SHA256) != 64 || record.Video.Container != "mp4" ||
		record.Video.VideoCodec != "h264" || record.Video.PlaybackHint != "browser_candidate" {
		return ErrTranscodeOutput
	}
	return nil
}
