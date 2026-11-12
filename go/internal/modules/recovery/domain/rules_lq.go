package domain

import mediadomain "comicatlas.local/backend/internal/modules/media/domain"

// RestoredLQRegistration 保留外来导入来源；其他产物登记到实际恢复任务，区别于编码任务。
func RestoredLQRegistration(record mediadomain.LQRecord, task, reference string) (mediadomain.LQRecord, error) {
	if !Key(task) || !validHash(reference) || mediadomain.ValidateLQProvenance(record.Provenance, record.TaskID) != nil {
		return mediadomain.LQRecord{}, ErrMetadata
	}
	if record.Provenance != nil && record.Provenance.Origin == "imported" {
		return record, nil
	}
	record.TaskID = task
	originalState := ""
	if record.Provenance != nil {
		originalState = record.Provenance.OriginalState
	}
	record.Provenance = &mediadomain.LQProvenance{Origin: "recovered", ReferenceSHA256: reference, OriginalState: originalState}
	return record, nil
}
