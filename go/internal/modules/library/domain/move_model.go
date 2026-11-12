package domain

// StructureMove 只移动同一父目录下的节点；目标 null 表示漫画根目录。
type StructureMove struct {
	Version  int       `json:"version"`
	ParentID *string   `json:"parentId"`
	Items    []NodeRef `json:"items"`
}
