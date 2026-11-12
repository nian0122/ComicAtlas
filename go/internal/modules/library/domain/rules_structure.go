package domain

import (
	"sort"
)

const MaxStructureNodes = 20000

const MaxStructureDepth = 64

func NodeKey(reference NodeRef) string { return reference.Type + ":" + reference.ID }

func ParentKey(parent *string) string {
	if parent == nil {
		return ""
	}
	return *parent
}

func NullableParent(parent string) *string {
	if parent == "" {
		return nil
	}
	value := parent
	return &value
}

func (tree *StructureTree) sortChildren() {
	for _, nodes := range tree.Children {
		sort.Slice(nodes, func(i, j int) bool {
			if nodes[i].SortOrder != nodes[j].SortOrder {
				return nodes[i].SortOrder < nodes[j].SortOrder
			}
			return NodeKey(nodes[i].NodeRef) < NodeKey(nodes[j].NodeRef)
		})
	}
}

func (tree *StructureTree) ordered() ([]*TreeNode, error) {
	result := make([]*TreeNode, 0, len(tree.Nodes))
	seen := map[string]bool{}
	var walk func(string, int) error
	walk = func(parent string, depth int) error {
		if depth > MaxStructureDepth {
			return ErrValidation
		}
		for _, node := range tree.Children[parent] {
			key := NodeKey(node.NodeRef)
			if seen[key] {
				return ErrConflict
			}
			seen[key] = true
			result = append(result, node)
			if node.Type == "catalog" {
				if err := walk(node.ID, depth+1); err != nil {
					return err
				}
			}
		}
		return nil
	}
	if err := walk("", 0); err != nil {
		return nil, err
	}
	if len(result) != len(tree.Nodes) {
		return nil, ErrConflict
	}
	return result, nil
}

func (tree *StructureTree) View() (Structure, error) {
	result := Structure{Version: tree.Version, Nodes: []StructureNode{}}
	nodes, err := tree.ordered()
	if err != nil {
		return result, err
	}
	for _, node := range nodes {
		result.Nodes = append(result.Nodes, node.StructureNode)
	}
	return result, nil
}

func (tree *StructureTree) CheckParent(parent *string) error {
	if parent != nil && (*parent == "" || tree.Nodes[NodeKey(NodeRef{Type: "catalog", ID: *parent})] == nil) {
		return ErrValidation
	}
	return nil
}

func (tree *StructureTree) Remove(node *TreeNode) {
	parent := ParentKey(node.ParentID)
	nodes := tree.Children[parent]
	for index, candidate := range nodes {
		if candidate == node {
			tree.Children[parent] = append(nodes[:index], nodes[index+1:]...)
			return
		}
	}
}

func (tree *StructureTree) LinkChildren() error {
	for _, node := range tree.Nodes {
		parent := ParentKey(node.ParentID)
		if parent != "" && tree.Nodes[NodeKey(NodeRef{Type: "catalog", ID: parent})] == nil {
			return ErrConflict
		}
		tree.Children[parent] = append(tree.Children[parent], node)
	}
	tree.sortChildren()
	if _, err := tree.ordered(); err != nil {
		return err
	}
	return nil
}

func (tree *StructureTree) PreparePositions() ([]*TreeNode, error) {
	nodes, err := tree.ordered()
	if err != nil {
		return nil, err
	}
	global := 0
	for _, node := range nodes {
		if node.Type == "chapter" {
			global++
			value := global
			node.GlobalOrder = &value
		}
	}

	return nodes, nil
}

func (tree *StructureTree) InitializeOrder() error {
	ordered, err := tree.ordered()
	if err != nil {
		return err
	}
	first := map[string]int{}
	maximum := 0
	for index := len(ordered) - 1; index >= 0; index-- {
		node := ordered[index]
		position := 0
		if node.Type == "chapter" {
			position = *node.GlobalOrder
			if position > maximum {
				maximum = position
			}
			node.SortOrder = position*2 + 1
		} else {
			position = first[node.ID]
			if position > 0 {
				node.SortOrder = position * 2
			}
		}
		parent := ParentKey(node.ParentID)
		if position > 0 && (first[parent] == 0 || position < first[parent]) {
			first[parent] = position
		}
	}
	for _, node := range ordered {
		if node.Type == "catalog" && first[node.ID] == 0 {
			node.SortOrder = 2 * (maximum + node.SortOrder + 1)
		}
	}
	tree.sortChildren()
	return nil
}
