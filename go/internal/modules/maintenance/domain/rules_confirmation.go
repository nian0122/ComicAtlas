package domain

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
)

func RequiresConfirmation(operation string) bool {
	return operation == "hq_release" || TrashTargetType(operation) != "" || UsesTrashSelection(operation)
}
func ConfirmationHash(token string) (string, error) {
	data, err := hex.DecodeString(token)
	if err != nil || len(data) != 32 || hex.EncodeToString(data) != token {
		return "", librarydomain.ErrValidation
	}
	digest := sha256.Sum256([]byte(token))
	return hex.EncodeToString(digest[:]), nil
}
func ValidateDangerousConfirmation(operation, expected, supplied string) error {
	if !RequiresConfirmation(operation) {
		if supplied != "" {
			return librarydomain.ErrValidation
		}
		return nil
	}
	if expected == "" || subtle.ConstantTimeCompare([]byte(expected), []byte(supplied)) != 1 {
		return jobsdomain.ErrConflict
	}
	return nil
}
func AddHQSummary(summary *HQSummary, item Item) {
	if item.Status == "pending" && item.HQ != nil {
		summary.Images += item.HQ.Available
		summary.OriginalBytes += item.HQ.OriginalBytes
	}
}
