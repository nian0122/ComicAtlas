package domain

// validateMediaSet 要求重排覆盖快照中的完整媒体集合。
func ValidateMediaSet(requested, actual []string) error {
	if len(requested) != len(actual) {
		return ErrValidation
	}
	seen := make(map[string]bool, len(requested))
	for _, id := range requested {
		if id == "" || seen[id] {
			return ErrValidation
		}
		seen[id] = true
	}
	for _, id := range actual {
		if !seen[id] {
			return ErrValidation
		}
		delete(seen, id)
	}
	return nil
}
