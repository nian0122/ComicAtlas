package domain

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"io"
	"strings"
	"time"
)

func DirectoryConfirmationHash(token string) (string, error) {
	if !digest(token) {
		return "", ErrValidation
	}
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:]), nil
}

func ValidateDirectoryConsent(request DirectoryConfirmRequest) error {
	if !request.Confirm {
		return ErrValidation
	}
	_, err := DirectoryConfirmationHash(request.ConfirmationToken)
	return err
}

func CheckDirectoryConfirmation(record DirectoryPreviewRecord, tokenHash string, now time.Time, allowClaimed bool) error {
	if !TaskID(record.ID) || !digest(tokenHash) || !digest(record.TokenHash) || subtle.ConstantTimeCompare([]byte(tokenHash), []byte(record.TokenHash)) != 1 {
		return ErrConflict
	}
	if record.TaskID != "" {
		if allowClaimed && TaskID(record.TaskID) {
			return nil
		}
		return ErrConflict
	}
	if record.CreatedAt.After(now) || !now.Before(record.ExpiresAt) || record.ExpiresAt.Sub(record.CreatedAt) != DirectoryPreviewTTL {
		return ErrConflict
	}
	return nil
}

func ValidateDirectoryPlanning(value DirectoryPlanningResult) error {
	stamp, err := DirectoryPlanStamp(value.Plans)
	if err != nil || len(value.Inventories) != len(value.Plans) || value.Seal.PlanSHA256 != stamp || !TaskID(value.Seal.TaskID) || !digest(value.Seal.FilesSHA256) || value.Seal.ObservedBytes < 0 || value.Seal.ObservedBytes > 100<<30 {
		return ErrManifest
	}
	ids := make([]string, 0, len(value.Plans))
	count := 0
	for index, plan := range value.Plans {
		identity := value.Inventories[index]
		if identity.ComicID != plan.ComicID || !digest(identity.SHA256) {
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

func (writer *directoryPreviewBuffer) Write(data []byte) (int, error) {
	if len(data) > MaxDirectoryPreviewBytes-writer.value.Len() {
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

func EncodeDirectoryPlanning(value DirectoryPlanningResult) (string, string, error) {
	if err := ValidateDirectoryPlanning(value); err != nil {
		return "", "", err
	}
	writer := &directoryPreviewBuffer{}
	encoder := json.NewEncoder(writer)
	if err := encoder.Encode(directoryPreviewHeader{Version: DirectoryPlanVersion, Books: len(value.Plans), Seal: value.Seal}); err != nil {
		return "", "", err
	}
	if err := encodeDirectoryRows(encoder, value.Measurements); err != nil {
		return "", "", err
	}
	for index, plan := range value.Plans {
		header := directoryPreviewBook{Identity: value.Inventories[index], Name: plan.Name, Files: len(plan.Files), Directories: len(plan.Directories), Owned: len(plan.OwnedDirectories), Absences: len(plan.AbsentSources), Documents: len(plan.Documents), Trash: len(plan.TrashIDs)}
		if err := encoder.Encode(header); err != nil {
			return "", "", err
		}
		if err := encodeDirectoryRows(encoder, plan.TrashIDs); err != nil {
			return "", "", err
		}
		if err := encodeDirectoryRows(encoder, plan.Files); err != nil {
			return "", "", err
		}
		if err := encodeDirectoryRows(encoder, plan.Directories); err != nil {
			return "", "", err
		}
		if err := encodeDirectoryRows(encoder, plan.OwnedDirectories); err != nil {
			return "", "", err
		}
		if err := encodeDirectoryRows(encoder, plan.AbsentSources); err != nil {
			return "", "", err
		}
		if err := encodeDirectoryRows(encoder, plan.Documents); err != nil {
			return "", "", err
		}
	}
	sum := sha256.Sum256(writer.value.Bytes())
	return writer.value.String(), hex.EncodeToString(sum[:]), nil
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

func DecodeDirectoryPlanning(data, stamp string) (DirectoryPlanningResult, error) {
	var result DirectoryPlanningResult
	if len(data) == 0 || len(data) > MaxDirectoryPreviewBytes || !digest(stamp) {
		return result, ErrManifest
	}
	sum := sha256.Sum256([]byte(data))
	if hex.EncodeToString(sum[:]) != stamp {
		return result, ErrManifest
	}
	decoder := json.NewDecoder(strings.NewReader(data))
	decoder.DisallowUnknownFields()
	var header directoryPreviewHeader
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
		var book directoryPreviewBook
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
