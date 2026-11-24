package domain

import "bytes"

// TrashSnapshot 逐行记录媒体事实，不积累整批媒体对象。
// 章节/媒体的派生顺序与漫画总修订号不属于本目标范围。
type TrashSnapshot struct {
	data           bytes.Buffer
	bytes, members int
	lastID         string
	err            error
}
