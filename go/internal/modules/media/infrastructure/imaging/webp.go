package imaging

import "encoding/binary"

// isStaticWebP accepts only complete static-image headers for direct cwebp input.
// Animation chunks, unknown layouts and truncated headers use Go normalization.
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
