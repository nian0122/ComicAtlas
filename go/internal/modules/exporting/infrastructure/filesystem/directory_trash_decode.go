package filesystem

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"encoding/json"
	"io"
)

// 逐项解码文件数组，在追加超过整批剩余预算的项之前拒绝，不先读取完整清单字节数组。
func decodeDirectoryTrash(input io.Reader, maximumFiles int) (lifecycledomain.FileJournal, error) {
	var value lifecycledomain.FileJournal
	decoder := json.NewDecoder(input)
	decoder.DisallowUnknownFields()
	token, err := decoder.Token()
	if err != nil || token != json.Delim('{') {
		return value, exportdomain.ErrManifest
	}
	seen := map[string]bool{}
	for decoder.More() {
		token, err = decoder.Token()
		if err != nil {
			return value, exportdomain.ErrManifest
		}
		key, ok := token.(string)
		if !ok || seen[key] {
			return value, exportdomain.ErrManifest
		}
		seen[key] = true
		switch key {
		case "version":
			err = decoder.Decode(&value.Version)
		case "id":
			err = decoder.Decode(&value.ID)
		case "phase":
			err = decoder.Decode(&value.Phase)
		case "moves":
			err = decoder.Decode(&value.Moves)
		case "files":
			token, err = decoder.Token()
			if err != nil || token != json.Delim('[') {
				return value, exportdomain.ErrManifest
			}
			value.Files = []lifecycledomain.RetainedFile{}
			for decoder.More() {
				if len(value.Files) >= maximumFiles {
					return value, exportdomain.ErrValidation
				}
				var item lifecycledomain.RetainedFile
				if err = decoder.Decode(&item); err != nil {
					return value, exportdomain.ErrManifest
				}
				if lifecycledomain.ValidateCandidates([]lifecycledomain.FileCandidate{item.Source}) != nil || len(item.SHA256) > 64 || item.Size < 0 {
					return value, exportdomain.ErrManifest
				}
				value.Files = append(value.Files, item)
			}
			token, err = decoder.Token()
			if err != nil || token != json.Delim(']') {
				return value, exportdomain.ErrManifest
			}
		default:
			return value, exportdomain.ErrManifest
		}
		if err != nil {
			return value, exportdomain.ErrManifest
		}
	}
	token, err = decoder.Token()
	if err != nil || token != json.Delim('}') || len(seen) != 5 || decoder.Decode(new(any)) != io.EOF || lifecycledomain.ValidateJournal(value) != nil {
		return value, exportdomain.ErrManifest
	}
	return value, nil
}
