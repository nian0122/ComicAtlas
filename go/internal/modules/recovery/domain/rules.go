package domain

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"encoding/hex"
	"path"
	"strings"
	"time"
)

func Key(value string) bool {
	if value == "" || len(value) > 128 {
		return false
	}
	for _, c := range value {
		if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-') {
			return false
		}
	}
	return true
}

func validHash(value string) bool {
	if len(value) != 64 || strings.ToLower(value) != value {
		return false
	}
	_, err := hex.DecodeString(value)
	return err == nil
}

func sourcePath(value, prefix string) bool {
	if len(value) > 2048 || !strings.HasPrefix(value, prefix+"/") || path.Clean(value) != value || strings.ContainsAny(value, "\\:") {
		return false
	}
	for _, part := range strings.Split(value, "/") {
		if part == "" || part == "." || part == ".." || !librarydomain.ValidName(part, 255) {
			return false
		}
	}
	return true
}

func ValidateSidecar(comic string, value Sidecar) error {
	if !Key(comic) || value.Version != 1 || !Key(value.TaskID) || !validHash(value.BaselineSHA256) || len(value.ObservedAt) > 40 {
		return ErrMetadata
	}
	if _, err := time.Parse(time.RFC3339Nano, value.ObservedAt); err != nil {
		return ErrMetadata
	}
	switch value.Producer {
	case "automatic":
		if value.SyncRevision < 1 || value.HasObservations || value.ObservationCount != 0 {
			return ErrMetadata
		}
	case "":
		if value.SyncRevision != 0 || !value.HasObservations || value.ObservationCount < 0 || value.ObservationCount > 2*librarydomain.MaxSnapshotMedia {
			return ErrMetadata
		}
	default:
		return ErrMetadata
	}
	if err := validateSnapshot(comic, value.Snapshot); err != nil {
		return err
	}
	_, stamp, err := librarydomain.SnapshotData(value.Snapshot)
	if err != nil {
		return ErrLimit
	}
	// 手动刷新的 baseline 是刷新前的快照，不能拿它与下一份 snapshot 比较。
	if value.Producer == "automatic" && stamp != value.BaselineSHA256 {
		return ErrMetadata
	}
	return nil
}
