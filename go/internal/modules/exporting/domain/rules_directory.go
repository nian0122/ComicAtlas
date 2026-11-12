package domain

import (
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"path"
	"strings"
	"unicode/utf8"
)

const DirectoryPlanVersion = 5

func directoryRelative(name string) bool {
	if name == "" || len(name) > DirectoryPathBytes || !utf8.ValidString(name) || path.Clean(name) != name || strings.HasPrefix(name, "/") || strings.ContainsAny(name, "\\:\x00") {
		return false
	}
	for _, part := range strings.Split(name, "/") {
		if part == "." || part == ".." {
			return false
		}
	}
	return true
}

func directoryTarget(name string) bool {
	if !directoryRelative(name) || strings.ContainsAny(name, "<>\"|?*") {
		return false
	}
	for _, character := range name {
		if character < 32 || character == 127 {
			return false
		}
	}
	for _, part := range strings.Split(name, "/") {
		if len(part) > DirectoryComponentBytes || strings.HasPrefix(strings.ToLower(part), ".comicatlas-") || part == "." || part == ".." || strings.TrimRight(part, " .") != part {
			return false
		}
		base := strings.ToUpper(strings.SplitN(part, ".", 2)[0])
		if base == "CON" || base == "PRN" || base == "AUX" || base == "NUL" || len(base) == 4 && (strings.HasPrefix(base, "COM") || strings.HasPrefix(base, "LPT")) && base[3] >= '1' && base[3] <= '9' {
			return false
		}
	}
	return len(strings.Split(name, "/")) <= DirectoryPathDepth
}

func directorySource(name string) bool {
	if !directoryRelative(name) {
		return false
	}
	root, rest, found := strings.Cut(name, "/")
	if found && root == "trash" {
		id, tail, child := strings.Cut(rest, "/")
		return lifecycledomain.ValidJournalID(id) && (!child || tail == "manifest.json" || strings.HasPrefix(tail, "files/") && lifecycledomain.ValidSourcePath(strings.TrimPrefix(tail, "files/")))
	}
	return found && (root == "hq" || root == "lq" || root == "thumbs" || root == "metadata" || root == "retired")
}

