package sqlite

import (
	"context"
	"database/sql"
	"errors"
	"strings"

	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
)

func loadStructure(ctx context.Context, tx *sql.Tx, comicID string) (*librarydomain.StructureTree, error) {
	return readStructure(ctx, tx, comicID, false)
}

func readStructure(ctx context.Context, tx *sql.Tx, comicID string, allowParked bool) (*librarydomain.StructureTree, error) {
	tree := &librarydomain.StructureTree{Nodes: map[string]*librarydomain.TreeNode{}, Children: map[string][]*librarydomain.TreeNode{}}
	if err := tx.QueryRowContext(ctx, "SELECT structure_version FROM active_comics AS comics WHERE id=?", comicID).Scan(&tree.Version); errors.Is(err, sql.ErrNoRows) {
		return nil, librarydomain.ErrNotFound
	} else if err != nil {
		return nil, err
	}
	for _, kind := range []string{"catalog", "chapter"} {
		query := "SELECT id,parent_id,title,sort_order,NULL FROM active_catalogs AS catalogs WHERE comic_id=? LIMIT ?"
		if kind == "chapter" {
			query = "SELECT id,catalog_id,title,sort_order,global_order FROM active_chapters AS chapters WHERE comic_id=? LIMIT ?"
		}
		rows, err := tx.QueryContext(ctx, query, comicID, librarydomain.MaxStructureNodes+1)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			node := &librarydomain.TreeNode{}
			node.Type = kind
			if err = rows.Scan(&node.ID, &node.ParentID, &node.Title, &node.SortOrder, &node.GlobalOrder); err != nil {
				rows.Close()
				return nil, err
			}
			if kind == "chapter" && !allowParked && (node.GlobalOrder == nil || *node.GlobalOrder < 1 || *node.GlobalOrder > librarydomain.MaxStructureNodes) {
				rows.Close()
				return nil, librarydomain.ErrConflict
			}
			node.Original = node.StructureNode
			tree.Nodes[librarydomain.NodeKey(node.NodeRef)] = node
			if len(tree.Nodes) > librarydomain.MaxStructureNodes {
				rows.Close()
				return nil, librarydomain.ErrValidation
			}
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return nil, err
		}
	}
	if err := tree.LinkChildren(); err != nil {
		return nil, err
	}
	return tree, nil
}

func (service *sqliteRepository) Structure(ctx context.Context, comicID string) (librarydomain.Structure, error) {
	tx, err := service.database.Reader.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return librarydomain.Structure{}, err
	}
	defer tx.Rollback()
	tree, err := loadStructure(ctx, tx, comicID)
	if err != nil {
		return librarydomain.Structure{}, err
	}
	result, err := tree.View()
	if err != nil {
		return result, err
	}
	return result, tx.Commit()
}

func (service *sqliteRepository) MutateStructure(ctx context.Context, comicID string, version int, change func(*librarydomain.StructureTree) (*librarydomain.TreeNode, error)) (librarydomain.StructureResult, error) {
	// 在同一写事务内加载快照、运行业务回调并提交，防止检查与写入之间发生竞态。
	tx, err := service.database.Writer.BeginTx(ctx, nil)
	if err != nil {
		return librarydomain.StructureResult{}, err
	}
	defer tx.Rollback()
	var lifecycle int
	if err = tx.QueryRowContext(ctx, "SELECT (SELECT COUNT(*) FROM lifecycle_runs WHERE comic_id=? AND completed=0)+(SELECT COUNT(*) FROM tasks WHERE comic_id=? AND type='lifecycle_audit' AND status IN('queued','running','cancelling'))", comicID, comicID).Scan(&lifecycle); err != nil {
		return librarydomain.StructureResult{}, err
	}
	if lifecycle > 0 {
		return librarydomain.StructureResult{}, librarydomain.ErrConflict
	}
	tree, err := loadStructure(ctx, tx, comicID)
	if err != nil {
		return librarydomain.StructureResult{}, err
	}
	if tree.Version != version {
		return librarydomain.StructureResult{}, librarydomain.ErrConflict
	}
	node, err := change(tree)
	if err != nil {
		return librarydomain.StructureResult{}, err
	}
	if err = persistStructure(ctx, tx, comicID, tree); err != nil {
		return librarydomain.StructureResult{}, err
	}
	if _, err = tx.ExecContext(ctx, "UPDATE comics SET structure_version=structure_version+1 WHERE id=?", comicID); err != nil {
		return librarydomain.StructureResult{}, err
	}
	result := librarydomain.StructureResult{Version: version + 1}
	if node != nil {
		copy := node.StructureNode
		result.Node = &copy
	}
	if err = tx.Commit(); err != nil {
		return librarydomain.StructureResult{}, err
	}
	return result, nil
}

