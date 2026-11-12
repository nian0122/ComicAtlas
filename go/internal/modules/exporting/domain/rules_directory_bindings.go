package domain

import (
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"crypto/sha256"
	"encoding/hex"
	"path"
	"sort"
)

func directoryBindings(value DirectoryInventory, chapters map[string]string) ([]*directoryBinding, map[string]bool, error) {
	bindings := []*directoryBinding{}
	absent := map[string]bool{}
	states := map[string]string{}
	for _, node := range value.Chapters {
		states[node.ID] = node.State
	}
	media := map[string]DirectoryMedia{}
	pageCounts := map[string]map[string]int{}
	pageNames := map[string]string{}
	for _, item := range value.Media {
		if item.State != "active" || states[item.ChapterID] != "active" {
			continue
		}
		label := directoryPageLabel(item.FileName)
		if pageCounts[item.ChapterID] == nil {
			pageCounts[item.ChapterID] = map[string]int{}
		}
		pageCounts[item.ChapterID][directoryNameKey(label)]++
	}
	pageUsed := map[string]map[string]bool{}
	for _, item := range value.Media {
		if item.State != "active" || states[item.ChapterID] != "active" {
			continue
		}
		label := directoryPageLabel(item.FileName)
		used := pageUsed[item.ChapterID]
		if used == nil {
			used = map[string]bool{}
			pageUsed[item.ChapterID] = used
		}
		duplicate := pageCounts[item.ChapterID][directoryNameKey(label)] > 1
		if duplicate || used[directoryNameKey(label)] {
			label = directoryUniqueName(label, item.ID, used, duplicate)
		}
		used[directoryNameKey(label)] = true
		pageNames[item.ID] = label
	}
	add := func(id, chapter, role, target string, file DirectoryStoredFile, optional, hidden bool) {
		availability := "required"
		if optional {
			availability = "optional"
		}
		bindings = append(bindings, &directoryBinding{DirectoryPlacement: DirectoryPlacement{MediaID: id, Role: role, OriginalSource: file.Path, PhysicalSource: file.Path, Target: target, Availability: availability}, file: file, hidden: hidden, chapter: chapter})
	}
	for _, item := range value.Media {
		media[item.ID] = item
		visible := item.State == "active" && states[item.ChapterID] == "active"
		prefix := "_retained/media/" + directoryKey(item.ID) + "/"
		primary := "hq"
		if item.HQState == "released" || item.Hash == "" {
			absent[item.StoragePath] = true
			primary = "lq"
			if visible && (item.MediaType != "image" || item.LQFile == nil || !item.LQFile.Valid || !item.LQFile.MatchesSource(item.Hash)) {
				return nil, nil, ErrSource
			}
		}
		targetPath := func(role, source string) (string, error) {
			ext := directoryExtension(source, role)
			if visible && role != primary && (role == "hq" || role == "lq") {
				return directoryPageTarget(path.Join("_retained", role, chapters[item.ChapterID]), pageNames[item.ID], ext)
			}
			if visible && role == primary {
				return directoryPageTarget(chapters[item.ChapterID], pageNames[item.ID], ext)
			}
			return prefix + role + ext, nil
		}
		if item.Hash == "" {
			target, err := targetPath("hq", item.StoragePath)
			if err != nil {
				return nil, nil, err
			}
			add(item.ID, item.ChapterID, "hq", target, DirectoryStoredFile{Path: item.StoragePath, Size: 0}, false, !visible)
			bindings[len(bindings)-1].Availability = "missing"
		} else if item.HQState == "available" {
			target, err := targetPath("hq", item.StoragePath)
			if err != nil {
				return nil, nil, err
			}
			add(item.ID, item.ChapterID, "hq", target, DirectoryStoredFile{Path: item.StoragePath, Size: item.Size, SHA256: item.Hash}, false, !visible)
		}
		if lq := item.LQFile; lq != nil {
			target, err := targetPath("lq", lq.StoragePath)
			if err != nil {
				return nil, nil, err
			}
			add(item.ID, item.ChapterID, "lq", target, DirectoryStoredFile{Path: lq.StoragePath, Size: lq.Size, SHA256: lq.Hash}, !(visible && primary == "lq"), !visible)
		}
	}
	if cover := value.Cover; cover != nil {
		add("", "", "cover", "_retained/cover/cover.webp", cover.File, true, false)
	}

	for _, name := range value.SuppressedPaths {
		// 只有路径，无媒体/回收身份及旧测量；可选项仍必须接受实际文件核验与冻结。
		target := "_retained/suppressed/" + directoryKey(name) + directoryExtension(name, "suppressed")
		add("", "", "suppressed", target, DirectoryStoredFile{Path: name, Size: -1}, true, false)
	}
	for _, item := range value.Retired {
		owner := media[item.MediaID]
		visible := owner.State == "active" && states[owner.ChapterID] == "active"
		role := "retired_" + item.Kind
		target := "_retained/retired/" + directoryKey(item.SessionID) + "/" + item.Kind + directoryExtension(item.OriginalPath, role)
		add(item.MediaID, owner.ChapterID, role, target, item.File, false, !visible)
	}
	add("", "", "sidecar", "_retained/sidecar/metadata.json", DirectoryStoredFile{Path: "metadata/" + value.Comic.ID + ".json", Size: -1}, true, false)
	return bindings, absent, nil
}

