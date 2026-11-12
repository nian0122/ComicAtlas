package domain

import "encoding/json"

// 回收条目和章节/媒体目标没有活动漫画版本统计；不要把默认零值展示成实际观察。
func (item Item) MarshalJSON() ([]byte, error) {
	type fields Item
	if item.EntryID == "" && item.TargetType != "chapter" && item.TargetType != "media" {
		return json.Marshal(fields(item))
	}
	return json.Marshal(struct {
		fields
		ScopeRevision *int64 `json:"scopeRevision,omitempty"`
		ImageCount    *int   `json:"imageCount,omitempty"`
		VideoCount    *int   `json:"videoCount,omitempty"`
		Version       *int   `json:"version,omitempty"`
	}{fields: fields(item)})
}
