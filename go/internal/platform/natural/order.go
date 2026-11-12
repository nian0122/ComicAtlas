// Package natural 定义新后端统一的名称排序，不沿用旧 ICU 二进制排序键。
package natural

import "strings"

// Compare 忽略大小写，ASCII 连续数字按数值比较，数值相同短数字串优先。
func Compare(left, right string) int {
	left, right = strings.ToLower(left), strings.ToLower(right)
	for len(left) > 0 && len(right) > 0 {
		if digit(left[0]) && digit(right[0]) {
			li, ri := 0, 0
			for li < len(left) && digit(left[li]) {
				li++
			}
			for ri < len(right) && digit(right[ri]) {
				ri++
			}
			ln, rn := strings.TrimLeft(left[:li], "0"), strings.TrimLeft(right[:ri], "0")
			if len(ln) != len(rn) {
				if len(ln) < len(rn) {
					return -1
				}
				return 1
			}
			if comparison := strings.Compare(ln, rn); comparison != 0 {
				return comparison
			}
			if li != ri {
				if li < ri {
					return -1
				}
				return 1
			}
			left, right = left[li:], right[ri:]
			continue
		}
		if left[0] != right[0] {
			if left[0] < right[0] {
				return -1
			}
			return 1
		}
		left, right = left[1:], right[1:]
	}
	return strings.Compare(left, right)
}

func digit(value byte) bool { return value >= '0' && value <= '9' }
