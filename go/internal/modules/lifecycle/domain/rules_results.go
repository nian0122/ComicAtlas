package domain

import (
	"strings"
	"time"
)

func validFileOperation(operation string) bool {
	return operation == "trash" || operation == "restore"
}

func ValidateFileReport(attempt int, operation string, total int) error {
	if !validFileOperation(operation) || total < 0 || total > MaxFiles || attempt < 1 {
		return ErrValidation
	}
	return nil
}

func ValidFileResultBatchSize(size int) bool { return size > 0 && size <= MaxFileResultBatch }

func ValidFileResult(item FileResult, operation string, total int) bool {
	if !validFileOperation(operation) || item.Operation != operation || !ValidJournalID(item.EntryID) || item.Position < 1 || item.Position > total || len(item.FileName) == 0 || len(item.FileName) > 2048 || strings.ContainsAny(item.FileName, "/\\\x00") {
		return false
	}
	if item.Role != "hq" && item.Role != "lq" && item.Role != "metadata" && item.Role != "thumbs" && item.Role != "retired" {
		return false
	}
	if item.Phase != "preparing" && item.Phase != "applying" && item.Phase != "verifying" {
		return false
	}
	if _, err := time.Parse(time.RFC3339Nano, item.ObservedAt); err != nil {
		return false
	}
	if item.State == "applied" || item.State == "initially_missing" {
		return item.ErrorCode == "" && item.Phase != "preparing"
	}
	if item.State != "failed" {
		return false
	}
	switch item.ErrorCode {
	case "FILE_CHANGED", "FILE_MISSING", "FILE_LOCATION_CONFLICT", "FILE_ACCESS_DENIED", "FILE_IO_FAILED":
		return true
	default:
		return false
	}
}
