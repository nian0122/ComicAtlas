package imaging

import (
	"bytes"
	"encoding/binary"
	"image"
	"image/color"
)

// jpegOrientation 仅检查有界 JPEG APP1/Exif 目录，不保留任意元数据。
func jpegOrientation(data []byte) int {
	if len(data) < 4 || data[0] != 0xff || data[1] != 0xd8 {
		return 1
	}
	for offset := 2; offset+4 <= len(data); {
		if data[offset] != 0xff {
			break
		}
		marker := data[offset+1]
		offset += 2
		if marker == 0xff {
			offset--
			continue
		}
		if marker == 0xda || marker == 0xd9 {
			break
		}
		length := int(binary.BigEndian.Uint16(data[offset : offset+2]))
		if length < 2 || offset+length > len(data) {
			break
		}
		segment := data[offset+2 : offset+length]
		offset += length
		if marker != 0xe1 || !bytes.HasPrefix(segment, []byte("Exif\x00\x00")) {
			continue
		}
		tiff := segment[6:]
		if len(tiff) < 8 {
			continue
		}
		var order binary.ByteOrder
		if string(tiff[:2]) == "II" {
			order = binary.LittleEndian
		} else if string(tiff[:2]) == "MM" {
			order = binary.BigEndian
		} else {
			continue
		}
		if order.Uint16(tiff[2:4]) != 42 {
			continue
		}
		ifd := uint64(order.Uint32(tiff[4:8]))
		if ifd+2 > uint64(len(tiff)) {
			continue
		}
		count := int(order.Uint16(tiff[ifd : ifd+2]))
		for index := 0; index < count; index++ {
			position := ifd + 2 + uint64(index)*12
			if position+12 > uint64(len(tiff)) {
				break
			}
			entry := tiff[position : position+12]
			if order.Uint16(entry[:2]) == 0x112 && order.Uint16(entry[2:4]) == 3 && order.Uint32(entry[4:8]) == 1 {
				value := int(order.Uint16(entry[8:10]))
				if value >= 1 && value <= 8 {
					return value
				}
			}
		}
	}
	return 1
}

type oriented struct {
	source      image.Image
	orientation int
}

func (value oriented) ColorModel() color.Model { return value.source.ColorModel() }
func (value oriented) Bounds() image.Rectangle {
	bounds := value.source.Bounds()
	width, height := bounds.Dx(), bounds.Dy()
	if value.orientation >= 5 {
		width, height = height, width
	}
	return image.Rect(0, 0, width, height)
}
func (value oriented) At(x, y int) color.Color {
	b := value.source.Bounds()
	w, h := b.Dx(), b.Dy()
	switch value.orientation {
	case 2:
		x = w - 1 - x
	case 3:
		x, y = w-1-x, h-1-y
	case 4:
		y = h - 1 - y
	case 5:
		x, y = y, x
	case 6:
		x, y = y, h-1-x
	case 7:
		x, y = w-1-y, h-1-x
	case 8:
		x, y = w-1-y, x
	}
	return value.source.At(x+b.Min.X, y+b.Min.Y)
}