func directoryApplyTrash(value DirectoryInventory, bindings []*directoryBinding, absent map[string]bool, resolved map[string]DirectoryTrashResolution) ([]*directoryBinding, error) {
	sources := map[string]*directoryBinding{}
	for _, binding := range bindings {
		if sources[binding.OriginalSource] != nil {
			return nil, ErrManifest
		}
		sources[binding.OriginalSource] = binding
	}
	chapterStates, mediaStates := map[string]string{}, map[string]string{}
	for _, item := range value.Chapters {
		chapterStates[item.ID] = item.State
	}
	for _, item := range value.Media {
		mediaStates[item.ID] = item.State
	}
	covered := map[string]bool{}
	for _, entry := range value.Trash {
		resolution, ok := resolved[entry.ID]
		if !ok || resolution.ComicID != value.Comic.ID || resolution.EntryID != entry.ID || resolution.Manifest.Path != "trash/"+entry.ID+"/manifest.json" || !digest(resolution.Manifest.SHA256) || resolution.Manifest.Size < 1 || resolution.Manifest.Size > lifecycledomain.MaxJournalBytes || resolution.Journal.ID != entry.ID || resolution.Journal.Phase != "trashed" || lifecycledomain.MatchJournalCandidates(resolution.Journal, entry.Candidates) != nil {
			return nil, ErrManifest
		}
		state := chapterStates[entry.TargetID]
		if entry.TargetType == "media" {
			state = mediaStates[entry.TargetID]
		}
		key := entry.TargetType + ":" + entry.TargetID
		if state != "trashed" || covered[key] {
			return nil, ErrManifest
		}
		covered[key] = true
		delete(resolved, entry.ID)
		for _, file := range resolution.Journal.Files {
			binding := sources[file.Source.Path]
			if binding == nil || !binding.hidden || binding.EntryID != "" || binding.MediaID == "" || entry.TargetType == "chapter" && binding.chapter != entry.TargetID || entry.TargetType == "media" && binding.MediaID != entry.TargetID || file.Source.Size != binding.file.Size || file.Source.SHA256 != binding.file.SHA256 {
				return nil, ErrManifest
			}
			binding.EntryID = entry.ID
			absent[binding.OriginalSource] = true
			binding.PhysicalSource = lifecycledomain.RetainedPath(entry.ID, file.Source.Path)
			binding.file = DirectoryStoredFile{Path: binding.PhysicalSource, Size: file.Size, SHA256: file.SHA256}
			binding.Availability = "required"
			if file.Missing {
				binding.Availability = "missing"
				absent[binding.PhysicalSource] = true
			}
		}
		bindings = append(bindings, &directoryBinding{DirectoryPlacement: DirectoryPlacement{Role: "trash_manifest", EntryID: entry.ID, OriginalSource: resolution.Manifest.Path, PhysicalSource: resolution.Manifest.Path, Target: "_retained/trash/" + entry.ID + "/manifest.json", Availability: "required"}, file: resolution.Manifest})
		for _, unit := range resolution.Journal.Moves {
			if !unit.Directory {
				continue
			}
			source := lifecycledomain.MoveMarkerPath(lifecycledomain.RetainedPath(entry.ID, unit.Path), entry.ID)
			proof := sha256.Sum256([]byte(unit.Proof))
			file := DirectoryStoredFile{Path: source, Size: int64(len(unit.Proof)), SHA256: hex.EncodeToString(proof[:])}
			bindings = append(bindings, &directoryBinding{DirectoryPlacement: DirectoryPlacement{Role: "trash_move_proof", EntryID: entry.ID, OriginalSource: source, PhysicalSource: source, Target: "_retained/trash/" + entry.ID + "/proofs/" + unit.Path + ".token", Availability: "required"}, file: file})
		}
	}
	for id, state := range chapterStates {
		if state == "trashed" && !covered["chapter:"+id] {
			return nil, ErrManifest
		}
	}
	for id, state := range mediaStates {
		if state == "trashed" && !covered["media:"+id] {
			return nil, ErrManifest
		}
	}
	for _, binding := range bindings {
		if binding.hidden && binding.EntryID == "" {
			return nil, ErrManifest
		}
	}
	return bindings, nil
}

