package domain

import (
	"bytes"
	_ "golang.org/x/image/webp"
	"image"
	_ "image/gif"
	_ "image/jpeg"
	_ "image/png"
	"io"
	"path"
	"strings"
)

func InspectImage(header []byte) (Inspection, error) {
	return InspectImageReader(bytes.NewReader(header))
}
func InspectImageReader(reader io.Reader) (Inspection, error) {
	var value Inspection
	checked := &inspectionReader{reader: reader}
	dimensions, format, err := image.DecodeConfig(checked)
	if checked.err != nil {
		return value, checked.err
	}
	if err != nil || dimensions.Width < 1 || dimensions.Height < 1 {
		return value, ErrImageInvalid
	}
	content := map[string]string{"jpeg": "image/jpeg", "png": "image/png", "gif": "image/gif", "webp": "image/webp"}[format]
	if content == "" {
		return value, ErrImageInvalid
	}
	return Inspection{MediaType: "image", ContentType: content, Width: dimensions.Width, Height: dimensions.Height}, nil
}

func (reader *inspectionReader) Read(buffer []byte) (int, error) {
	n, err := reader.reader.Read(buffer)
	if err != nil && err != io.EOF {
		reader.err = err
	}
	return n, err
}
func UploadMediaKind(name string) string {
	switch strings.ToLower(path.Ext(name)) {
	case ".jpg", ".jpeg", ".png", ".gif", ".webp":
		return "image"
	case ".mp4", ".webm", ".mov", ".mkv", ".avi":
		return "video"
	}
	return ""
}
func InspectionExtension(info Inspection) string {
	switch info.ContentType {
	case "image/jpeg":
		return ".jpg"
	case "image/png":
		return ".png"
	case "image/gif":
		return ".gif"
	case "image/webp":
		return ".webp"
	case "video/mp4":
		return ".mp4"
	case "video/webm":
		return ".webm"
	case "video/quicktime":
		return ".mov"
	case "video/x-matroska":
		return ".mkv"
	case "video/x-msvideo":
		return ".avi"
	}
	return ""
}
func ValidateInspection(info Inspection, name string) error {
	if info.Width < 1 || info.Height < 1 || info.MediaType != UploadMediaKind(name) || InspectionExtension(info) == "" {
		return ErrUploadMedia
	}
	if info.MediaType == "video" && info.Video == nil {
		return ErrUploadMedia
	}
	if info.MediaType == "image" && info.Video != nil {
		return ErrUploadMedia
	}
	return nil
}
