package domain

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"strings"
)

func DirectoryIDs(ids []string) error {
	if len(ids) == 0 || len(ids) > MaxDirectoryBooks {
		return ErrValidation
	}
	seen := map[string]bool{}
	for _, id := range ids {
		if !directoryID(id) || seen[id] {
			return ErrValidation
		}
		seen[id] = true
	}
	return nil
}

func directoryID(id string) bool {
	if len(id) == 0 || len(id) > 128 {
		return false
	}
	for _, c := range id {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-') {
			return false
		}
	}
	return true
}

func DirectoryInactive(value DirectoryActivity) error {
	if value.Tasks != 0 || value.Uploads != 0 || value.LifecycleRuns != 0 || value.RefreshRuns != 0 || value.PreparedTranscodes != 0 || value.PendingRetired != 0 {
		return ErrConflict
	}
	return nil
}

func DirectoryInventoryBudget(rows, rawBytes, totalBytes int64, maximumRows int) error {
	if rows < 0 || rows > int64(maximumRows) || rawBytes < 0 || totalBytes < 0 || rawBytes > MaxSnapshotBytes-totalBytes {
		return ErrValidation
	}
	return nil
}

func DirectoryRetiredPath(session, kind string) (string, error) {
	if !directoryID(session) || kind != "hq" && kind != "lq" {
		return "", ErrValidation
	}
	return "retired/uploads/" + session + "/" + kind + ".bin", nil
}