// DirectoryPlanStamp 逐项编码取摘要并限制总预算，不先把整批快照编码到大缓冲。
func DirectoryPlanStamp(plans []DirectoryPlan) (string, error) {
	if len(plans) == 0 || len(plans) > MaxDirectoryBooks {
		return "", ErrValidation
	}
	fileCount, nodeCount, documentCount, absenceCount := 0, 0, 0, 0
	for _, plan := range plans {
		fileCount += len(plan.Files)
		nodeCount += len(plan.Directories) + len(plan.OwnedDirectories)
		documentCount += len(plan.Documents)
		absenceCount += len(plan.AbsentSources)
		if fileCount > MaxDirectoryFiles || nodeCount > MaxNodes || documentCount > MaxNodes || absenceCount > MaxDirectoryAbsences {
			return "", ErrValidation
		}
	}
	hash := sha256.New()
	budget, files, nodes, documents := 0, 0, 0, 0
	add := func(value any) bool {
		data, err := json.Marshal(value)
		if err != nil || len(data)+budget+1 > MaxDirectoryPlanBytes {
			return false
		}
		budget += len(data) + 1
		_, _ = hash.Write(data)
		_, _ = hash.Write([]byte{'\n'})
		return true
	}
	_ = add(struct{ Version, Books int }{DirectoryPlanVersion, len(plans)})
	comics, names, sources, owned := map[string]bool{}, map[string]bool{}, map[string]bool{}, map[string]bool{}
	outputDirectories := 0
	trashCount := 0
	for _, plan := range plans {
		targets, dirs := map[string]bool{}, map[string]bool{}
		name := strings.ToLower(plan.Name)
		if !directoryTarget(plan.Name) || strings.Contains(plan.Name, "/") || strings.EqualFold(plan.Name, DirectoryOtherRoot) || plan.ComicID == "" || len(plan.ComicID) > 128 || comics[plan.ComicID] || names[name] {
			return "", ErrValidation
		}
		comics[plan.ComicID], names[name] = true, true
		if !add(struct {
			Comic, Name                                    string
			Files, Directories, Owned, Documents, Absences int
		}{plan.ComicID, plan.Name, len(plan.Files), len(plan.Directories), len(plan.OwnedDirectories), len(plan.Documents), len(plan.AbsentSources)}) {
			return "", ErrValidation
		}
		trash := map[string]bool{}
		trashCount += len(plan.TrashIDs)
		if trashCount > MaxNodes {
			return "", ErrValidation
		}
		for _, id := range plan.TrashIDs {
			if !directoryID(id) || trash[id] || !add(id) {
				return "", ErrValidation
			}
			trash[id] = true
		}
		for _, entry := range plan.Files {
			files++
			key := strings.ToLower(entry.Source)
			mapped := DirectoryOutputPath(plan.Name, entry.Target)
			target := strings.ToLower(mapped)
			known := entry.Size >= 0 && entry.Size <= 20<<30 && digest(entry.SHA256)
			unknown := entry.Optional && entry.Size == -1 && entry.SHA256 == ""
			if files > MaxDirectoryFiles || !directorySource(entry.Source) || !directoryTarget(mapped) || sources[key] || targets[target] || !known && !unknown || !add(entry) {
				return "", ErrValidation
			}
			sources[key], targets[target] = true, true
		}
		for _, source := range plan.AbsentSources {
			key := strings.ToLower(source)
			if !directorySource(source) || sources[key] || !add(source) {
				return "", ErrValidation
			}
			sources[key] = true
		}
		for _, entry := range plan.Documents {
			documents++
			mapped := DirectoryOutputPath(plan.Name, entry.Name)
			target := strings.ToLower(mapped)
			if documents > MaxNodes || !directoryTarget(mapped) || targets[target] || len(entry.Data) > MaxSnapshotBytes {
				return "", ErrValidation
			}
			// 文档在计划 JSON 中为 base64；预算按实际编码长度计算，摘要直接覆盖原始内容。
			encoded := (len(entry.Data) + 2) / 3 * 4
			if encoded+budget > MaxDirectoryPlanBytes {
				return "", ErrValidation
			}
			budget += encoded
			digest := sha256.Sum256(entry.Data)
			if !add(struct {
				Name   string
				Size   int
				SHA256 [32]byte
			}{entry.Name, len(entry.Data), digest}) {
				return "", ErrValidation
			}
			targets[target] = true
		}
		for _, entry := range plan.Directories {
			nodes++
			mapped := DirectoryOutputPath(plan.Name, entry)
			target := strings.ToLower(mapped)
			if nodes > MaxNodes || !directoryTarget(mapped) || dirs[target] || targets[target] || !add(entry) {
				return "", ErrValidation
			}
			dirs[target] = true
		}
		for _, entry := range plan.OwnedDirectories {
			nodes++
			key := strings.ToLower(entry)
			if nodes > MaxNodes || !directorySource(entry) || owned[key] || !add(entry) {
				return "", ErrValidation
			}
			owned[key] = true
		}
		if directoryPrefixCollision(targets, dirs) {
			return "", ErrValidation
		}
		parents := map[string]bool{".": true}
		for name := range dirs {
			parents[name] = true
		}
		for _, paths := range []map[string]bool{targets, dirs} {
			for name := range paths {
				for parent := path.Dir(name); parent != "."; parent = path.Dir(parent) {
					parents[parent] = true
					if outputDirectories+len(parents) > MaxNodes+MaxDirectoryBooks {
						return "", ErrValidation
					}
				}
			}
		}
		outputDirectories += len(parents)
		if outputDirectories > MaxNodes+MaxDirectoryBooks {
			return "", ErrValidation
		}
	}
	// 文件不能兼作祖先目录；独占来源根不能互相包含。
	if directoryPrefixCollision(sources, nil) || directoryPrefixCollision(owned, nil) {
		return "", ErrValidation
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}

func directoryPrefixCollision(files, dirs map[string]bool) bool {
	for _, paths := range []map[string]bool{files, dirs} {
		for name := range paths {
			for parent := path.Dir(name); parent != "."; parent = path.Dir(parent) {
				if files[parent] {
					return true
				}
			}
		}
	}
	return false
}
