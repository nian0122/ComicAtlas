package filesystem

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"

	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"comicatlas.local/backend/internal/platform/relocate"
)

func (files *Files) load(id string) (lifecycledomain.FileJournal, error) {
	var value lifecycledomain.FileJournal
	if !lifecycledomain.ValidJournalID(id) {
		return value, lifecycledomain.ErrValidation
	}
	name := "trash/" + id + "/manifest.json"
	info, err := files.stat(name)
	if err != nil {
		return value, err
	}
	if info.Size() > lifecycledomain.MaxJournalBytes {
		return value, lifecycledomain.ErrJournal
	}
	input, err := files.root.Open(filepath.FromSlash(name))
	if err != nil {
		return value, err
	}
	defer input.Close()
	opened, err := input.Stat()
	if err != nil {
		return value, err
	}
	if !opened.Mode().IsRegular() || !os.SameFile(info, opened) {
		return value, lifecycledomain.ErrJournal
	}
	data, err := io.ReadAll(io.LimitReader(input, lifecycledomain.MaxJournalBytes+1))
	if err != nil {
		return value, err
	}
	if len(data) > lifecycledomain.MaxJournalBytes {
		return value, lifecycledomain.ErrJournal
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if decoder.Decode(&value) != nil || decoder.Decode(new(any)) != io.EOF || value.ID != id || lifecycledomain.ValidateJournal(value) != nil {
		return lifecycledomain.FileJournal{}, lifecycledomain.ErrJournal
	}
	return value, nil
}

func (files *Files) save(ctx context.Context, value lifecycledomain.FileJournal, first bool) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if err := lifecycledomain.ValidateJournal(value); err != nil {
		return err
	}
	var err error
	name := "trash/" + value.ID + "/manifest.json"
	if err = files.parents(name, true); err != nil {
		return err
	}
	var suffix [16]byte
	if _, err = rand.Read(suffix[:]); err != nil {
		return err
	}
	temporary := path.Dir(name) + "/.journal-" + hex.EncodeToString(suffix[:]) + ".tmp"
	output, err := files.root.OpenFile(filepath.FromSlash(temporary), os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0600)
	if err != nil {
		return err
	}
	defer files.root.Remove(filepath.FromSlash(temporary))
	writer := bufio.NewWriterSize(&boundedWriter{writer: output, remaining: lifecycledomain.MaxJournalBytes}, 64<<10)
	writeErr := writeJournal(writer, value)
	if writeErr == nil {
		writeErr = writer.Flush()
	}
	if err = errors.Join(writeErr, output.Sync(), output.Close()); err != nil {
		return err
	}
	if err = ctx.Err(); err != nil {
		return err
	}
	if first {
		// 首次发布不覆盖同 ID 清单，并支持不提供硬链接的媒体文件系统。
		info, statErr := files.root.Lstat(filepath.FromSlash(temporary))
		if statErr != nil {
			return statErr
		}
		err = relocate.WithinRoot(files.root, temporary, name, info)
	} else {
		err = files.root.Rename(filepath.FromSlash(temporary), filepath.FromSlash(name))
	}
	if err != nil {
		return err
	}
	return files.syncDirectory(path.Dir(name))
}

type boundedWriter struct {
	writer    io.Writer
	remaining int
}

func (writer *boundedWriter) Write(data []byte) (int, error) {
	if len(data) > writer.remaining {
		return 0, lifecycledomain.ErrValidation
	}
	count, err := writer.writer.Write(data)
	writer.remaining -= count
	return count, err
}

// 逐项编码，避免先为超限清单分配完整 JSON 再拒绝。
func writeJournal(writer io.Writer, value lifecycledomain.FileJournal) error {
	if _, err := fmt.Fprintf(writer, `{"version":%d,"id":%q,"phase":%q,"files":[`, value.Version, value.ID, value.Phase); err != nil {
		return err
	}
	for index, item := range value.Files {
		if index > 0 {
			if _, err := io.WriteString(writer, ","); err != nil {
				return err
			}
		}
		data, err := json.Marshal(item)
		if err != nil {
			return err
		}
		if _, err = writer.Write(data); err != nil {
			return err
		}
	}
	if _, err := io.WriteString(writer, `],"moves":`); err != nil {
		return err
	}
	if err := json.NewEncoder(writer).Encode(value.Moves); err != nil {
		return err
	}
	_, err := io.WriteString(writer, "}")
	return err
}