// DirectoryInventoryStamp 逐行校验和取摘要，拒绝超预算后再构造整份序列化结果。
func DirectoryInventoryStamp(value DirectoryInventory) (string, int, error) {
	if err := directorySuppressedPaths(value); err != nil {
		return "", 0, err
	}
	if value.Version != 1 || !directoryID(value.Comic.ID) || value.Comic.Version < 1 || value.StructureVersion < 1 || librarydomain.ValidateImportedMetadata(value.Comic) != nil || librarydomain.ValidateComicInfo(value.ComicInfo) != nil || len(value.Catalogs)+len(value.Chapters) > MaxNodes || len(value.Media) > MaxDirectoryFiles || len(value.Retired) > MaxDirectoryFiles || len(value.Trash) > MaxNodes || len(value.Reactions) > MaxDirectoryFiles+MaxNodes+1 {
		return "", 0, ErrValidation
	}
	if value.Comic.CoverURL != "" || len(value.Comic.CreatedAt) > 40 {
		return "", 0, ErrValidation
	}
	if category := value.Comic.Category; category != nil && (!directoryID(category.ID) || !librarydomain.ValidName(category.Name, 200) || category.Version < 1) {
		return "", 0, ErrValidation
	}
	tags := map[string]bool{}
	for _, tag := range value.Comic.Tags {
		if !directoryID(tag.ID) || tags[tag.ID] || tag.Version < 1 || tag.Source != "manual" && tag.Source != "comicinfo" && tag.Source != "auto" {
			return "", 0, ErrValidation
		}
		tags[tag.ID] = true
	}
	hash := sha256.New()
	bytes := 0
	add := func(row any) bool {
		data, err := json.Marshal(row)
		if err != nil || len(data)+bytes+1 > MaxSnapshotBytes {
			return false
		}
		bytes += len(data) + 1
		_, _ = hash.Write(data)
		_, _ = hash.Write([]byte{'\n'})
		return true
	}
	if !add(struct {
		Version                                                                int
		Comic                                                                  librarydomain.Comic
		ComicInfo                                                              *librarydomain.ComicInfo
		StructureVersion, Catalogs, Chapters, Media, Retired, Trash, Reactions int
	}{value.Version, value.Comic, value.ComicInfo, value.StructureVersion, len(value.Catalogs), len(value.Chapters), len(value.Media), len(value.Retired), len(value.Trash), len(value.Reactions)}) {
		return "", bytes, ErrValidation
	}
	catalogs := map[string]*string{}
	for _, node := range value.Catalogs {
		if !directoryID(node.ID) || node.ParentID != nil && !directoryID(*node.ParentID) || !librarydomain.ValidName(node.Title, 500) {
			return "", bytes, ErrValidation
		}
		if _, ok := catalogs[node.ID]; ok {
			return "", bytes, ErrValidation
		}
		catalogs[node.ID] = node.ParentID
		if !add(node) {
			return "", bytes, ErrValidation
		}
	}
	for id := range catalogs {
		seen := map[string]bool{}
		for current := id; current != ""; {
			parent, ok := catalogs[current]
			if !ok || seen[current] || len(seen) >= 64 {
				return "", bytes, ErrValidation
			}
			seen[current] = true
			current = ""
			if parent != nil {
				current = *parent
				if current == "" {
					return "", bytes, ErrValidation
				}
			}
		}
	}
	chapters := map[string]bool{}
	orders := map[int]bool{}
	for _, node := range value.Chapters {
		if node.State != "active" && node.State != "trashed" {
			return "", bytes, ErrConflict
		}
		if !directoryID(node.ID) || chapters[node.ID] || !librarydomain.ValidName(node.Title, 500) || node.MediaVersion < 1 || node.GlobalOrder < 1 || orders[node.GlobalOrder] || node.CatalogID != nil && !directoryCatalog(catalogs, *node.CatalogID) || !add(node) {
			return "", bytes, ErrValidation
		}
		chapters[node.ID] = true
		orders[node.GlobalOrder] = true
	}
	media := map[string]bool{}
	mediaChapters := map[string]string{}
	pages := map[string]map[int]bool{}
	files := 1 // 可选旧侧车同样占冻结文件项。
	for _, item := range value.Media {
		if item.State != "active" && item.State != "trashed" || item.HQState != "available" && item.HQState != "released" {
			return "", bytes, ErrConflict
		}
		if !directoryID(item.ID) || media[item.ID] || !chapters[item.ChapterID] || item.PageNumber < 1 || len(item.FileName) > 255 || item.FileName == "" || (item.MediaType != "image" && item.MediaType != "video") || len(item.ContentType) > 255 || !directorySource(item.StoragePath) || !sourcePath(item.StoragePath, "hq") || (!digest(item.Hash) && (item.Hash != "" || librarydomain.OriginalAvailability(item.StoredMedia) == "unknown")) || item.Size < 0 || item.Size > 20<<30 {
			return "", bytes, ErrValidation
		}
		if item.ContentURL != "" || item.AutoContentURL != "" || item.PreviewURL != nil || item.LQ != nil || item.StorageState != "" {
			return "", bytes, ErrValidation
		}
		if pages[item.ChapterID] == nil {
			pages[item.ChapterID] = map[int]bool{}
		}
		if pages[item.ChapterID][item.PageNumber] {
			return "", bytes, ErrValidation
		}
		pages[item.ChapterID][item.PageNumber] = true
		media[item.ID] = true
		mediaChapters[item.ID] = item.ChapterID
		if item.HQState == "available" || item.Hash == "" {
			files++
		}
		if item.Video != nil && (len(item.Video.AudioCodecs) > 32 || len(item.Video.Container) > 128 || len(item.Video.VideoCodec) > 128 || len(item.Video.PixelFormat) > 128 || len(item.Video.PlaybackHint) > 128) {
			return "", bytes, ErrValidation
		}
		if item.Video != nil {
			for _, codec := range item.Video.AudioCodecs {
				if len(codec) > 128 {
					return "", bytes, ErrValidation
				}
			}
		}
		if lq := item.LQFile; lq != nil {
			if item.MediaType != "image" || lq.MediaID != item.ID || !directorySource(lq.StoragePath) || !sourcePath(lq.StoragePath, "lq") || !digest(lq.Hash) || (lq.TaskID == "" && (lq.Provenance == nil || lq.Provenance.Origin != "imported")) || lq.TaskID != "" && !directoryID(lq.TaskID) || mediadomain.ValidateLQProvenance(lq.Provenance, lq.TaskID) != nil || lq.Size < 1 || lq.Size > 20<<30 {
				return "", bytes, ErrValidation
			}
			files++
		}
		if files > MaxDirectoryFiles || !add(item) {
			return "", bytes, ErrValidation
		}
	}
	// 没有路径事实时不改变既有冻结清单摘要；有记录时计入数量、预算和逐项路径。
	if choice := value.CoverSelection; choice != nil {
		if choice.ComicID != value.Comic.ID || !media[choice.MediaID] || choice.Version < 1 || !add(choice) {
			return "", bytes, ErrValidation
		}
	}
	if cover := value.Cover; cover != nil {
		if !mediadomain.StorageKey(cover.MediaID) || cover.File.Path != mediadomain.CoverPath(value.Comic.ID) || cover.File.Size < 1 || !digest(cover.File.SHA256) || cover.Width < 1 || cover.Height < 1 || cover.Width > mediadomain.CoverSpec().MaxLongEdge || cover.Height > mediadomain.CoverSpec().MaxLongEdge || cover.File.Size > 2<<20 || !digest(cover.SourceKey) || !add(cover) {
			return "", bytes, ErrValidation
		}
		files++
	}

	if len(value.SuppressedPaths) > 0 {
		if !add(struct{ SuppressedPaths int }{len(value.SuppressedPaths)}) {
			return "", bytes, ErrValidation
		}
		for _, name := range value.SuppressedPaths {
			files++
			if files > MaxDirectoryFiles || !add(name) {
				return "", bytes, ErrValidation
			}
		}
	}
	retired := map[string]bool{}
	for _, item := range value.Retired {
		key := item.SessionID + "/" + item.Kind
		if !directoryID(item.SessionID) || !media[item.MediaID] || item.Kind != "hq" && item.Kind != "lq" || !directorySource(item.OriginalPath) || !sourcePath(item.OriginalPath, item.Kind) || item.File.Path != "retired/uploads/"+item.SessionID+"/"+item.Kind+".bin" || !directoryStoredFile(item.File, "retired") || retired[key] {
			return "", bytes, ErrValidation
		}
		if len(item.OriginalMetadata) > 0 {
			if len(item.OriginalMetadata) > MaxDirectoryRetiredMetadataBytes || !json.Valid(item.OriginalMetadata) || !strings.HasPrefix(strings.TrimSpace(string(item.OriginalMetadata)), "{") {
				return "", bytes, ErrValidation
			}
		}
		if !add(item) {
			return "", bytes, ErrValidation
		}
		retired[key] = true
		files++
	}
	trash := map[string]bool{}
	sources := map[string]bool{}
	totalCandidates := 0
	for _, item := range value.Trash {
		if item.State != "trashed" {
			return "", bytes, ErrConflict
		}
		if !lifecycledomain.ValidJournalID(item.ID) || !directoryID(item.TaskID) || trash[item.ID] || !chapters[item.ChapterID] || item.TargetType != "chapter" && item.TargetType != "media" || item.TargetType == "chapter" && item.TargetID != item.ChapterID || item.TargetType == "media" && mediaChapters[item.TargetID] != item.ChapterID || len(item.Title) > 1024 || len(item.CreatedAt) > 40 || len(item.UpdatedAt) > 40 || lifecycledomain.ValidateCandidates(item.Candidates) != nil {
			return "", bytes, ErrValidation
		}
		trash[item.ID] = true
		files++
		totalCandidates += len(item.Candidates)
		if totalCandidates > MaxDirectoryFiles {
			return "", bytes, ErrValidation
		}
		if !add(struct {
			ID, TargetType, TargetID, ChapterID, Title, CreatedAt, UpdatedAt, TaskID, State string
			Count                                                                           int
		}{item.ID, item.TargetType, item.TargetID, item.ChapterID, item.Title, item.CreatedAt, item.UpdatedAt, item.TaskID, item.State, len(item.Candidates)}) {
			return "", bytes, ErrValidation
		}
		for _, candidate := range item.Candidates {
			key := strings.ToLower(candidate.Path)
			if sources[key] || !add(candidate) {
				return "", bytes, ErrValidation
			}
			sources[key] = true
		}
	}
	if files > MaxDirectoryFiles {
		return "", bytes, ErrValidation
	}
	reactions := map[string]bool{}
	for _, item := range value.Reactions {
		key := item.TargetType + ":" + item.TargetID
		exists := item.TargetType == "comic" && item.TargetID == value.Comic.ID || item.TargetType == "chapter" && chapters[item.TargetID] || item.TargetType == "media" && media[item.TargetID]
		if !exists || reactions[key] || item.Reaction != "like" && item.Reaction != "dislike" || len(item.UpdatedAt) > 40 || !add(item) {
			return "", bytes, ErrValidation
		}
		reactions[key] = true
	}
	if p := value.Progress; p != nil && (p.ComicID != value.Comic.ID || !chapters[p.ChapterID] || p.MediaID != "" && mediaChapters[p.MediaID] != p.ChapterID || p.PageNumber < 1 || len(p.UpdatedAt) > 40) {
		return "", bytes, ErrValidation
	}
	if !add(value.Progress) {
		return "", bytes, ErrValidation
	}
	return hex.EncodeToString(hash.Sum(nil)), bytes, nil
}

func directoryCatalog(catalogs map[string]*string, id string) bool { _, ok := catalogs[id]; return ok }
func directoryStoredFile(file DirectoryStoredFile, root string) bool {
	return directorySource(file.Path) && sourcePath(file.Path, root) && digest(file.SHA256) && file.Size >= 0 && file.Size <= 20<<30
}
