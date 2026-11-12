package filesystem

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"context"
	"encoding/json"
	"io"
	"reflect"
	"strings"
	"unicode/utf8"
)

type checkedReader struct {
	ctx     context.Context
	reader  io.Reader
	rune    [utf8.UTFMax]byte
	pending int
}

func (reader *checkedReader) Read(buffer []byte) (int, error) {
	if err := reader.ctx.Err(); err != nil {
		return 0, err
	}
	n, err := reader.reader.Read(buffer)
	for _, b := range buffer[:n] {
		if reader.pending == 0 && b < utf8.RuneSelf {
			continue
		}
		reader.rune[reader.pending] = b
		reader.pending++
		if utf8.FullRune(reader.rune[:reader.pending]) {
			_, size := utf8.DecodeRune(reader.rune[:reader.pending])
			if size == 1 {
				return 0, recoverydomain.ErrMetadata
			}
			reader.pending = 0
		}
	}
	if err == io.EOF && reader.pending != 0 {
		return 0, recoverydomain.ErrMetadata
	}
	return n, err
}

// 按发布器的静态 Go 结构反射线格式，避免复制一套容易漂移的 JSON 字段名单。
func wireFields(t reflect.Type) map[string]reflect.StructField {
	result := map[string]reflect.StructField{}
	for i := 0; i < t.NumField(); i++ {
		field := t.Field(i)
		if field.PkgPath != "" {
			continue
		}
		tag := strings.Split(field.Tag.Get("json"), ",")[0]
		if tag == "-" {
			continue
		}
		if field.Anonymous && tag == "" {
			nested := field.Type
			for nested.Kind() == reflect.Pointer {
				nested = nested.Elem()
			}
			for key, value := range wireFields(nested) {
				result[key] = value
			}
			continue
		}
		if tag == "" {
			tag = field.Name
		}
		result[tag] = field
	}
	return result
}

func validateWire(ctx context.Context, decoder *json.Decoder, root reflect.Type) error {
	cache := map[reflect.Type]map[string]reflect.StructField{}
	nodes, media := 0, 0
	var walk func(reflect.Type, string, int) error
	walk = func(expected reflect.Type, route string, depth int) error {
		if err := ctx.Err(); err != nil {
			return err
		}
		if depth > 32 {
			return recoverydomain.ErrLimit
		}
		token, err := decoder.Token()
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			return recoverydomain.ErrMetadata
		}
		nullable := expected.Kind() == reflect.Pointer || expected.Kind() == reflect.Slice
		if token == nil {
			if nullable {
				return nil
			}
			return recoverydomain.ErrMetadata
		}
		for expected.Kind() == reflect.Pointer {
			expected = expected.Elem()
		}
		switch expected.Kind() {
		case reflect.Struct:
			if token != json.Delim('{') {
				return recoverydomain.ErrMetadata
			}
			fields := cache[expected]
			if fields == nil {
				fields = wireFields(expected)
				cache[expected] = fields
			}
			seen := map[string]bool{}
			for decoder.More() {
				keyToken, err := decoder.Token()
				if err != nil {
					return recoverydomain.ErrMetadata
				}
				key, ok := keyToken.(string)
				if !ok {
					return recoverydomain.ErrMetadata
				}
				field, known := fields[key]
				if !known || seen[key] {
					return recoverydomain.ErrMetadata
				}
				seen[key] = true
				next := key
				if route != "" {
					next = route + "." + key
				}
				if err = walk(field.Type, next, depth+1); err != nil {
					return err
				}
			}
			if _, err = decoder.Token(); err != nil {
				return recoverydomain.ErrMetadata
			}
			for key, field := range fields {
				optional := strings.Contains(field.Tag.Get("json"), ",omitempty") || route == "" && key == "observations"
				if !optional && !seen[key] {
					return recoverydomain.ErrMetadata
				}
			}
		case reflect.Slice:
			if token != json.Delim('[') {
				return recoverydomain.ErrMetadata
			}
			maximum := 100
			switch route {
			case "snapshot.detail.catalogs", "snapshot.detail.chapters":
				maximum = librarydomain.MaxStructureNodes
			case "snapshot.media", "snapshot.suppressedPaths":
				maximum = librarydomain.MaxSnapshotMedia
			case "observations":
				maximum = 2 * librarydomain.MaxSnapshotMedia
			default:
				if strings.HasSuffix(route, ".audioCodecs") {
					maximum = 32
				}
			}
			count := 0
			for decoder.More() {
				if count >= maximum {
					return recoverydomain.ErrLimit
				}
				count++
				switch route {
				case "snapshot.detail.catalogs", "snapshot.detail.chapters":
					nodes++
					if nodes > librarydomain.MaxStructureNodes {
						return recoverydomain.ErrLimit
					}
				case "snapshot.media", "snapshot.suppressedPaths":
					media++
					if media > librarydomain.MaxSnapshotMedia {
						return recoverydomain.ErrLimit
					}
				}
				if err = walk(expected.Elem(), route+".*", depth+1); err != nil {
					return err
				}
			}
			if _, err = decoder.Token(); err != nil {
				return recoverydomain.ErrMetadata
			}
		case reflect.String:
			value, ok := token.(string)
			if !ok {
				return recoverydomain.ErrMetadata
			}
			if len(value) > 65536 {
				return recoverydomain.ErrLimit
			}
		case reflect.Bool:
			if _, ok := token.(bool); !ok {
				return recoverydomain.ErrMetadata
			}
		case reflect.Int, reflect.Int32, reflect.Int64:
			number, ok := token.(json.Number)
			if !ok {
				return recoverydomain.ErrMetadata
			}
			value, err := number.Int64()
			if err != nil || expected.OverflowInt(value) {
				return recoverydomain.ErrMetadata
			}
		default:
			return recoverydomain.ErrMetadata
		}
		return nil
	}
	if err := walk(root, "", 0); err != nil {
		return err
	}
	if _, err := decoder.Token(); err != io.EOF {
		return recoverydomain.ErrMetadata
	}
	return nil
}
