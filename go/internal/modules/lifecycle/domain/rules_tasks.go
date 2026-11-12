package domain

func ValidateTarget(kind, id string) error {
	if (kind != "comic" && kind != "chapter" && kind != "media") || !ValidJournalID(id) {
		return ErrValidation
	}
	return nil
}

func ValidateRequest(value Request) error {
	if value.Operation == "trash" && value.EntryID == "" && value.Confirmation == "" {
		return nil
	}
	if value.Operation == "restore" && ValidJournalID(value.EntryID) && value.Confirmation == "" {
		return nil
	}
	if value.Operation == "purge" && ValidJournalID(value.EntryID) && validDigest(value.Confirmation) {
		return nil
	}
	return ErrValidation
}

func ValidateEntryQuery(query EntryQuery) error {
	if query.Page < 1 || query.Page > 1000000 || query.PageSize < 1 || query.PageSize > 100 || len(query.Keyword) > 500 {
		return ErrValidation
	}
	switch query.State {
	case "", "trashing", "trashed", "restoring", "restored", "purging", "purged", "detached":
		return nil
	}
	return ErrValidation
}

func ValidateTransition(operation, state string, ancestorActive bool) error {
	if !ancestorActive {
		return ErrConflict
	}
	if operation == "trash" && state == "active" || operation == "restore" && state == "trashed" {
		return nil
	}
	return ErrConflict
}

func ValidateRestoreCapacity(kind string, count, maximum int) error {
	if kind == "media" && (count >= 10000 || maximum >= 10000) || kind == "chapter" && count >= 20000 {
		return ErrConflict
	}
	return nil
}
