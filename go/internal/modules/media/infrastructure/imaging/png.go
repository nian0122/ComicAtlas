package imaging

import "encoding/binary"

const pngSignature = "\x89PNG\r\n\x1a\n"

// isStaticPNG rejects APNG and incomplete headers from the direct cwebp path.
// Unknown chunk layout falls back to the existing Go decoder path.
func isStaticPNG(header []byte) bool {
	if len(header) < len(pngSignature) || string(header[:len(pngSignature)]) != pngSignature {
		return false
	}
	for offset := len(pngSignature); offset+12 <= len(header); {
		length := uint64(binary.BigEndian.Uint32(header[offset : offset+4]))
		kind := string(header[offset+4 : offset+8])
		if kind == "acTL" || kind == "IEND" {
			return false
		}
		// APNG requires acTL before its first IDAT; the IDAT payload itself may exceed the header budget.
		if kind == "IDAT" {
			return true
		}
		if length > uint64(len(header)-offset-12) {
			return false
		}
		offset += 12 + int(length)
	}
	return false
}
