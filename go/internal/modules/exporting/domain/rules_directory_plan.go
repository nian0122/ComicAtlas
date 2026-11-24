package domain

import (
	"encoding/json"
	"io"
	"strings"
)

func ValidateDirectoryPlanning(value DirectoryPlanningResult) error {
	if err := ValidateDirectoryPlans(value.Plans); err != nil || len(value.Inventories) != len(value.Plans) || !TaskID(value.Seal.TaskID) || value.Seal.ObservedBytes < 0 || value.Seal.ObservedBytes > 100<<30 {
		return ErrManifest
	}
	ids := make([]string, 0, len(value.Plans))
	count := 0
	for index, plan := range value.Plans {
		identity := value.Inventories[index]
		if identity.ComicID != plan.ComicID {
			return ErrManifest
		}
		ids = append(ids, identity.ComicID)
		count += len(plan.Files)
	}
	if DirectoryIDs(ids) != nil || value.Seal.FileCount != count {
		return ErrManifest
	}
	actual, err := DirectoryMeasurementSeal(value.Seal.TaskID, value.Plans, value.Measurements)
	if err != nil || actual != value.Seal {
		return ErrManifest
	}
	return nil
}

func EncodeDirectoryPlanning(value DirectoryPlanningResult) (string, error) {
	if err := ValidateDirectoryPlanning(value); err != nil {
		return "", err
	}
	writer := &directoryPlanBuffer{}
	encoder := json.NewEncoder(writer)
	if err := encoder.Encode(directoryPlanHeader{Version: DirectoryPlanVersion, Books: len(value.Plans), Seal: value.Seal}); err != nil {
		return "", err
	}
	if err := encodeDirectoryRows(encoder, value.Measurements); err != nil {
		return "", err
	}
	for index, plan := range value.Plans {
		header := directoryPlanBook{Identity: value.Inventories[index], Name: plan.Name, Files: len(plan.Files), Directories: len(plan.Directories), Owned: len(plan.OwnedDirectories), Absences: len(plan.AbsentSources), Documents: len(plan.Documents), Trash: len(plan.TrashIDs)}
		if err := encoder.Encode(header); err != nil {
			return "", err
		}
		if err := encodeDirectoryRows(encoder, plan.TrashIDs); err != nil {
			return "", err
		}
		if err := encodeDirectoryRows(encoder, plan.Files); err != nil {
			return "", err
		}
		if err := encodeDirectoryRows(encoder, plan.Directories); err != nil {
			return "", err
		}
		if err := encodeDirectoryRows(encoder, plan.OwnedDirectories); err != nil {
			return "", err
		}
		if err := encodeDirectoryRows(encoder, plan.AbsentSources); err != nil {
			return "", err
		}
		if err := encodeDirectoryRows(encoder, plan.Documents); err != nil {
			return "", err
		}
	}
	return writer.value.String(), nil
}

func decodeDirectoryRows[T any](decoder *json.Decoder, count int) ([]T, error) {
	rows := make([]T, 0, count)
	for index := 0; index < count; index++ {
		var row T
		if err := decoder.Decode(&row); err != nil {
			return nil, ErrManifest
		}
		rows = append(rows, row)
	}
	return rows, nil
}

func DecodeDirectoryPlanning(data string) (DirectoryPlanningResult, error) {
	var result DirectoryPlanningResult
	if len(data) == 0 || len(data) > MaxDirectoryPlanReadBytes {
		return result, ErrManifest
	}
	decoder := json.NewDecoder(strings.NewReader(data))
	decoder.DisallowUnknownFields()
	var header directoryPlanHeader
	if decoder.Decode(&header) != nil || header.Version != DirectoryPlanVersion || header.Books < 1 || header.Books > MaxDirectoryBooks {
		return result, ErrManifest
	}
	result.Seal = header.Seal
	if header.Seal.FileCount < 0 || header.Seal.FileCount > MaxDirectoryFiles {
		return result, ErrManifest
	}
	var readErr error
	result.Measurements, readErr = decodeDirectoryRows[DirectoryFileState](decoder, header.Seal.FileCount)
	if readErr != nil {
		return result, readErr
	}
	files, nodes, absences, documents, trash := 0, 0, 0, 0, 0
	for index := 0; index < header.Books; index++ {
		var book directoryPlanBook
		if decoder.Decode(&book) != nil || book.Files < 0 || book.Files > MaxDirectoryFiles-files || book.Directories < 0 || book.Directories > MaxNodes-nodes || book.Owned < 0 || book.Owned > MaxNodes-nodes-book.Directories || book.Absences < 0 || book.Absences > MaxDirectoryAbsences-absences || book.Documents < 0 || book.Documents > MaxNodes-documents || book.Trash < 0 || book.Trash > MaxNodes-trash {
			return DirectoryPlanningResult{}, ErrManifest
		}
		files, nodes, absences, documents = files+book.Files, nodes+book.Directories+book.Owned, absences+book.Absences, documents+book.Documents
		trash += book.Trash
		plan := DirectoryPlan{ComicID: book.Identity.ComicID, Name: book.Name}
		var err error
		if plan.TrashIDs, err = decodeDirectoryRows[string](decoder, book.Trash); err != nil {
			return DirectoryPlanningResult{}, err
		}
		if plan.Files, err = decodeDirectoryRows[DirectoryFile](decoder, book.Files); err != nil {
			return DirectoryPlanningResult{}, err
		}
		if plan.Directories, err = decodeDirectoryRows[string](decoder, book.Directories); err != nil {
			return DirectoryPlanningResult{}, err
		}
		if plan.OwnedDirectories, err = decodeDirectoryRows[string](decoder, book.Owned); err != nil {
			return DirectoryPlanningResult{}, err
		}
		if plan.AbsentSources, err = decodeDirectoryRows[string](decoder, book.Absences); err != nil {
			return DirectoryPlanningResult{}, err
		}
		if plan.Documents, err = decodeDirectoryRows[DirectoryDocument](decoder, book.Documents); err != nil {
			return DirectoryPlanningResult{}, err
		}
		result.Inventories = append(result.Inventories, book.Identity)
		result.Plans = append(result.Plans, plan)
	}
	var extra any
	if decoder.Decode(&extra) != io.EOF || ValidateDirectoryPlanning(result) != nil {
		return DirectoryPlanningResult{}, ErrManifest
	}
	return result, nil
}

func (writer *directoryPlanBuffer) Write(data []byte) (int, error) {
	if len(data) > MaxDirectoryPlanBytes-writer.value.Len() {
		return 0, ErrValidation
	}
	return writer.value.Write(data)
}

func encodeDirectoryRows[T any](encoder *json.Encoder, rows []T) error {
	for _, row := range rows {
		if err := encoder.Encode(row); err != nil {
			return err
		}
	}
	return nil
}
