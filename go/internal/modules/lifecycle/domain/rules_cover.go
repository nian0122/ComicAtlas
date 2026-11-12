package domain

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"strings"
)

func SelectOwnedCover(entry, media string, options, plan []FileCandidate) CoverSource {
	source := CoverSource{EntryID: entry, MediaID: media}
	if len(options) > 3 {
		return source
	}
	for _, option := range options {
		for _, owned := range plan {
			if option == owned {
				source.Candidates = append(source.Candidates, option)
				break
			}
		}
	}
	return source
}

func CoverFiles(source CoverSource, journal FileJournal) ([]RetainedFile, error) {
	if ValidateCoverSource(source) != nil || journal.ID != source.EntryID || journal.Phase != "trashed" {
		return nil, ErrJournal
	}
	items := make([]RetainedFile, 0, len(source.Candidates))
	for _, candidate := range source.Candidates {
		found := false
		for _, item := range journal.Files {
			if item.Source == candidate {
				items = append(items, item)
				found = true
				break
			}
		}
		if !found {
			return nil, ErrJournal
		}
	}
	return items, nil
}

func ValidateCoverSource(source CoverSource) error {
	if !ValidJournalID(source.EntryID) || source.MediaID == "" || len(source.MediaID) > 128 || len(source.Candidates) < 1 || len(source.Candidates) > 3 || ValidateCandidates(source.Candidates) != nil {
		return ErrValidation
	}
	for _, item := range source.Candidates {
		role, _, _ := strings.Cut(item.Path, "/")
		if (role != "hq" && role != "lq" && role != "thumbs") || item.Size < 0 || !validDigest(item.SHA256) {
			return ErrValidation
		}
	}
	data, err := json.Marshal(source)
	if err != nil || len(data) > MaxCoverSourceBytes {
		return ErrValidation
	}
	return nil
}

func CoverSourceKey(source CoverSource) (string, error) {
	if err := ValidateCoverSource(source); err != nil {
		return "", err
	}
	data, _ := json.Marshal(source)
	digest := sha256.Sum256(append([]byte("trash-cover-v1\x00"), data...))
	return hex.EncodeToString(digest[:]), nil
}
