package application

import (
	"context"
	"strings"

	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
)

func (service *Service) Structure(ctx context.Context, comicID string) (librarydomain.Structure, error) {
	return service.repository.Structure(ctx, comicID)
}

func (service *Service) mutateStructure(ctx context.Context, comicID string, version int, change func(*librarydomain.StructureTree) (*librarydomain.TreeNode, error)) (librarydomain.StructureResult, error) {
	if version < 1 {
		return librarydomain.StructureResult{}, librarydomain.ErrValidation
	}
	return service.repository.MutateStructure(ctx, comicID, version, change)
}

func (service *Service) StructureNode(ctx context.Context, comicID string, reference librarydomain.NodeRef) (librarydomain.StructureResult, error) {
	structure, err := service.Structure(ctx, comicID)
	if err != nil {
		return librarydomain.StructureResult{}, err
	}
	for _, node := range structure.Nodes {
		if node.NodeRef == reference {
			copy := node
			return librarydomain.StructureResult{Version: structure.Version, Node: &copy}, nil
		}
	}
	return librarydomain.StructureResult{}, librarydomain.ErrNotFound
}

func (service *Service) CreateNode(ctx context.Context, comicID, kind string, change librarydomain.StructureChange) (librarydomain.StructureResult, error) {
	if (kind != "catalog" && kind != "chapter") || !librarydomain.ValidName(strings.TrimSpace(change.Title), 500) {
		return librarydomain.StructureResult{}, librarydomain.ErrValidation
	}
	return service.mutateStructure(ctx, comicID, change.Version, func(tree *librarydomain.StructureTree) (*librarydomain.TreeNode, error) {
		if len(tree.Nodes) >= librarydomain.MaxStructureNodes {
			return nil, librarydomain.ErrValidation
		}
		if err := tree.CheckParent(change.ParentID); err != nil {
			return nil, err
		}
		id, err := librarydomain.NewID(kind + "_")
		if err != nil {
			return nil, err
		}
		node := &librarydomain.TreeNode{StructureNode: librarydomain.StructureNode{NodeRef: librarydomain.NodeRef{Type: kind, ID: id}, ParentID: change.ParentID, Title: strings.TrimSpace(change.Title)}, Created: true}
		tree.Nodes[librarydomain.NodeKey(node.NodeRef)] = node
		parent := librarydomain.ParentKey(node.ParentID)
		tree.Children[parent] = append(tree.Children[parent], node)
		return node, nil
	})
}

func (service *Service) UpdateNode(ctx context.Context, comicID string, reference librarydomain.NodeRef, change librarydomain.StructureChange) (librarydomain.StructureResult, error) {
	if !librarydomain.ValidName(strings.TrimSpace(change.Title), 500) {
		return librarydomain.StructureResult{}, librarydomain.ErrValidation
	}
	return service.mutateStructure(ctx, comicID, change.Version, func(tree *librarydomain.StructureTree) (*librarydomain.TreeNode, error) {
		node := tree.Nodes[librarydomain.NodeKey(reference)]
		if node == nil {
			return nil, librarydomain.ErrNotFound
		}
		if err := tree.CheckParent(change.ParentID); err != nil {
			return nil, err
		}
		if node.Type == "catalog" {
			for current := librarydomain.ParentKey(change.ParentID); current != ""; {
				if current == node.ID {
					return nil, librarydomain.ErrValidation
				}
				current = librarydomain.ParentKey(tree.Nodes[librarydomain.NodeKey(librarydomain.NodeRef{Type: "catalog", ID: current})].ParentID)
			}
		}
		if librarydomain.ParentKey(node.ParentID) != librarydomain.ParentKey(change.ParentID) {
			tree.Remove(node)
			node.ParentID = change.ParentID
			parent := librarydomain.ParentKey(node.ParentID)
			tree.Children[parent] = append(tree.Children[parent], node)
		}
		node.Title = strings.TrimSpace(change.Title)
		return node, nil
	})
}

func (service *Service) ReorderStructure(ctx context.Context, comicID string, version int, parent *string, references []librarydomain.NodeRef) (librarydomain.StructureResult, error) {
	if references == nil || len(references) > librarydomain.MaxStructureNodes {
		return librarydomain.StructureResult{}, librarydomain.ErrValidation
	}
	return service.mutateStructure(ctx, comicID, version, func(tree *librarydomain.StructureTree) (*librarydomain.TreeNode, error) {
		if err := tree.CheckParent(parent); err != nil {
			return nil, err
		}
		key := librarydomain.ParentKey(parent)
		if len(references) != len(tree.Children[key]) {
			return nil, librarydomain.ErrValidation
		}
		seen := map[string]bool{}
		ordered := make([]*librarydomain.TreeNode, 0, len(references))
		for _, reference := range references {
			node := tree.Nodes[librarydomain.NodeKey(reference)]
			if node == nil || seen[librarydomain.NodeKey(reference)] || librarydomain.ParentKey(node.ParentID) != key {
				return nil, librarydomain.ErrValidation
			}
			seen[librarydomain.NodeKey(reference)] = true
			ordered = append(ordered, node)
		}
		tree.Children[key] = ordered
		return nil, nil
	})
}

func (service *Service) DeleteCatalog(ctx context.Context, comicID, id string, version int, reparentTo *string) (librarydomain.StructureResult, error) {
	return service.mutateStructure(ctx, comicID, version, func(tree *librarydomain.StructureTree) (*librarydomain.TreeNode, error) {
		node := tree.Nodes[librarydomain.NodeKey(librarydomain.NodeRef{Type: "catalog", ID: id})]
		if node == nil {
			return nil, librarydomain.ErrNotFound
		}
		if err := tree.CheckParent(reparentTo); err != nil {
			return nil, err
		}
		for current := librarydomain.ParentKey(reparentTo); current != ""; {
			if current == id {
				return nil, librarydomain.ErrValidation
			}
			current = librarydomain.ParentKey(tree.Nodes[librarydomain.NodeKey(librarydomain.NodeRef{Type: "catalog", ID: current})].ParentID)
		}
		children := tree.Children[id]
		source, destination := librarydomain.ParentKey(node.ParentID), librarydomain.ParentKey(reparentTo)
		if source == destination {
			siblings := tree.Children[source]
			for index, candidate := range siblings {
				if candidate == node {
					merged := append([]*librarydomain.TreeNode{}, siblings[:index]...)
					merged = append(merged, children...)
					merged = append(merged, siblings[index+1:]...)
					tree.Children[source] = merged
					break
				}
			}
		} else {
			tree.Remove(node)
			tree.Children[destination] = append(tree.Children[destination], children...)
		}
		for _, child := range children {
			child.ParentID = librarydomain.NullableParent(destination)
		}
		delete(tree.Children, id)
		delete(tree.Nodes, librarydomain.NodeKey(node.NodeRef))
		tree.Deleted = id
		return nil, nil
	})
}
