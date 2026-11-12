package domain

import (
	"encoding/json"
	"io"
)

func (writer *directoryDocumentBuffer) Write(data []byte) (int, error) {
	if len(data) > MaxSnapshotBytes-writer.value.Len() {
		return 0, ErrValidation
	}
	return writer.value.Write(data)
}

func directoryField(writer io.Writer, name string, value any, comma bool) error {
	if comma {
		if _, err := io.WriteString(writer, ","); err != nil {
			return err
		}
	}
	key, _ := json.Marshal(name)
	if _, err := writer.Write(key); err != nil {
		return err
	}
	if _, err := io.WriteString(writer, ":"); err != nil {
		return err
	}
	return json.NewEncoder(writer).Encode(value)
}

func directoryArray[T any](writer io.Writer, name string, rows []T) error {
	if _, err := io.WriteString(writer, `,"`+name+`":[`); err != nil {
		return err
	}
	encoder := json.NewEncoder(writer)
	for index, row := range rows {
		if index > 0 {
			if _, err := io.WriteString(writer, ","); err != nil {
				return err
			}
		}
		if err := encoder.Encode(row); err != nil {
			return err
		}
	}
	_, err := io.WriteString(writer, "]")
	return err
}

func directoryTrashArray(writer io.Writer, rows []DirectoryTrash) error {
	if _, err := io.WriteString(writer, `,"trash":[`); err != nil {
		return err
	}
	for index, row := range rows {
		if index > 0 {
			if _, err := io.WriteString(writer, ","); err != nil {
				return err
			}
		}
		if _, err := io.WriteString(writer, "{"); err != nil {
			return err
		}
		for index, field := range []struct {
			name  string
			value any
		}{
			{"state", row.State}, {"id", row.ID}, {"targetType", row.TargetType}, {"targetId", row.TargetID}, {"chapterId", row.ChapterID}, {"title", row.Title}, {"createdAt", row.CreatedAt}, {"updatedAt", row.UpdatedAt}, {"taskId", row.TaskID},
		} {
			if err := directoryField(writer, field.name, field.value, index > 0); err != nil {
				return err
			}
		}
		if err := directoryArray(writer, "candidates", row.Candidates); err != nil {
			return err
		}
		if _, err := io.WriteString(writer, "}"); err != nil {
			return err
		}
	}
	_, err := io.WriteString(writer, "]")
	return err
}

// 文档数组逐项编码，单个大回收记录也逐候选编码，不先构造整份 JSON 再判断预算。
func directoryDocument(value DirectoryInventory, stamp string, files []DirectoryPlacement) ([]byte, error) {
	writer := &directoryDocumentBuffer{}
	if _, err := io.WriteString(writer, "{"); err != nil {
		return nil, err
	}
	for index, field := range []struct {
		name  string
		value any
	}{{"version", 1}, {"kind", "directory_move"}, {"inventorySha256", stamp}} {
		if err := directoryField(writer, field.name, field.value, index > 0); err != nil {
			return nil, err
		}
	}
	if _, err := io.WriteString(writer, `,"inventory":{`); err != nil {
		return nil, err
	}
	for index, field := range []struct {
		name  string
		value any
	}{{"version", value.Version}, {"comic", value.Comic}, {"sourceComicInfo", value.ComicInfo}, {"structureVersion", value.StructureVersion}} {
		if err := directoryField(writer, field.name, field.value, index > 0); err != nil {
			return nil, err
		}
	}
	if err := directoryArray(writer, "catalogs", value.Catalogs); err != nil {
		return nil, err
	}
	if err := directoryArray(writer, "chapters", value.Chapters); err != nil {
		return nil, err
	}
	if err := directoryArray(writer, "media", value.Media); err != nil {
		return nil, err
	}
	if len(value.SuppressedPaths) > 0 {
		if err := directoryArray(writer, "suppressedPaths", value.SuppressedPaths); err != nil {
			return nil, err
		}
	}
	if err := directoryArray(writer, "retired", value.Retired); err != nil {
		return nil, err
	}
	if err := directoryTrashArray(writer, value.Trash); err != nil {
		return nil, err
	}
	if err := directoryArray(writer, "reactions", value.Reactions); err != nil {
		return nil, err
	}
	if err := directoryField(writer, "progress", value.Progress, true); err != nil {
		return nil, err
	}
	if _, err := io.WriteString(writer, "}"); err != nil {
		return nil, err
	}
	if err := directoryArray(writer, "files", files); err != nil {
		return nil, err
	}
	if _, err := io.WriteString(writer, "}"); err != nil {
		return nil, err
	}
	return writer.value.Bytes(), nil
}
