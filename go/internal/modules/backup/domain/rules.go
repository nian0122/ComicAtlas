package domain

import (
	"encoding/hex"
	"math"
	"path"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

func ValidPath(value string) bool {
	if value == "" || len(value) > MaxPathBytes || !utf8.ValidString(value) || path.Clean(value) != value || strings.HasPrefix(value, "/") || strings.Count(value, "/") >= MaxDepth {
		return false
	}
	if value != "data" && value != "media" && value != DatabasePath && !strings.HasPrefix(value, "media/") {
		return false
	}
	for _, char := range value {
		if unicode.IsControl(char) {
			return false
		}
	}
	return true
}

func ValidDigest(value string) bool {
	decoded, err := hex.DecodeString(value)
	return err == nil && len(decoded) == 32 && strings.ToLower(value) == value
}

func ValidTimestamp(value string) bool {
	parsed, err := time.Parse(time.RFC3339Nano, value)
	return err == nil && parsed.UTC().Format(time.RFC3339Nano) == value
}

func ValidateDescriptor(entry Entry) error {
	if !ValidPath(entry.Path) || entry.Size < 0 {
		return ErrValidation
	}
	if entry.Kind == Directory {
		if entry.Path == DatabasePath || entry.Size != 0 || entry.SHA256 != "" || entry.ModifiedAt != "" {
			return ErrValidation
		}
		return nil
	}
	if entry.Kind != File || entry.Path == "data" || entry.Path == "media" || !ValidTimestamp(entry.ModifiedAt) || entry.Path == DatabasePath && entry.Size == 0 {
		return ErrValidation
	}
	return nil
}

func ValidateEntry(entry Entry) error {
	if err := ValidateDescriptor(entry); err != nil {
		return err
	}
	if entry.Kind == File && !ValidDigest(entry.SHA256) {
		return ErrValidation
	}
	return nil
}

func Add(summary Summary, entry Entry) (Summary, error) {
	if ValidateEntry(entry) != nil || summary.Files < 0 || summary.Directories < 0 || summary.Bytes < 0 || summary.Files > MaxEntries || summary.Directories > MaxEntries || summary.Files+summary.Directories >= MaxEntries || entry.Size > math.MaxInt64-summary.Bytes {
		return Summary{}, ErrValidation
	}
	if entry.Kind == File {
		summary.Files++
		summary.Bytes += entry.Size
	} else {
		summary.Directories++
	}
	return summary, nil
}

func ValidateSummary(summary Summary) error {
	if summary.Files < 1 || summary.Directories < 2 || summary.Files > MaxEntries || summary.Directories > MaxEntries || summary.Files+summary.Directories > MaxEntries || summary.Bytes < 1 {
		return ErrValidation
	}
	return nil
}

func ValidateDatabase(info DatabaseInfo) error {
	if info.Format != "comicatlas-go-v1" || info.MigrationCount < 1 || info.MigrationCount > 999 || !ValidDigest(info.MigrationChecksum) {
		return ErrValidation
	}
	return nil
}

func ValidateRoots(data, media, database Entry) error {
	if data.Path != "data" || data.Kind != Directory || media.Path != "media" || media.Kind != Directory || database.Path != DatabasePath || database.Kind != File || ValidateEntry(data) != nil || ValidateEntry(media) != nil || ValidateEntry(database) != nil {
		return ErrValidation
	}
	return nil
}

func ValidateManifest(value Manifest) error {
	if value.Format != Format || value.Version != Version || !ValidID(value.ID) || !ValidTimestamp(value.CreatedAt) || ValidateDatabase(value.Database) != nil || ValidateSummary(value.Summary) != nil || value.Catalog.Size < 1 || !ValidDigest(value.Catalog.SHA256) {
		return ErrValidation
	}
	return nil
}

func ValidID(value string) bool {
	if len(value) != 26 {
		return false
	}
	for _, char := range value {
		if !(char >= 'A' && char <= 'Z' || char >= '2' && char <= '7') {
			return false
		}
	}
	return true
}

func ValidateReceipt(value RestoreReceipt) error {
	if value.Format != "comicatlas-restore" || value.Version != Version || !ValidID(value.BackupID) || !ValidTimestamp(value.RestoredAt) || ValidateDatabase(value.Database) != nil || ValidateSummary(value.Summary) != nil {
		return ErrValidation
	}
	return nil
}
