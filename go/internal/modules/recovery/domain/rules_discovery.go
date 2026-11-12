package domain

func ValidateDiscoveryWrite(run DiscoveryRun) error {
	if !Key(run.TaskID) || run.TaskStatus != "running" {
		return ErrConflict
	}
	return nil
}

func ValidateDiscoveryBatch(run DiscoveryRun, ids []string) error {
	if err := ValidateDiscoveryWrite(run); err != nil {
		return err
	}
	if run.Sealed {
		return ErrConflict
	}
	if len(ids) < 1 || len(ids) > DiscoveryBatchSize {
		return ErrValidation
	}
	if run.CandidateCount+len(ids) > MaxCandidates {
		return ErrLimit
	}
	seen := map[string]bool{}
	for _, id := range ids {
		if !Key(id) || seen[id] {
			return ErrValidation
		}
		seen[id] = true
	}
	return nil
}

func ValidateDiscoverySeal(run DiscoveryRun, found Discovery, actual int) error {
	if err := ValidateDiscoveryWrite(run); err != nil {
		return err
	}
	if run.Sealed || actual != run.CandidateCount || found.Candidates != actual {
		return ErrConflict
	}
	if actual < 0 || actual > MaxCandidates || found.Ignored < 0 || found.Ignored > MaxRootEntries-actual {
		return ErrLimit
	}
	return nil
}

func ValidateDiscoveryPage(task, after string, limit int) error {
	if !Key(task) || after != "" && !Key(after) || limit < 1 || limit > DiscoveryBatchSize {
		return ErrValidation
	}
	return nil
}
