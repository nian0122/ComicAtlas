package domain

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
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
	if !Key(comic) || value.Version != 1 || !Key(value.TaskID) || len(value.ObservedAt) > 40 {
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
	if _, err := librarydomain.SnapshotData(value.Snapshot); err != nil {
		return ErrLimit
	}
	return nil
}
