package domain

import "encoding/json"

// 保持内部字符串定位便于调度，公开无关联值统一为 null。
func (task Task) MarshalJSON() ([]byte, error) {
	type value Task
	optional := func(id string) *string {
		if id == "" {
			return nil
		}
		return &id
	}
	return json.Marshal(struct {
		value
		ComicID    *string `json:"comicId"`
		TargetType *string `json:"targetType"`
		TargetID   *string `json:"targetId"`
	}{value(task), optional(task.ComicID), optional(task.TargetType), optional(task.TargetID)})
}
