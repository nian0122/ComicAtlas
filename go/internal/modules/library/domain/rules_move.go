package domain

func (tree *StructureTree) MoveGroup(parent *string, references []NodeRef) error {
	if len(references) == 0 || len(references) > MaxStructureNodes {
		return ErrValidation
	}
	if err := tree.CheckParent(parent); err != nil {
		return err
	}
	selected := map[string]bool{}
	source := ""
	for i, reference := range references {
		key := NodeKey(reference)
		node := tree.Nodes[key]
		if node == nil || selected[key] {
			return ErrValidation
		}
		if i == 0 {
			source = ParentKey(node.ParentID)
		}
		if ParentKey(node.ParentID) != source {
			return ErrValidation
		}
		selected[key] = true
		if node.Type == "catalog" {
			for current := ParentKey(parent); current != ""; {
				if current == node.ID {
					return ErrValidation
				}
				current = ParentKey(tree.Nodes[NodeKey(NodeRef{Type: "catalog", ID: current})].ParentID)
			}
		}
	}
	if source == ParentKey(parent) {
		return ErrValidation
	}
	// 使用快照中的同级顺序，而非客户端提交顺序。
	remaining := make([]*TreeNode, 0, len(tree.Children[source]))
	moved := make([]*TreeNode, 0, len(references))
	for _, node := range tree.Children[source] {
		if selected[NodeKey(node.NodeRef)] {
			moved = append(moved, node)
		} else {
			remaining = append(remaining, node)
		}
	}
	for _, node := range moved {
		node.ParentID = parent
	}
	tree.Children[source] = remaining
	tree.Children[ParentKey(parent)] = append(tree.Children[ParentKey(parent)], moved...)
	return nil
}
