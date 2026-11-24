// Package stableid 为派生的内部标识生成稳定、非加密的短字符串。
// 它不用于内容完整性判断；文件一致性由路径、大小、修改时间和文件身份校验。
package stableid

import (
	"fmt"
	"hash/fnv"
)

// Hex 返回 32 位小写十六进制标识。两个独立的 FNV-1a 64 位结果降低碰撞概率。
func Hex(value string) string {
	first := fnv.New64a()
	_, _ = first.Write([]byte("id-a\x00" + value))
	second := fnv.New64a()
	_, _ = second.Write([]byte("id-b\x00" + value))
	return fmt.Sprintf("%016x%016x", first.Sum64(), second.Sum64())
}
