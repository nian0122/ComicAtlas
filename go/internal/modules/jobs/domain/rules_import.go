package domain

import (
	"crypto/sha256"
	"fmt"
	"path/filepath"
	"strings"
)

func PrepareImport(input ImportInput, key string) (PreparedImport, error) {
	source, title := input.SourcePath, strings.TrimSpace(input.Title)
	if (input.Type != "directory_import" && input.Type != "archive_import") || ValidateImportSource(source) != nil || len(title) > 500 || len(key) > 128 {
		return PreparedImport{}, ErrValidation
	}
	source = filepath.ToSlash(filepath.Clean(source))
	if source == "." {
		return PreparedImport{}, ErrValidation
	}
	explicit := title != ""
	if title == "" {
		title = filepath.Base(source)
		if input.Type == "archive_import" {
			if stem := strings.TrimSuffix(title, filepath.Ext(title)); stem != "" {
				title = stem
			}
		}
	}
	if len(title) > 500 || input.Type == "archive_import" && !strings.EqualFold(filepath.Ext(source), ".zip") && !strings.EqualFold(filepath.Ext(source), ".cbz") {
		return PreparedImport{}, ErrValidation
	}
	identity := source + "\x00" + title
	if input.Type == "archive_import" {
		identity = input.Type + "\x00" + identity + fmt.Sprint(explicit)
	}
	return PreparedImport{ReferenceID: input.ReferenceID, Resource: ResourceTask{Type: input.Type, SourcePath: source, Title: title, Payload: ""}, Explicit: explicit, RequestHash: fmt.Sprintf("%x", sha256.Sum256([]byte(identity)))}, nil
}

func ValidateImportSource(source string) error {
	if !filepath.IsLocal(source) || source == "." || strings.ContainsAny(source, "\\:\x00") || len(source) > 2048 {
		return ErrValidation
	}
	return nil
}
