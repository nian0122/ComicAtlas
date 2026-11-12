package httpclient

import (
	"bytes"
	aidomain "comicatlas.local/backend/internal/modules/ai/domain"
	"encoding/json"
	"io"
	"unicode/utf8"
)

func decodeResponse(data []byte, input aidomain.Request) (aidomain.Candidate, error) {
	var empty aidomain.Candidate
	if len(data) > aidomain.MaxResponseBytes || !utf8.Valid(data) {
		return empty, aidomain.ErrProtocol
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	budget := 4096
	if inspectJSON(decoder, 0, &budget) != nil {
		return empty, aidomain.ErrProtocol
	}
	if _, err := decoder.Token(); err != io.EOF {
		return empty, aidomain.ErrProtocol
	}
	root, err := exactObject(data, "version", "requestId", "candidate")
	if err != nil {
		return empty, err
	}
	candidate, err := exactObject(root["candidate"], "title", "author", "description", "categoryId", "tags", "warnings")
	if err != nil {
		return empty, err
	}
	var tags []json.RawMessage
	if err = json.Unmarshal(candidate["tags"], &tags); err != nil || tags == nil || len(tags) > aidomain.MaxCandidateTags {
		return empty, aidomain.ErrProtocol
	}
	for _, raw := range tags {
		if _, err = exactObject(raw, "name", "existingId", "evidence"); err != nil {
			return empty, err
		}
	}
	var response wireResponse
	if err = json.Unmarshal(data, &response); err != nil || response.Version != aidomain.ProtocolVersion || response.RequestID != input.RequestID || aidomain.ValidateCandidate(input, response.Candidate) != nil {
		return empty, aidomain.ErrProtocol
	}
	return response.Candidate, nil
}

func exactObject(data []byte, keys ...string) (map[string]json.RawMessage, error) {
	var object map[string]json.RawMessage
	if json.Unmarshal(data, &object) != nil || len(object) != len(keys) {
		return nil, aidomain.ErrProtocol
	}
	for _, key := range keys {
		if _, ok := object[key]; !ok {
			return nil, aidomain.ErrProtocol
		}
	}
	return object, nil
}

// Token 预检在有界响应内拒绝重复键和深层结构，不让后解码覆盖前值。
func inspectJSON(decoder *json.Decoder, depth int, budget *int) error {
	*budget--
	if depth > 8 || *budget < 0 {
		return aidomain.ErrProtocol
	}
	token, err := decoder.Token()
	if err != nil {
		return aidomain.ErrProtocol
	}
	delimiter, compound := token.(json.Delim)
	if !compound {
		return nil
	}
	switch delimiter {
	case '{':
		seen := map[string]bool{}
		for decoder.More() {
			*budget--
			key, err := decoder.Token()
			if err != nil || *budget < 0 {
				return aidomain.ErrProtocol
			}
			name, ok := key.(string)
			if !ok || seen[name] {
				return aidomain.ErrProtocol
			}
			seen[name] = true
			if err = inspectJSON(decoder, depth+1, budget); err != nil {
				return err
			}
		}
		end, err := decoder.Token()
		if err != nil || end != json.Delim('}') {
			return aidomain.ErrProtocol
		}
	case '[':
		for decoder.More() {
			if err = inspectJSON(decoder, depth+1, budget); err != nil {
				return err
			}
		}
		end, err := decoder.Token()
		if err != nil || end != json.Delim(']') {
			return aidomain.ErrProtocol
		}
	default:
		return aidomain.ErrProtocol
	}
	return nil
}
