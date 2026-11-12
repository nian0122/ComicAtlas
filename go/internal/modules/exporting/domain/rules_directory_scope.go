package domain

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"strings"
)

func ValidateDirectoryScope(value DirectoryAdmissionScope) error {
	if !TaskID(value.PreviewID) || !digest(value.PlanSHA256) || value.Seal.TaskID != value.PreviewID || !digest(value.Seal.PlanSHA256) || !digest(value.Seal.FilesSHA256) || value.Seal.FileCount < 0 || value.Seal.FileCount > MaxDirectoryFiles || value.Seal.ObservedBytes < 0 || value.Seal.ObservedBytes > 100<<30 || len(value.Inventories) == 0 || len(value.Inventories) > MaxDirectoryBooks {
		return ErrManifest
	}
	ids := make([]string, 0, len(value.Inventories))
	for _, identity := range value.Inventories {
		if !digest(identity.SHA256) || identity.MutationVersion < 1 {
			return ErrManifest
		}
		ids = append(ids, identity.ComicID)
	}
	if DirectoryIDs(ids) != nil {
		return ErrManifest
	}
	return nil
}

func ValidateDirectoryVersions(expected []DirectoryInventoryIdentity, current []DirectoryCurrentVersion) error {
	if len(expected) == 0 || len(expected) != len(current) {
		return ErrConflict
	}
	for index, identity := range expected {
		if identity.MutationVersion < 1 || identity.ComicID != current[index].ComicID || identity.MutationVersion != current[index].MutationVersion {
			return ErrConflict
		}
	}
	return nil
}

func (buffer *directoryScopeBuffer) Write(value []byte) (int, error) {
	if len(value) > MaxDirectoryScopeBytes-buffer.value.Len() {
		return 0, ErrValidation
	}
	return buffer.value.Write(value)
}

func EncodeDirectoryScope(value DirectoryAdmissionScope) (string, string, error) {
	if err := ValidateDirectoryScope(value); err != nil {
		return "", "", err
	}
	writer := &directoryScopeBuffer{}
	encoder := json.NewEncoder(writer)
	if err := encoder.Encode(directoryScopeHeader{Version: 1, Books: len(value.Inventories), PreviewID: value.PreviewID, PlanSHA256: value.PlanSHA256, Seal: value.Seal}); err != nil {
		return "", "", err
	}
	if err := encodeDirectoryRows(encoder, value.Inventories); err != nil {
		return "", "", err
	}
	sum := sha256.Sum256(writer.value.Bytes())
	return writer.value.String(), hex.EncodeToString(sum[:]), nil
}

func DecodeDirectoryScope(data, stamp string) (DirectoryAdmissionScope, error) {
	var result DirectoryAdmissionScope
	if len(data) == 0 || len(data) > MaxDirectoryScopeBytes || !digest(stamp) {
		return result, ErrManifest
	}
	sum := sha256.Sum256([]byte(data))
	if hex.EncodeToString(sum[:]) != stamp {
		return result, ErrManifest
	}
	decoder := json.NewDecoder(strings.NewReader(data))
	decoder.DisallowUnknownFields()
	var header directoryScopeHeader
	if decoder.Decode(&header) != nil || header.Version != 1 || header.Books < 1 || header.Books > MaxDirectoryBooks {
		return result, ErrManifest
	}
	rows, err := decodeDirectoryRows[DirectoryInventoryIdentity](decoder, header.Books)
	if err != nil {
		return result, err
	}
	var extra any
	if decoder.Decode(&extra) != io.EOF {
		return result, ErrManifest
	}
	result = DirectoryAdmissionScope{PreviewID: header.PreviewID, PlanSHA256: header.PlanSHA256, Seal: header.Seal, Inventories: rows}
	return result, ValidateDirectoryScope(result)
}