func persistStructure(ctx context.Context, tx *sql.Tx, comicID string, tree *librarydomain.StructureTree) error {
	nodes, err := tree.PreparePositions()
	if err != nil {
		return err
	}

	for _, node := range nodes {
		if !node.Created {
			continue
		}
		if node.Type == "catalog" {
			_, err = tx.ExecContext(ctx, "INSERT INTO catalogs(id,comic_id,parent_id,title,sort_order) VALUES(?,?,?,?,0)", node.ID, comicID, node.ParentID, node.Title)
		} else {
			temporary := -(librarydomain.MaxStructureNodes + *node.GlobalOrder)
			_, err = tx.ExecContext(ctx, "INSERT INTO chapters(id,comic_id,catalog_id,title,global_order,sort_order) VALUES(?,?,?,?,?,0)", node.ID, comicID, node.ParentID, node.Title, temporary)
		}
		if err != nil {
			return err
		}
	}
	catalogUpdate, err := tx.PrepareContext(ctx, "UPDATE catalogs SET parent_id=?,title=? WHERE id=?")
	if err != nil {
		return err
	}
	defer catalogUpdate.Close()
	chapterUpdate, err := tx.PrepareContext(ctx, "UPDATE chapters SET catalog_id=?,title=? WHERE id=?")
	if err != nil {
		return err
	}
	defer chapterUpdate.Close()
	catalogPositions, chapterPositions := []*librarydomain.TreeNode{}, []*librarydomain.TreeNode{}
	for _, siblings := range tree.Children {
		for index, node := range siblings {
			node.SortOrder = index + 1
			if node.Created || node.SortOrder != node.Original.SortOrder {
				if node.Type == "catalog" {
					catalogPositions = append(catalogPositions, node)
				} else {
					chapterPositions = append(chapterPositions, node)
				}
			}
			if !node.Created && (node.Title != node.Original.Title || librarydomain.ParentKey(node.ParentID) != librarydomain.ParentKey(node.Original.ParentID)) {
				statement := chapterUpdate
				if node.Type == "catalog" {
					statement = catalogUpdate
				}
				if _, err = statement.ExecContext(ctx, node.ParentID, node.Title, node.ID); err != nil {
					return err
				}
			}
		}
	}
	if err = updatePositions(ctx, tx, "catalogs", "sort_order", catalogPositions); err != nil {
		return err
	}
	if err = updatePositions(ctx, tx, "chapters", "sort_order", chapterPositions); err != nil {
		return err
	}

	// 先释放发生变化的全局序号，再批量写入新序号，避免交换位置触发唯一约束。
	changed := []*librarydomain.TreeNode{}
	negativeIDs := []any{}
	for _, node := range nodes {
		if node.Type == "chapter" && (node.Created || node.Original.GlobalOrder == nil || *node.Original.GlobalOrder != *node.GlobalOrder) {
			changed = append(changed, node)
			if !node.Created {
				negativeIDs = append(negativeIDs, node.ID)
			}
		}
	}
	for start := 0; start < len(negativeIDs); start += 128 {
		batch := negativeIDs[start:min(start+128, len(negativeIDs))]
		placeholders := strings.Repeat("?,", len(batch)-1) + "?"
		if _, err = tx.ExecContext(ctx, "UPDATE chapters SET global_order=-global_order WHERE id IN("+placeholders+")", batch...); err != nil {
			return err
		}
	}
	if err = updatePositions(ctx, tx, "chapters", "global_order", changed); err != nil {
		return err
	}
	if tree.Deleted != "" {
		// 隐藏章节仍保留目录引用，不能为删除可见节点而丢掉其恢复位置。
		var hidden int
		if err = tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM chapters WHERE catalog_id=? AND lifecycle_state<>'active'", tree.Deleted).Scan(&hidden); err != nil {
			return err
		}
		if hidden > 0 {
			return librarydomain.ErrConflict
		}
		if _, err = tx.ExecContext(ctx, "DELETE FROM catalogs WHERE id=? AND comic_id=?", tree.Deleted, comicID); err != nil {
			return err
		}
	}
	return nil
}

// 位置更新按 128 项批量绑定，SQL 表/列仅由本模块固定常量指定。
func updatePositions(ctx context.Context, tx *sql.Tx, table, column string, nodes []*librarydomain.TreeNode) error {
	for start := 0; start < len(nodes); start += 128 {
		batch := nodes[start:min(start+128, len(nodes))]
		var query strings.Builder
		query.WriteString("UPDATE " + table + " SET " + column + "=CASE id ")
		arguments := make([]any, 0, len(batch)*3)
		for _, node := range batch {
			query.WriteString("WHEN ? THEN ? ")
			position := node.SortOrder
			if column == "global_order" {
				position = *node.GlobalOrder
			}
			arguments = append(arguments, node.ID, position)
		}
		query.WriteString("END WHERE id IN(")
		query.WriteString(strings.Repeat("?,", len(batch)-1) + "?)")
		for _, node := range batch {
			arguments = append(arguments, node.ID)
		}
		if _, err := tx.ExecContext(ctx, query.String(), arguments...); err != nil {
			return err
		}
	}
	return nil
}

func initializeImportStructure(ctx context.Context, tx *sql.Tx, comicID string) error {
	tree, err := loadStructure(ctx, tx, comicID)
	if err != nil {
		return err
	}
	if err = tree.InitializeOrder(); err != nil {
		return err
	}
	return persistStructure(ctx, tx, comicID, tree)
}
