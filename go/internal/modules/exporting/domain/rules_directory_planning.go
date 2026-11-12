package domain

func DirectoryInventoryIdentities(snapshots []DirectoryInventorySnapshot) ([]DirectoryInventoryIdentity, error) {
	if len(snapshots) == 0 || len(snapshots) > MaxDirectoryBooks {
		return nil, ErrValidation
	}
	ids := make([]string, 0, len(snapshots))
	identities := make([]DirectoryInventoryIdentity, 0, len(snapshots))
	bytes := 0
	for _, snapshot := range snapshots {
		stamp, size, err := DirectoryInventoryStamp(snapshot.Inventory)
		if err != nil {
			return nil, err
		}
		if stamp != snapshot.SHA256 || size != snapshot.CanonicalBytes {
			return nil, ErrManifest
		}
		bytes += size
		if bytes > MaxDirectoryPlanBytes {
			return nil, ErrValidation
		}
		ids = append(ids, snapshot.Inventory.Comic.ID)
		identities = append(identities, DirectoryInventoryIdentity{ComicID: snapshot.Inventory.Comic.ID, SHA256: stamp, MutationVersion: snapshot.MutationVersion})
	}
	if err := DirectoryIDs(ids); err != nil {
		return nil, err
	}
	return identities, nil
}

func BuildDirectoryPlans(snapshots []DirectoryInventorySnapshot, resolutions []DirectoryTrashResolution) ([]DirectoryPlan, error) {
	if _, err := DirectoryInventoryIdentities(snapshots); err != nil {
		return nil, err
	}
	names, err := directoryBookNames(snapshots)
	if err != nil {
		return nil, err
	}
	if len(resolutions) > MaxNodes {
		return nil, ErrValidation
	}
	resolved := map[string]DirectoryTrashResolution{}
	totalJournalFiles, totalJournalBytes := 0, int64(0)
	for _, value := range resolutions {
		if _, exists := resolved[value.EntryID]; exists {
			return nil, ErrManifest
		}
		totalJournalFiles += len(value.Journal.Files)
		totalJournalBytes += value.Manifest.Size
		if totalJournalFiles > MaxDirectoryFiles || totalJournalBytes < 0 || totalJournalBytes > MaxDirectoryPlanBytes {
			return nil, ErrValidation
		}
		resolved[value.EntryID] = value
	}
	plans := make([]DirectoryPlan, 0, len(snapshots))
	files, absences, nodes, documentBytes := 0, 0, 0, 0
	for index, snapshot := range snapshots {
		plan, placements, err := directoryBoundPlan(snapshot.Inventory, names[index], resolved)
		if err != nil {
			return nil, err
		}
		for _, entry := range snapshot.Inventory.Trash {
			plan.TrashIDs = append(plan.TrashIDs, entry.ID)
		}
		files += len(plan.Files)
		absences += len(plan.AbsentSources)
		nodes += len(plan.Directories) + len(plan.OwnedDirectories)
		if files > MaxDirectoryFiles || absences > MaxDirectoryAbsences || nodes > MaxNodes {
			return nil, ErrValidation
		}
		data, err := directoryDocument(snapshot.Inventory, snapshot.SHA256, placements)
		if err != nil {
			return nil, err
		}
		documentBytes += (len(data) + 2) / 3 * 4
		if documentBytes > MaxDirectoryPlanBytes {
			return nil, ErrValidation
		}
		plan.Documents = []DirectoryDocument{{Name: "metadata.json", Data: data}}
		plans = append(plans, plan)
	}
	if len(resolved) != 0 {
		return nil, ErrManifest
	}
	if _, err := DirectoryPlanStamp(plans); err != nil {
		return nil, err
	}
	return plans, nil
}
