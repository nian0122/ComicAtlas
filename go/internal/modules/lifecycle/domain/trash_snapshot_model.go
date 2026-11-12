package domain

import "hash"

// TrashSnapshot 逐行取摘要，不积累整批媒体事实。
// 章节/媒体的派生顺序与漫画总修订号不属于本目标范围。
type TrashSnapshot struct {
	digest         hash.Hash
	bytes, members int
	lastID         string
	err            error
}