// Prepare 为目录写入随目录搬移的凭证。只有独立文件需要内容摘要。
func (files *Files) Prepare(ctx context.Context, id string, target lifecycledomain.Target, candidates []lifecycledomain.FileCandidate, visit func(lifecycledomain.FileResult) error) (lifecycledomain.FileJournal, error) {
	files.mutex.Lock()
	defer files.mutex.Unlock()
	var value lifecycledomain.FileJournal
	if err := ctx.Err(); err != nil {
		return value, err
	}
	roots, err := lifecycledomain.TrashDirectories(target)
	if err != nil || !lifecycledomain.ValidJournalID(id) || lifecycledomain.ValidateCandidates(candidates) != nil {
		return value, lifecycledomain.ErrValidation
	}
	previous, err := files.load(id)
	if err == nil {
		if lifecycledomain.MatchJournalCandidates(previous, candidates) != nil || lifecycledomain.MatchTrashDirectories(previous, target) != nil {
			return value, lifecycledomain.ErrConflict
		}
		return previous, nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return value, err
	}
	items := append([]lifecycledomain.FileCandidate{}, candidates...)
	sort.Slice(items, func(i, j int) bool { return items[i].Path < items[j].Path })
	value = lifecycledomain.FileJournal{Version: lifecycledomain.JournalVersion, ID: id, Phase: "prepared", Files: []lifecycledomain.RetainedFile{}, Moves: []lifecycledomain.MoveUnit{}}
	for _, root := range roots {
		_, err := files.moveInfo(root, true)
		if err == nil {
			value.Moves = append(value.Moves, lifecycledomain.MoveUnit{Path: root, Directory: true})
		} else if !errors.Is(err, os.ErrNotExist) {
			return value, err
		}
	}
	for index, item := range items {
		if err = ctx.Err(); err != nil {
			return value, err
		}
		file := lifecycledomain.RetainedFile{Source: item}
		directory := false
		if len(roots) != 0 && (strings.HasPrefix(item.Path, "hq/") || strings.HasPrefix(item.Path, "lq/")) {
			for _, root := range roots {
				directory = directory || strings.HasPrefix(item.Path, root+"/")
			}
			if !directory {
				return value, reportFile(visit, id, "trash", "preparing", index+1, file, lifecycledomain.ErrConflict)
			}
		}
		info, err := files.stat(item.Path)
		switch {
		case errors.Is(err, os.ErrNotExist):
			file.Missing = true
		case err != nil:
			return value, reportFile(visit, id, "trash", "preparing", index+1, file, err)
		case item.SourceAbsent:
			return value, reportFile(visit, id, "trash", "preparing", index+1, file, lifecycledomain.ErrConflict)
		case item.Size >= 0 && item.Size != info.Size():
			return value, reportFile(visit, id, "trash", "preparing", index+1, file, lifecycledomain.ErrChanged)
		default:
			file.Size, file.SHA256 = info.Size(), item.SHA256
			if !directory {
				proof := item.SHA256
				if proof == "" {
					size, digest, err := files.digest(ctx, item.Path)
					if err == nil && size != info.Size() {
						err = lifecycledomain.ErrChanged
					}
					if err != nil {
						return value, reportFile(visit, id, "trash", "preparing", index+1, file, err)
					}
					proof = digest
				}
				value.Moves = append(value.Moves, lifecycledomain.MoveUnit{Path: item.Path, Size: info.Size(), Proof: proof})
			}
		}
		value.Files = append(value.Files, file)
	}
	err = nil
	for index := range value.Moves {
		unit := &value.Moves[index]
		if !unit.Directory {
			continue
		}
		if err = ctx.Err(); err != nil {
			break
		}
		unit.Proof, err = files.prepareMoveMarker(unit.Path, id)
		if err != nil {
			break
		}
	}
	if err == nil {
		err = files.save(ctx, value, true)
	}
	if err != nil {
		if _, statErr := files.stat("trash/" + id + "/manifest.json"); errors.Is(statErr, os.ErrNotExist) {
			for _, unit := range value.Moves {
				if unit.Directory && unit.Proof != "" {
					err = errors.Join(err, files.removeMoveMarker(unit.Path, id, unit.Proof))
				}
			}
		}
		return value, err
	}
	return value, nil
}

func (files *Files) Journal(ctx context.Context, id string) (lifecycledomain.FileJournal, error) {
	files.mutex.Lock()
	defer files.mutex.Unlock()
	if err := ctx.Err(); err != nil {
		return lifecycledomain.FileJournal{}, err
	}
	return files.load(id)
}
