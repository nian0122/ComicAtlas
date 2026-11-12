package filesystem

import (
	"bytes"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	storagedomain "comicatlas.local/backend/internal/modules/storage/domain"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"reflect"
)

func (files *Files) ReadSidecar(ctx context.Context, comic string) (result recoverydomain.Document, err error) {
	defer func() {
		if ctx.Err() != nil {
			err = errors.Join(err, ctx.Err())
		}
		if err != nil {
			result = recoverydomain.Document{}
		}
	}()
	if !recoverydomain.Key(comic) {
		return result, recoverydomain.ErrValidation
	}
	if err = ctx.Err(); err != nil {
		return result, err
	}
	name := "metadata/" + comic + ".json"
	if err = files.parents(name); err != nil {
		return result, err
	}
	before, err := files.root.Lstat(name)
	if errors.Is(err, os.ErrNotExist) {
		if files.observe != nil {
			if recordErr := files.observe(name, nil); recordErr != nil {
				return result, recordErr
			}
		}
		return result, recoverydomain.ErrMissing
	}
	if err != nil {
		return result, err
	}
	if !before.Mode().IsRegular() {
		return result, recoverydomain.ErrUnsafe
	}
	if before.Size() < 1 || before.Size() > recoverydomain.MaxSidecarBytes {
		return result, recoverydomain.ErrLimit
	}
	input, err := files.root.Open(name)
	if err != nil {
		return result, err
	}
	defer func() { err = errors.Join(err, input.Close()) }()
	opened, err := input.Stat()
	if err != nil || !os.SameFile(before, opened) || opened.Size() != before.Size() {
		return result, recoverydomain.ErrChanged
	}
	first := sha256.New()
	limit := &io.LimitedReader{R: input, N: before.Size() + 1}
	decoder := json.NewDecoder(io.TeeReader(&checkedReader{ctx: ctx, reader: limit}, first))
	decoder.UseNumber()
	// 先按真实 Go 线格式逐项验证数量、重复键和准确字段大小写，再分配快照数组。
	if err = validateWire(ctx, decoder, reflect.TypeOf(storagedomain.MetadataSidecar{})); err != nil {
		return result, err
	}
	if limit.N != 1 {
		return result, recoverydomain.ErrChanged
	}
	if _, err = input.Seek(0, io.SeekStart); err != nil {
		return result, err
	}
	second := sha256.New()
	limit = &io.LimitedReader{R: input, N: before.Size() + 1}
	decoder = json.NewDecoder(io.TeeReader(&checkedReader{ctx: ctx, reader: limit}, second))
	decoder.DisallowUnknownFields()
	decoder.UseNumber()
	result.Sidecar, err = decodeSidecar(decoder)
	if err != nil {
		return result, err
	}
	var extra any
	if decoder.Decode(&extra) != io.EOF {
		return result, recoverydomain.ErrMetadata
	}
	if limit.N != 1 || !bytes.Equal(first.Sum(nil), second.Sum(nil)) {
		return result, recoverydomain.ErrChanged
	}
	if err = recoverydomain.ValidateSidecar(comic, result.Sidecar); err != nil {
		return result, err
	}
	after, err := input.Stat()
	if err != nil {
		return result, err
	}
	current, err := files.root.Lstat(name)
	if err != nil {
		return result, err
	}
	if !os.SameFile(before, current) || !os.SameFile(before, after) || after.Size() != before.Size() || current.Size() != before.Size() || !after.ModTime().Equal(before.ModTime()) || !current.ModTime().Equal(before.ModTime()) {
		return result, recoverydomain.ErrChanged
	}
	if err = files.parents(name); err != nil {
		return result, err
	}
	result.Path, result.Size, result.SHA256 = name, before.Size(), hex.EncodeToString(first.Sum(nil))
	if files.observe != nil {
		err = files.observe(name, before)
	}
	return result, nil
}

func decodeSidecar(decoder *json.Decoder) (value recoverydomain.Sidecar, err error) {
	start, err := decoder.Token()
	if err != nil || start != json.Delim('{') {
		return value, recoverydomain.ErrMetadata
	}
	for decoder.More() {
		token, err := decoder.Token()
		if err != nil {
			return value, err
		}
		key, ok := token.(string)
		if !ok {
			return value, recoverydomain.ErrMetadata
		}
		switch key {
		case "version":
			err = decoder.Decode(&value.Version)
		case "producer":
			err = decoder.Decode(&value.Producer)
		case "syncRevision":
			err = decoder.Decode(&value.SyncRevision)
		case "observedAt":
			err = decoder.Decode(&value.ObservedAt)
		case "taskId":
			err = decoder.Decode(&value.TaskID)
		case "baselineSha256":
			err = decoder.Decode(&value.BaselineSHA256)
		case "snapshot":
			err = decoder.Decode(&value.Snapshot)
		case "observations":
			value.HasObservations = true
			var start json.Token
			start, err = decoder.Token()
			if err != nil {
				break
			}
			if start == nil {
				continue
			}
			if start != json.Delim('[') {
				return value, recoverydomain.ErrMetadata
			}
			for decoder.More() {
				if value.ObservationCount >= 2*librarydomain.MaxSnapshotMedia {
					return value, recoverydomain.ErrLimit
				}
				var item storagedomain.MediaObservation
				if err = decoder.Decode(&item); err != nil {
					return value, recoverydomain.ErrMetadata
				}
				value.ObservationCount++
				if item.Position != value.ObservationCount {
					return value, recoverydomain.ErrMetadata
				}
			}
			_, err = decoder.Token()
		default:
			return value, recoverydomain.ErrMetadata
		}
		if err != nil {
			return value, recoverydomain.ErrMetadata
		}
	}
	_, err = decoder.Token()
	return value, err
}
