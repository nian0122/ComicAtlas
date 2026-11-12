package domain

// ComicInfo 保存规范化的来源快照，不随手动资料编辑改写。
type ComicInfo struct {
	Series  string   `json:"series"`
	Title   string   `json:"title"`
	Number  string   `json:"number"`
	Writer  string   `json:"writer"`
	Summary string   `json:"summary"`
	Genre   string   `json:"genre"`
	Tags    []string `json:"tags"`
}
