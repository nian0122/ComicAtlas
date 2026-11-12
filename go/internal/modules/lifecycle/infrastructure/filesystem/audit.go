package filesystem

import (
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
	"errors"
	"os"
	"sort"
)

func (files *Files) auditState(ctx context.Context, name string, expected lifecycledomain.RetainedFile, verify bool) (string, os.FileInfo, error) {
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
	if expected.Source.Size >= 0 && info.Size() != expected.Source.Size || expected.Size >= 0 && info.Size() != expected.Size {
		return "changed", info, nil
	}
	if expected.SHA256 == "" && expected.Source.SHA256 == "" || info.Size() > lifecycledomain.MaxFileBytes {
		return "unmeasured", info, nil
	}
	size, digest, err := files.digest(ctx, name)
	if ctx.Err() != nil {
		return "", nil, ctx.Err()
	}
	if errors.Is(err, lifecycledomain.ErrChanged) {
		return "changed", info, nil
	}
	if errors.Is(err, lifecycledomain.ErrConflict) {
		return "unsafe", info, nil
	}
	if err != nil {
		return "unreadable", info, nil
	}
	if expected.Source.Size >= 0 && size != expected.Source.Size || expected.Source.SHA256 != "" && digest != expected.Source.SHA256 {
		return "changed", info, nil
	}
	if expected.Size >= 0 && size != expected.Size || expected.SHA256 != "" && digest != expected.SHA256 {
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
	journal, err := files.load(id)
	if errors.Is(err, os.ErrNotExist) {
		observation.State = "missing"
	} else if err != nil {
		observation.State = "invalid"
	} else if lifecycledomain.MatchJournalCandidates(journal, candidates) != nil {
		observation.State = "mismatch"
	} else {
		observation.Phase = journal.Phase
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
		if observation.State == "valid" {
			expected = journal.Files[index]
		}
		item := lifecycledomain.FileObservation{EntryID: id, Position: index + 1, JournalState: observation.State, JournalPhase: observation.Phase, Prepared: observation.State == "valid", InitiallyMissing: expected.Missing}
		item.Role, item.FileName = lifecycledomain.FileLabel(candidate.Path)
		verifySource := observation.Phase != "purged" && observation.Phase != "purging" && observation.Phase != "trashed"
		var source, retained os.FileInfo
		item.SourceState, source, err = files.auditState(ctx, candidate.Path, expected, verifySource)
		if err != nil {
			return observation, err
		}
		item.RetainedState, retained, err = files.auditState(ctx, lifecycledomain.RetainedPath(id, candidate.Path), expected, observation.Phase != "purged")
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
