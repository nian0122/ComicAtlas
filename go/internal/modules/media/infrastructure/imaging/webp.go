package imaging

import "encoding/binary"

// 只接受单个 VP8 图块，排除动画、透明通道和无损编码。dwebp 会读入压缩文件，故单独限制输入体积。
func isSimpleLossyWebP(header []byte, size int64) bool {
	if size > 128<<20 || len(header) < 20 || string(header[:4]) != "RIFF" || string(header[8:16]) != "WEBPVP8 " {
		return false
	}
	chunk := int64(binary.LittleEndian.Uint32(header[16:20]))
	return int64(binary.LittleEndian.Uint32(header[4:8]))+8 == size && 20+chunk+(chunk&1) == size
}

// 仅将可识别的静态 WebP 直接交给 cwebp；其余布局走规范化路径。
func isStaticWebP(header []byte) bool {
	if len(header) < 20 || string(header[:4]) != "RIFF" || string(header[8:12]) != "WEBP" {
		return false
	}
	for offset := 12; offset+8 <= len(header); {
		length := uint64(binary.LittleEndian.Uint32(header[offset+4 : offset+8]))
		kind := string(header[offset : offset+4])
		switch kind {
		case "ANIM", "ANMF":
			return false
		case "VP8X":
			if length < 10 || offset+9 > len(header) || header[offset+8]&0x02 != 0 {
				return false
			}
		case "VP8 ", "VP8L":
			return true
		}
		padded := length + (length & 1)
		if padded > uint64(len(header)-offset-8) {
			return false
		}
		offset += 8 + int(padded)
	}
	return false
}
