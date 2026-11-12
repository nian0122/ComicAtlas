package domain

import (
	"encoding/hex"
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
	if value.ReferenceSHA256 != "" && !lqDigest(value.ReferenceSHA256) {
		return ErrLQValidation
	}
	if value.OriginalState != "" && value.OriginalState != "missing" && value.OriginalState != "released" && value.OriginalState != "unbound" {
		return ErrLQValidation
	}
	if value.OriginalState != "" && value.ReferenceSHA256 == "" {
		return ErrLQValidation
	}
	switch value.Origin {
	case "imported":
		if task != "" || value.ReferenceSHA256 == "" {
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
	return ValidateImportedLQ(value, value.SourceSHA256)
}

// LQMatchesSource 区分已核对摘要和明确的快照关联，不把空字符串相等当成摘要匹配。
func LQMatchesSource(record *LQRecord, sourceSHA string) bool {
	if record == nil {
		return false
	}
	if record.SourceSHA256 != "" {
		return sourceSHA != "" && record.SourceSHA256 == sourceSHA && (record.Provenance == nil || record.Provenance.OriginalState == "")
	}
	return sourceSHA == "" && record.Provenance != nil && record.Provenance.OriginalState != "" && record.Provenance.ReferenceSHA256 != "" && ValidateLQProvenance(record.Provenance, record.TaskID) == nil
}

func ValidateImportedLQSource(value LQRecord, sourceSHA, hqState string) error {
	if ValidateImportedLQ(value, sourceSHA) != nil {
		return ErrLQConflict
	}
	if sourceSHA == "" && (value.Provenance.OriginalState == "released") != (hqState == "released") {
		return ErrLQConflict
	}
	if hqState != "available" && hqState != "released" {
		return ErrLQConflict
	}
	return nil
}

// ValidateImportedLQ 要求可靠摘要或带明确原件状态的快照关联。
func ValidateImportedLQ(value LQRecord, sourceSHA string) error {
	if !utf8.ValidString(value.Path) || strings.ContainsFunc(value.Path, unicode.IsControl) {
		return ErrLQValidation
	}
	if value.MediaID == "" || len(value.MediaID) > 128 || !value.Valid || value.TaskID != "" || value.Provenance == nil || value.Provenance.Origin != "imported" || ValidateLQProvenance(value.Provenance, value.TaskID) != nil || !LQMatchesSource(&value, sourceSHA) || sourceSHA != "" && !lqDigest(sourceSHA) || !lqDigest(value.SHA256) || value.Size < 1 || value.Size > 20<<30 || value.Width < 1 || value.Height < 1 || !ValidLQPath(value.Path) || path.Clean(value.Path) != value.Path || strings.ContainsAny(value.Path, ":\x00") || len(value.Path) > 2048 || path.Ext(value.Path) != ".webp" {
		return ErrLQValidation
	}
	if value.Spec != nil && ValidateImageSpec(*value.Spec) != nil {
		return ErrLQValidation
	}
	return nil
}

func lqDigest(value string) bool {
	decoded, err := hex.DecodeString(value)
	return err == nil && len(decoded) == 32 && strings.ToLower(value) == value
}
