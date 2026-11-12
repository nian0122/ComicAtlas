package filesystem

import (
	exportports "comicatlas.local/backend/internal/modules/exporting/application/ports"
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
)

var _ exportports.DirectoryPlanningFiles = (*Files)(nil)

func (files *Files) ResolveDirectoryTrash(ctx context.Context, snapshots []exportdomain.DirectoryInventorySnapshot) ([]exportdomain.DirectoryTrashResolution, error) {
	files.directoryMutex.Lock()
	defer files.directoryMutex.Unlock()
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if _, err := exportdomain.DirectoryInventoryIdentities(snapshots); err != nil {
		return nil, err
	}
	values := []exportdomain.DirectoryTrashResolution{}
	seen := map[string]bool{}
	totalBytes, totalFiles := int64(0), 0
	for _, snapshot := range snapshots {
		for _, entry := range snapshot.Inventory.Trash {
			if err := ctx.Err(); err != nil {
				return nil, err
			}
			if seen[entry.ID] || len(values) >= exportdomain.MaxNodes {
				return nil, exportdomain.ErrManifest
			}
			seen[entry.ID] = true
			value, err := files.resolveDirectoryTrash(ctx, snapshot.Inventory.Comic.ID, entry, exportdomain.MaxDirectoryPlanBytes-totalBytes, exportdomain.MaxDirectoryFiles-totalFiles)
			if err != nil {
				return nil, err
			}
			totalBytes += value.Manifest.Size
			totalFiles += len(value.Journal.Files)
			if totalBytes > exportdomain.MaxDirectoryPlanBytes || totalFiles > exportdomain.MaxDirectoryFiles {
				return nil, exportdomain.ErrValidation
			}
			values = append(values, value)
		}
	}
	return values, nil
}

func (files *Files) resolveDirectoryTrash(ctx context.Context, comic string, entry exportdomain.DirectoryTrash, remainingBytes int64, remainingFiles int) (value exportdomain.DirectoryTrashResolution, err error) {
	value.ComicID, value.EntryID = comic, entry.ID
	name := "trash/" + entry.ID + "/manifest.json"
	before, err := files.directoryEntry(name)
	if err != nil {
		return value, err
	}
	if before == nil || !before.Mode().IsRegular() || before.Size() > lifecycledomain.MaxJournalBytes {
		return value, exportdomain.ErrManifest
	}
	if before.Size() > remainingBytes {
		return value, exportdomain.ErrValidation
	}
	input, err := files.root.Open(name)
	if err != nil {
		return value, err
	}
	defer func() { err = errors.Join(err, input.Close()) }()
	opened, err := input.Stat()
	if err != nil || !os.SameFile(before, opened) || opened.Size() != before.Size() {
		return value, exportdomain.ErrManifest
	}
	hash := sha256.New()
	count := &directoryTrashCounter{input: io.LimitReader(input, lifecycledomain.MaxJournalBytes+1), ctx: ctx}
	value.Journal, err = decodeDirectoryTrash(io.TeeReader(count, hash), remainingFiles)
	if err != nil {
		if ctx.Err() != nil {
			return value, ctx.Err()
		}
		return value, err
	}
	if err = ctx.Err(); err != nil {
		return value, err
	}
	if value.Journal.ID != entry.ID || value.Journal.Phase != "trashed" || lifecycledomain.MatchJournalCandidates(value.Journal, entry.Candidates) != nil || count.bytes != before.Size() {
		return value, exportdomain.ErrManifest
	}
	after, err := input.Stat()
	if err != nil {
		return value, err
	}
	current, err := files.directoryEntry(name)
	if err != nil {
		return value, err
	}
	if current == nil || !os.SameFile(before, current) || !os.SameFile(before, after) || after.Size() != before.Size() || !before.ModTime().Equal(after.ModTime()) {
		return value, exportdomain.ErrManifest
	}
	value.Manifest = exportdomain.DirectoryStoredFile{Path: name, Size: count.bytes, SHA256: hex.EncodeToString(hash.Sum(nil))}
	for _, item := range value.Journal.Files {
		if err = ctx.Err(); err != nil {
			return value, err
		}
		original, err := files.directoryEntry(item.Source.Path)
		if err != nil {
			return value, err
		}
		if original != nil {
			return value, exportdomain.ErrConflict
		}
		retained, err := files.directoryEntry(lifecycledomain.RetainedPath(entry.ID, item.Source.Path))
		if err != nil {
			return value, err
		}
		if item.Missing {
			if retained != nil {
				return value, exportdomain.ErrConflict
			}
		} else if retained == nil || !retained.Mode().IsRegular() || retained.Size() != item.Size {
			return value, exportdomain.ErrSource
		}
	}
	return value, nil
}

type directoryTrashCounter struct {
	input io.Reader
	bytes int64
	ctx   context.Context
}

func (counter *directoryTrashCounter) Read(data []byte) (int, error) {
	if err := counter.ctx.Err(); err != nil {
		return 0, err
	}
	n, err := counter.input.Read(data)
	counter.bytes += int64(n)
	return n, err
}