func directoryBoundPlan(value DirectoryInventory, name string, resolved map[string]DirectoryTrashResolution) (DirectoryPlan, []DirectoryPlacement, error) {
	plan := DirectoryPlan{ComicID: value.Comic.ID, Name: name, Files: []DirectoryFile{}}
	directories, chapters, err := directoryLayout(value, name)
	if err != nil {
		return plan, nil, err
	}
	plan.Directories = directories
	bindings, absent, err := directoryBindings(value, chapters)
	if err != nil {
		return plan, nil, err
	}
	bindings, err = directoryApplyTrash(value, bindings, absent, resolved)
	if err != nil {
		return plan, nil, err
	}
	roots := map[string]bool{"hq/" + value.Comic.ID: true, "lq/" + value.Comic.ID: true, "thumbs/" + value.Comic.ID: true}
	for _, item := range value.Retired {
		root, err := directoryOwnerRoot(value.Comic.ID, item.OriginalPath)
		if err != nil {
			return plan, nil, err
		}
		if root != "" {
			roots[root] = true
		}
	}
	placements := make([]DirectoryPlacement, 0, len(bindings))
	for _, binding := range bindings {
		root, err := directoryOwnerRoot(value.Comic.ID, binding.OriginalSource)
		if err != nil {
			return plan, nil, err
		}
		if root != "" {
			roots[root] = true
		}
		if binding.Availability != "missing" {
			plan.Files = append(plan.Files, DirectoryFile{Source: binding.file.Path, Target: binding.Target, Size: binding.file.Size, SHA256: binding.file.SHA256, Optional: binding.Availability == "optional"})
		}
		placement := binding.DirectoryPlacement
		placement.Target = DirectoryOutputPath(plan.Name, placement.Target)
		placements = append(placements, placement)
	}
	plan.OwnedDirectories = directoryRoots(roots)
	plan.AbsentSources = make([]string, 0, len(absent))
	for source := range absent {
		plan.AbsentSources = append(plan.AbsentSources, source)
	}
	sort.Strings(plan.AbsentSources)
	return plan, placements, nil
}
