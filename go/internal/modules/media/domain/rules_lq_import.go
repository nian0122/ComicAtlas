package domain

import (
	"path"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

func ValidateLQProvenance(value *LQProvenance, task string) error {
	if value == nil {
		return nil
	}
	if value.OriginalState != "" && value.OriginalState != "missing" && value.OriginalState != "released" && value.OriginalState != "unbound" {
		return ErrLQValidation
	}
	switch value.Origin {
	case "imported":
		if task != "" {
			return ErrLQValidation
		}
	case "recovered":
		if task == "" {
			return ErrLQValidation
		}
	default:
		return ErrLQValidation
	}
	return nil
}

func ValidateImportedLQRegistration(value LQRecord, recordedAt string) error {
	stamp, err := time.Parse(time.RFC3339Nano, recordedAt)
	if err != nil || stamp.UTC().Format(time.RFC3339Nano) != recordedAt {
		return ErrLQValidation
	}
	return ValidateImportedLQ(value)
}

func ValidateImportedLQSource(value LQRecord, hqState string) error {
	if ValidateImportedLQ(value) != nil {
		return ErrLQConflict
	}
	if state := value.Provenance.OriginalState; state != "" && (state == "released") != (hqState == "released") {
		return ErrLQConflict
	}
	if hqState != "available" && hqState != "released" {
		return ErrLQConflict
	}
	return nil
}

func ValidateImportedLQ(value LQRecord) error {
	if !utf8.ValidString(value.Path) || strings.ContainsFunc(value.Path, unicode.IsControl) {
		return ErrLQValidation
	}
	if value.MediaID == "" || len(value.MediaID) > 128 || !value.Valid || value.TaskID != "" || value.Provenance == nil || value.Provenance.Origin != "imported" || ValidateLQProvenance(value.Provenance, value.TaskID) != nil || value.Size < 1 || value.Size > 20<<30 || value.Width < 1 || value.Height < 1 || !ValidLQPath(value.Path) || path.Clean(value.Path) != value.Path || strings.ContainsAny(value.Path, ":\x00") || len(value.Path) > 2048 || path.Ext(value.Path) != ".webp" {
		return ErrLQValidation
	}
	if value.Spec != nil && ValidateImageSpec(*value.Spec) != nil {
		return ErrLQValidation
	}
	return nil
}
