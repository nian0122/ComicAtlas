package archive

import (
	"archive/zip"
	"hash/crc32"
	"strings"
	"unicode/utf8"
)

// ZIP 的 0x7075 Unicode Path 扩展携带原始文件名校验和与 UTF-8 名称。
// 仅采用校验通过的标准字段，不猜测 GBK、Shift-JIS 等本地编码。
func decodeUnicodePath(file *zip.File) error {
	raw := file.Name
	found := false
	for fields := file.Extra; len(fields) > 0; {
		if len(fields) < 4 {
			return ErrInvalid
		}
		size := int(le.Uint16(fields[2:]))
		if size+4 > len(fields) {
			return ErrInvalid
		}
		if le.Uint16(fields) == 0x7075 {
			if found || size < 6 || fields[4] != 1 || le.Uint32(fields[5:]) != crc32.ChecksumIEEE([]byte(raw)) {
				return ErrInvalid
			}
			name := string(fields[9 : size+4])
			if !utf8.ValidString(name) || !safeName(name) || strings.HasSuffix(name, "/") != strings.HasSuffix(raw, "/") || file.Flags&0x800 != 0 && name != raw {
				return ErrInvalid
			}
			file.Name = name
			file.NonUTF8 = false
			found = true
		}
		fields = fields[size+4:]
	}
	return nil
}
