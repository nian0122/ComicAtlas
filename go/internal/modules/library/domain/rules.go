package domain

func ValidateListQuery(query ListQuery) error {
	if !ValidPage(query.Page, query.PageSize) || len(query.Keyword) > 500 {
		return ErrValidation
	}
	if len(query.TagIDs) > 100 {
		return ErrValidation
	}
	seen := make(map[string]bool, len(query.TagIDs))
	for _, id := range query.TagIDs {
		if id == "" || len(id) > 128 || seen[id] {
			return ErrValidation
		}
		seen[id] = true
	}
	switch query.Sort {
	case "", "title", "author", "createdAt", "lastReadTime":
	default:
		return ErrValidation
	}
	switch query.Order {
	case "", "asc", "desc":
	default:
		return ErrValidation
	}
	switch query.ReadStatus {
	case "", "read", "unread", "reading", "completed":
	default:
		return ErrValidation
	}
	return nil
}
