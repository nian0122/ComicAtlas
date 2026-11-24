package domain

import (
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

func validProof(value string) bool {
	if len(value) != 64 || strings.ToLower(value) != value {
		return false
	}
	for _, c := range value {
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return false
		}
	}
	return true
}

func validStamp(value string) bool {
	if len(value) != 32 || strings.ToLower(value) != value {
		return false
	}
	for _, c := range value {
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return false
		}
	}
	return true
}

func CandidateForFile(name string, size int64, sourceAbsent bool) FileCandidate {
	value := FileCandidate{Path: name, Size: size}
	if sourceAbsent {
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
		if !ValidSourcePath(item.Path) || seen[item.Path] || item.Size < -1 {
			return ErrValidation
		}
		if item.Directory && (item.Size != -1 || item.SourceAbsent || !validComicRoot(item.Path)) {
			return ErrValidation
		}
		if item.SourceAbsent && (item.Size != 0 || !strings.HasPrefix(item.Path, "hq/")) {
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
		if !ValidSourcePath(unit.Path) || moves[unit.Path].Path != "" || unit.Size < 0 {
			return ErrJournal
		}
		if unit.Directory && (!validProof(unit.Proof) || unit.Modified != 0 || !(strings.HasPrefix(unit.Path, "hq/") || strings.HasPrefix(unit.Path, "lq/")) || strings.Count(unit.Path, "/") > 2) {
			return ErrJournal
		}
		if !unit.Directory && (unit.Proof != "" || unit.Modified == 0) {
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
	directoryCount := 0
	items := make([]FileCandidate, 0, len(value.Files))
	covered := map[string]bool{}
	for _, file := range value.Files {
		if !ValidSourcePath(file.Source.Path) {
			return ErrJournal
		}
		if file.Source.Directory {
			directoryCount++
			if directoryCount > 2 || file.Size != 0 {
				return ErrJournal
			}
		}
		items = append(items, file.Source)
		if file.Missing {
			if _, hasMove := moves[file.Source.Path]; file.Size != 0 || hasMove {
				return ErrJournal
			}
			continue
		}
		if file.Source.SourceAbsent || file.Size < 0 || file.Source.Size >= 0 && file.Size != file.Source.Size {
			return ErrJournal
		}
		unit, ok := MoveForPath(moves, file.Source.Path)
		if !ok || (unit.Path == file.Source.Path && unit.Directory) != file.Source.Directory || !unit.Directory && unit.Size != file.Size {
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
		return unit, true
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
	for _, file := range journal.Files {
		if file.Source.Directory && (target.Type != "comic" || !slices.Contains(roots, file.Source.Path)) {
			return ErrJournal
		}
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

func validComicRoot(name string) bool {
	role, id, ok := strings.Cut(name, "/")
	return ok && (role == "hq" || role == "lq") && ValidJournalID(id)
}

func DirectoryPlan(files []FileCandidate, roots []string) []FileCandidate {
	if len(roots) == 0 {
		return files
	}
	plan := make([]FileCandidate, 0, len(roots)+2)
	for _, root := range roots {
		plan = append(plan, FileCandidate{Path: root, Size: -1, Directory: true})
	}
	for _, file := range files {
		covered := false
		for _, root := range roots {
			if strings.HasPrefix(file.Path, root+"/") {
				covered = true
				break
			}
		}
		if !covered {
			plan = append(plan, file)
		}
	}
	return plan
}

func MatchJournalCandidates(value FileJournal, candidates []FileCandidate) error {
	if ValidateJournal(value) != nil || ValidateCandidates(candidates) != nil {
		return ErrJournal
	}
	// 目录条目覆盖其下的登记页。库内登记清单另供完整性核对和清理预估使用。
	var roots []string
	for _, file := range value.Files {
		if file.Source.Directory {
			roots = append(roots, file.Source.Path)
		}
	}
	candidates = DirectoryPlan(candidates, roots)
	if len(value.Files) != len(candidates) {
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
