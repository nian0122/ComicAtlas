package domain

import (
	"encoding/json"
	"io"
	"strings"
)

func DecodeDirectoryPreflightIDs(data string, count int) ([]string, error) {
	if count < 1 || count > MaxDirectoryBooks || len(data) > 2<<20 {
		return nil, ErrManifest
	}
	decoder := json.NewDecoder(strings.NewReader(data))
	start, err := decoder.Token()
	if err != nil || start != json.Delim('[') {
		return nil, ErrManifest
	}
	ids := make([]string, 0, count)
	for decoder.More() {
		if len(ids) >= count {
			return nil, ErrManifest
		}
		var id string
		if decoder.Decode(&id) != nil {
			return nil, ErrManifest
		}
		ids = append(ids, id)
	}
	end, err := decoder.Token()
	if err != nil || end != json.Delim(']') || len(ids) != count || DirectoryIDs(ids) != nil {
		return nil, ErrManifest
	}
	var extra any
	if decoder.Decode(&extra) != io.EOF {
		return nil, ErrManifest
	}
	return ids, nil
}

func ValidateDirectoryPreflight(request DirectoryPreflightRequest) error {
	if !TaskID(request.ID) || !digest(request.TokenHash) || request.CreatedAt.IsZero() || DirectoryIDs(request.ComicIDs) != nil {
		return ErrValidation
	}
	return nil
}

func DirectoryPreflightCapacity(active, ready int) error {
	if active < 0 || ready < 0 || active+ready >= MaxDirectoryPreviews {
		return ErrConflict
	}
	return nil
}
