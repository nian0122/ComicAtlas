package domain

import (
	"crypto/rand"
	"encoding/hex"
)

// newID 提供本模块的不透明标识；随机生成与纯业务规则分开。
func NewID(prefix string) (string, error) {
	var token [16]byte
	if _, err := rand.Read(token[:]); err != nil {
		return "", err
	}
	return prefix + hex.EncodeToString(token[:]), nil
}
