package domain

import (
	"crypto/sha256"
	"encoding/hex"
	"path"
	"path/filepath"
	"slices"
	"sort"
	"strings"
)

func ValidJournalID(value string) bool {
	if len(value) == 0 || len(value) > 128 {
		return false
	}
	for _, c := range value {
		if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-') {
			return false
		}
	}
	return true
}

func ValidSourcePath(name string) bool {
	if name == "" || len(name) > 2048 || path.Clean(name) != name || !filepath.IsLocal(filepath.FromSlash(name)) || strings.ContainsAny(name, "\\:\x00") || strings.HasPrefix(name, "/") {
		return false
	}
	root, rest, found := strings.Cut(name, "/")
	if !found || rest == "" || strings.HasPrefix(rest, "../") || rest == ".." {
		return false
	}
	return root == "hq" || root == "lq" || root == "thumbs" || root == "metadata" || root == "retired"
}

func validDigest(value string) bool {
	decoded, err := hex.DecodeString(value)
	return err == nil && len(decoded) == sha256.Size && strings.ToLower(value) == value
}

func CandidateForFile(name string, size int64, hash string, sourceAbsent bool) FileCandidate {
	value := FileCandidate{Path: name, Size: size, SHA256: hash}
	if sourceAbsent && hash == "" {
		value.Size = 0
		value.SourceAbsent = true
	}
	return value
}

func ValidateCandidates(items []FileCandidate) error {
	if len(items) > MaxFiles {
		return ErrValidation
	}
	seen := map[string]bool{}
	for _, item := range items {
		if !ValidSourcePath(item.Path) || seen[item.Path] || item.Size < -1 || item.SHA256 != "" && !validDigest(item.SHA256) {
			return ErrValidation
		}
		if item.SourceAbsent && (item.Size != 0 || item.SHA256 != "" || !strings.HasPrefix(item.Path, "hq/")) {
			return ErrValidation
		}
		seen[item.Path] = true
	}
	return nil
}

func ValidateJournal(value FileJournal) error {
	if value.Version != JournalVersion || !ValidJournalID(value.ID) || len(value.Files) > MaxFiles || len(value.Moves) > MaxFiles+2 {
		return ErrJournal
	}
	switch value.Phase {
	case "prepared", "trashed", "restoring", "restored", "purging", "purged":
	default:
		return ErrJournal
	}
	moves := make(map[string]MoveUnit, len(value.Moves))
	for _, unit := range value.Moves {
		if !ValidSourcePath(unit.Path) || moves[unit.Path].Path != "" || !validDigest(unit.Proof) || unit.Size < 0 {
			return ErrJournal
		}
		if unit.Directory && (!(strings.HasPrefix(unit.Path, "hq/") || strings.HasPrefix(unit.Path, "lq/")) || strings.Count(unit.Path, "/") > 2) {
			return ErrJournal
		}
		moves[unit.Path] = unit
	}
	for _, unit := range value.Moves {
		for parent := path.Dir(unit.Path); parent != "."; parent = path.Dir(parent) {
			if moves[parent].Path != "" {
				return ErrJournal
			}
		}
	}
	items := make([]FileCandidate, 0, len(value.Files))
	covered := map[string]bool{}
	for _, file := range value.Files {
		if !ValidSourcePath(file.Source.Path) {
			return ErrJournal
		}
		items = append(items, file.Source)
		if file.Missing {
			if file.Size != 0 || file.SHA256 != "" {
				return ErrJournal
			}
			continue
		}
		if file.Source.SourceAbsent || file.Size < 0 || file.Source.Size >= 0 && file.Size != file.Source.Size || file.SHA256 != file.Source.SHA256 {
			return ErrJournal
		}
		unit, ok := MoveForPath(moves, file.Source.Path)
		if !ok || !unit.Directory && (unit.Size != file.Size || file.SHA256 != "" && unit.Proof != file.SHA256) {
			return ErrJournal
		}
		covered[unit.Path] = true
	}
	for _, unit := range value.Moves {
		if !unit.Directory && !covered[unit.Path] {
			return ErrJournal
		}
	}
	if ValidateCandidates(items) != nil {
		return ErrJournal
	}
	return nil
}

func RetainedPath(id, source string) string {
	return "trash/" + id + "/files/" + source
}

func MoveMarkerPath(directory, id string) string {
	return directory + "/.comicatlas-trash-" + id
}

func MoveForPath(moves map[string]MoveUnit, name string) (MoveUnit, bool) {
	if unit, exists := moves[name]; exists {
		return unit, !unit.Directory
	}
	for parent := path.Dir(name); parent != "." && parent != "/"; parent = path.Dir(parent) {
		if unit, exists := moves[parent]; exists {
			return unit, unit.Directory
		}
	}
	return MoveUnit{}, false
}

func TrashDirectories(target Target) ([]string, error) {
	if target.Type == "media" {
		return nil, nil
	}
	if !ValidJournalID(target.ComicID) {
		return nil, ErrValidation
	}
	scope := target.ComicID
	switch target.Type {
	case "comic":
	case "chapter":
		if !ValidJournalID(target.ChapterID) {
			return nil, ErrValidation
		}
		scope += "/" + target.ChapterID
	default:
		return nil, ErrValidation
	}
	return []string{"hq/" + scope, "lq/" + scope}, nil
}

func MatchTrashDirectories(journal FileJournal, target Target) error {
	roots, err := TrashDirectories(target)
	if err != nil {
		return err
	}
	for _, unit := range journal.Moves {
		if unit.Directory && !slices.Contains(roots, unit.Path) {
			return ErrJournal
		}
		if !unit.Directory && len(roots) != 0 && (strings.HasPrefix(unit.Path, "hq/") || strings.HasPrefix(unit.Path, "lq/")) {
			return ErrJournal
		}
	}
	return nil
}

func MatchJournalCandidates(value FileJournal, candidates []FileCandidate) error {
	if ValidateJournal(value) != nil || ValidateCandidates(candidates) != nil || len(value.Files) != len(candidates) {
		return ErrJournal
	}
	items := append([]FileCandidate{}, candidates...)
	sort.Slice(items, func(i, j int) bool { return items[i].Path < items[j].Path })
	for index, file := range value.Files {
		if file.Source != items[index] {
			return ErrJournal
		}
	}
	return nil
}
