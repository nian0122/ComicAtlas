package domain

func DirectoryDetachPlans(plans []DirectoryPlan) DirectoryDetachFacts {
	var facts DirectoryDetachFacts
	for _, plan := range plans {
		facts.TrashIDs = append(facts.TrashIDs, plan.TrashIDs...)
	}
	return facts
}

func ValidateDirectoryResult(row DirectoryFileResult) error {
	if row.Position < 1 || row.Position > MaxDirectoryFiles || DirectoryIDs([]string{row.ComicID}) != nil || !directorySource(row.Source) || !directoryTarget(row.Target) || row.Size < 0 || row.Size > 20<<30 {
		return ErrManifest
	}
	if row.State == "initially_missing" {
		if row.Size != 0 || row.SHA256 != "" {
			return ErrManifest
		}
		return nil
	}
	if row.State != "moved" || !digest(row.SHA256) {
		return ErrManifest
	}
	return nil
}

func ValidateDirectoryCompletedResult(row DirectoryFileResult, position int) error {
	if ValidateDirectoryResult(row) != nil || row.Position != position {
		return ErrManifest
	}
	return nil
}

func ValidateDirectoryResultQuery(id string, page, size int) error {
	if !TaskID(id) || page < 1 || page > 1000000 || size < 1 || size > 100 {
		return ErrValidation
	}
	return nil
}

func ValidateDirectoryResultBatch(rows []DirectoryFileResult) error {
	if len(rows) == 0 || len(rows) > MaxDirectoryResultBatch {
		return ErrValidation
	}
	return nil
}
