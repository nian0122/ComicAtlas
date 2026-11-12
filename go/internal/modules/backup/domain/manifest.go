package domain

import (
	"bytes"
	"encoding/json"
	"io"
	"unicode/utf8"
)

// 清单只包含固定的四层对象，逐层核对字段并拒绝重复键、别名和 null。
func DecodeManifest(data []byte) (Manifest, error) {
	var value Manifest
	if len(data) > MaxManifestBytes || !utf8.Valid(data) {
		return value, ErrInvalid
	}
	root, err := exactObject(data, "format", "version", "id", "createdAt", "database", "catalog", "summary")
	if err != nil {
		return value, err
	}
	for _, object := range []struct {
		name string
		keys []string
	}{{"database", []string{"format", "migrationCount", "migrationChecksum"}}, {"catalog", []string{"size", "sha256"}}, {"summary", []string{"files", "directories", "bytes"}}} {
		if _, err := exactObject(root[object.name], object.keys...); err != nil {
			return value, err
		}
	}
	if json.Unmarshal(data, &value) != nil || ValidateManifest(value) != nil {
		return Manifest{}, ErrInvalid
	}
	return value, nil
}

func EncodeManifest(value Manifest) ([]byte, error) {
	if ValidateManifest(value) != nil {
		return nil, ErrValidation
	}
	data, err := json.Marshal(value)
	if err != nil || len(data) > MaxManifestBytes {
		return nil, ErrValidation
	}
	return append(data, '\n'), nil
}

func exactObject(data []byte, names ...string) (map[string]json.RawMessage, error) {
	decoder := json.NewDecoder(bytes.NewReader(data))
	token, err := decoder.Token()
	if err != nil || token != json.Delim('{') {
		return nil, ErrInvalid
	}
	allowed := map[string]bool{}
	for _, name := range names {
		allowed[name] = true
	}
	values := map[string]json.RawMessage{}
	for decoder.More() {
		token, err = decoder.Token()
		name, ok := token.(string)
		if err != nil || !ok || !allowed[name] || values[name] != nil {
			return nil, ErrInvalid
		}
		var raw json.RawMessage
		if decoder.Decode(&raw) != nil || bytes.Equal(bytes.TrimSpace(raw), []byte("null")) {
			return nil, ErrInvalid
		}
		values[name] = raw
	}
	if token, err = decoder.Token(); err != nil || token != json.Delim('}') || len(values) != len(names) {
		return nil, ErrInvalid
	}
	if _, err = decoder.Token(); err != io.EOF {
		return nil, ErrInvalid
	}
	return values, nil
}
