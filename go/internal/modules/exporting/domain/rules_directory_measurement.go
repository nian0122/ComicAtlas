package domain

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
)

func directoryFilesDigest(files []DirectoryFileState) string {
	hash := sha256.New()
	encoder := json.NewEncoder(hash)
	_ = encoder.Encode(struct{ Version, Count int }{DirectoryPlanVersion, len(files)})
	for _, item := range files {
		_ = encoder.Encode(item)
	}
	return hex.EncodeToString(hash.Sum(nil))
}

// FreezeDirectoryMeasurement 将无内容读取的预检事实纳入唯一不可变计划。
func FreezeDirectoryMeasurement(id string, plans []DirectoryPlan, inspection DirectoryInspection) ([]DirectoryFileState, DirectorySeal, error) {
	stamp, err := DirectoryPlanStamp(plans)
	if err != nil || !TaskID(id) || stamp != inspection.PlanSHA256 {
		return nil, DirectorySeal{}, ErrManifest
	}
	files := make([]DirectoryFileState, 0, len(inspection.Files))
	index := 0
	for _, plan := range plans {
		for _, entry := range plan.Files {
			if index >= len(inspection.Files) {
				return nil, DirectorySeal{}, ErrManifest
			}
			item := inspection.Files[index]
			if item.ComicID != plan.ComicID || item.Source != entry.Source || item.Target != DirectoryOutputPath(plan.Name, entry.Target) {
				return nil, DirectorySeal{}, ErrManifest
			}
			files = append(files, DirectoryFileState{Identity: item.Identity, Modified: item.Modified, Size: item.Size, SHA256: item.SHA256, Missing: item.Missing})
			index++
		}
	}
	if index != len(inspection.Files) {
		return nil, DirectorySeal{}, ErrManifest
	}
	seal, err := DirectoryMeasurementSeal(id, plans, files)
	if err != nil || seal.ObservedBytes != inspection.ObservedBytes {
		return nil, seal, ErrManifest
	}
	return files, seal, nil
}

func DirectoryMeasurementSeal(id string, plans []DirectoryPlan, files []DirectoryFileState) (DirectorySeal, error) {
	stamp, err := DirectoryPlanStamp(plans)
	seal := DirectorySeal{TaskID: id, PlanSHA256: stamp, FilesSHA256: directoryFilesDigest(files), FileCount: len(files)}
	if err != nil || !TaskID(id) || len(files) > MaxDirectoryFiles {
		return seal, ErrManifest
	}
	index := 0
	identities := map[string]bool{}
	for _, plan := range plans {
		for _, entry := range plan.Files {
			if index >= len(files) {
				return seal, ErrManifest
			}
			item := files[index]
			if item.Missing {
				if !entry.Optional || item.Identity != "" || item.Modified != 0 || item.Size != 0 || item.SHA256 != "" {
					return seal, ErrManifest
				}
			} else {
				if item.Identity == "" || len(item.Identity) > 256 || identities[item.Identity] || item.Size < 0 || item.Size > 20<<30 || item.SHA256 != entry.SHA256 || entry.Size >= 0 && item.Size != entry.Size {
					return seal, ErrManifest
				}
				identities[item.Identity] = true
				seal.ObservedBytes += item.Size
				if seal.ObservedBytes > 100<<30 {
					return seal, ErrManifest
				}
			}
			index++
		}
	}
	if index != len(files) {
		return seal, ErrManifest
	}
	return seal, nil
}
