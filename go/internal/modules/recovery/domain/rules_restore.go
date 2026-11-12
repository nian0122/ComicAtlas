package domain

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"encoding/json"
	"strings"
	"time"
)

func TermNameKey(name string) string { return strings.ToLower(name) }

// RestoreTermIdentity 根据事务内读到的身份事实决定复用或创建，不修改共享词条。
func RestoreTermIdentity(sourceID, matchingID string, sourceIDUsed bool, replacementID string) (id string, create bool, err error) {
	if !Key(sourceID) {
		return "", false, ErrValidation
	}
	if matchingID != "" {
		if !Key(matchingID) {
			return "", false, ErrConflict
		}
		return matchingID, false, nil
	}
	if !sourceIDUsed {
		return sourceID, true, nil
	}
	if !Key(replacementID) || replacementID == sourceID {
		return "", false, ErrValidation
	}
	return replacementID, true, nil
}

func PrepareRestore(comic string, sidecar Sidecar, task, recordedAt string) (RestorePlan, error) {
	if !Key(task) || len(recordedAt) > 40 {
		return RestorePlan{}, ErrValidation
	}
	if _, err := time.Parse(time.RFC3339Nano, recordedAt); err != nil {
		return RestorePlan{}, ErrValidation
	}
	if err := ValidateSidecar(comic, sidecar); err != nil {
		return RestorePlan{}, err
	}
	// 规范大小上限在分配独立快照前检查；冻结发生在 SQL 写事务之外。
	data, snapshotSHA, err := librarydomain.SnapshotData(sidecar.Snapshot)
	if err != nil {
		return RestorePlan{}, ErrLimit
	}
	result := RestorePlan{TaskID: task, RecordedAt: recordedAt, SnapshotSHA256: snapshotSHA}
	if err = json.Unmarshal(data, &result.Snapshot); err != nil {
		return RestorePlan{}, ErrMetadata
	}
	keys := map[string]bool{}
	for _, tag := range result.Snapshot.Detail.Tags {
		key := TermNameKey(tag.Name)
		if keys[key] {
			return RestorePlan{}, ErrMetadata
		}
		keys[key] = true
	}
	for _, media := range result.Snapshot.Media {
		if (media.HQState == "released" || media.Hash == "") && !CurrentLQ(media) {
			return RestorePlan{}, ErrMetadata
		}
	}
	// 只调整插入顺序以满足父引用，不重排节点的 sort/global_order 或版本。
	catalogs := result.Snapshot.Detail.Catalogs
	indexes := make(map[string]int, len(catalogs))
	for i, node := range catalogs {
		indexes[node.ID] = i
	}
	ordered := make([]librarydomain.Catalog, 0, len(catalogs))
	seen := make([]bool, len(catalogs))
	var visit func(int)
	visit = func(i int) {
		if seen[i] {
			return
		}
		if parent := catalogs[i].ParentID; parent != nil {
			visit(indexes[*parent])
		}
		seen[i] = true
		ordered = append(ordered, catalogs[i])
	}
	for i := range catalogs {
		visit(i)
	}
	result.Snapshot.Detail.Catalogs = ordered
	return result, nil
}
