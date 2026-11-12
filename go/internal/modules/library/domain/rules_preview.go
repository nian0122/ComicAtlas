package domain

func ValidatePreviewNodes(nodes []NodeRef) error {
	if len(nodes) < 1 || len(nodes) > 50 {
		return ErrValidation
	}
	seen := map[string]bool{}
	for _, node := range nodes {
		key := NodeKey(node)
		if (node.Type != "catalog" && node.Type != "chapter") || node.ID == "" || len(node.ID) > 200 || seen[key] {
			return ErrValidation
		}
		seen[key] = true
	}
	return nil
}
