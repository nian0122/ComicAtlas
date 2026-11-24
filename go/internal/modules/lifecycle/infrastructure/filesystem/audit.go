package filesystem

import (
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
	"errors"
	"os"
	"sort"
)

func (files *Files) auditState(ctx context.Context, name string, expected lifecycledomain.RetainedFile, modified int64, verify bool) (string, os.FileInfo, error) {
	if err := ctx.Err(); err != nil {
		return "", nil, err
	}
	info, err := files.stat(name)
	if errors.Is(err, os.ErrNotExist) {
		return "absent", nil, nil
	}
	if errors.Is(err, lifecycledomain.ErrConflict) {
		return "unsafe", nil, nil
	}
	if err != nil {
		return "unreadable", nil, nil
	}
	if !verify {
		return "present", info, nil
	}
	if expected.Source.SourceAbsent {
		return "changed", info, nil
	}
	if expected.Source.Size >= 0 && info.Size() != expected.Source.Size || expected.Size >= 0 && info.Size() != expected.Size || modified != 0 && info.ModTime().UnixNano() != modified {
		return "changed", info, nil
	}
	return "valid", info, nil
}

// Audit 只读核对受控清单及文件，不同步、改写清单或修正文件位置。
func (files *Files) Audit(ctx context.Context, id string, candidates []lifecycledomain.FileCandidate, visit func(lifecycledomain.FileObservation) error) (lifecycledomain.JournalObservation, error) {
	files.mutex.Lock()
	defer files.mutex.Unlock()
	observation := lifecycledomain.JournalObservation{State: "valid"}
	if !lifecycledomain.ValidJournalID(id) || lifecycledomain.ValidateCandidates(candidates) != nil {
		return observation, lifecycledomain.ErrValidation
	}
	modifiedByPath := make(map[string]int64)
	byPath := make(map[string]lifecycledomain.RetainedFile)
	journal, err := files.load(id)
	if errors.Is(err, os.ErrNotExist) {
		observation.State = "missing"
	} else if err != nil {
		observation.State = "invalid"
	} else if lifecycledomain.MatchJournalCandidates(journal, candidates) != nil {
		observation.State = "mismatch"
	} else {
		observation.Phase = journal.Phase
		for _, file := range journal.Files {
			byPath[file.Source.Path] = file
			if file.Source.Directory && file.Missing && journal.Phase != "purging" && journal.Phase != "purged" && files.verifyMissing(id, file.Source) != nil {
				observation.MoveConflict = true
			}
		}
		for _, unit := range journal.Moves {
			if !unit.Directory {
				modifiedByPath[unit.Path] = unit.Modified
			}
		}
		if journal.Phase != "purging" && journal.Phase != "purged" {
			for _, unit := range journal.Moves {
				if err := ctx.Err(); err != nil {
					return observation, err
				}
				retainedName := lifecycledomain.RetainedPath(id, unit.Path)
				source, sourceErr := files.moveInfo(unit.Path, unit.Directory)
				retained, retainedErr := files.moveInfo(retainedName, unit.Directory)
				atSource := sourceErr == nil && errors.Is(retainedErr, os.ErrNotExist) && files.verifyMove(ctx, unit.Path, id, unit, source) == nil
				atRetained := retainedErr == nil && errors.Is(sourceErr, os.ErrNotExist) && files.verifyMove(ctx, retainedName, id, unit, retained) == nil
				if !atSource && !atRetained || journal.Phase == "trashed" && !atRetained || journal.Phase == "restored" && !atSource {
					observation.MoveConflict = true
					break
				}
			}
		}
	}
	items := append([]lifecycledomain.FileCandidate{}, candidates...)
	sort.Slice(items, func(i, j int) bool { return items[i].Path < items[j].Path })
	for index, candidate := range items {
		if err = ctx.Err(); err != nil {
			return observation, err
		}
		expected := lifecycledomain.RetainedFile{Source: candidate, Size: -1}
		file, standalone := byPath[candidate.Path]
		if standalone {
			expected = file
		}
		item := lifecycledomain.FileObservation{EntryID: id, Position: index + 1, JournalState: observation.State, JournalPhase: observation.Phase, Prepared: observation.State == "valid", InitiallyMissing: expected.Missing || candidate.SourceAbsent}
		item.DirectoryMember = observation.State == "valid" && !standalone
		item.Role, item.FileName = lifecycledomain.FileLabel(candidate.Path)
		verifySource := observation.Phase != "purged" && observation.Phase != "purging" && observation.Phase != "trashed"
		var source, retained os.FileInfo
		item.SourceState, source, err = files.auditState(ctx, candidate.Path, expected, modifiedByPath[candidate.Path], verifySource)
		if err != nil {
			return observation, err
		}
		item.RetainedState, retained, err = files.auditState(ctx, lifecycledomain.RetainedPath(id, candidate.Path), expected, modifiedByPath[candidate.Path], observation.Phase != "purged")
		if err != nil {
			return observation, err
		}
		item.SameFile = source != nil && retained != nil && os.SameFile(source, retained)
		if err = visit(item); err != nil {
			return observation, err
		}
	}
	return observation, nil
}
