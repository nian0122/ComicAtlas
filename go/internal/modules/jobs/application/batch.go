package application

import (
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	"context"
	"crypto/sha256"
	"encoding/json"
	"fmt"
)

func (store *Store) CreateImportBatch(ctx context.Context, inputs []jobsdomain.ImportInput, identity, key string, admission jobsports.BatchAdmission) (jobsdomain.TaskBatch, error) {
	if len(inputs) < 1 || len(inputs) > jobsdomain.MaxImportBatchItems || len(key) > 128 {
		return jobsdomain.TaskBatch{}, jobsdomain.ErrValidation
	}
	prepared := make([]jobsdomain.PreparedImport, 0, len(inputs))
	for _, input := range inputs {
		if input.ReferenceID == "" || len(input.ReferenceID) > 128 {
			return jobsdomain.TaskBatch{}, jobsdomain.ErrValidation
		}
		value, err := jobsdomain.PrepareImport(input, "")
		if err != nil {
			return jobsdomain.TaskBatch{}, err
		}
		prepared = append(prepared, value)
	}
	data, err := json.Marshal(prepared)
	if err != nil {
		return jobsdomain.TaskBatch{}, err
	}
	hash := fmt.Sprintf("%x", sha256.Sum256(append([]byte(identity+"\x00"), data...)))
	return store.repository.CreateImportBatch(ctx, prepared, key, hash, admission)
}

func (store *Store) ImportBatch(ctx context.Context, id string) (jobsdomain.TaskBatch, error) {
	return store.repository.GetImportBatch(ctx, id)
}

// CreatePreparedImportTask creates one frozen child import and runs admission in the same write transaction.
func (store *Store) CreatePreparedImportTask(ctx context.Context, input jobsdomain.PreparedImport, key, batchID string, position int, admission jobsports.Admission) (jobsdomain.Task, error) {
	if admission == nil || input.ReferenceID == "" || len(input.ReferenceID) > 128 ||
		(input.Resource.Type != "directory_import" && input.Resource.Type != "archive_import") || input.RequestHash == "" || batchID == "" || position < 1 || position > jobsdomain.MaxImportBatchItems {
		return jobsdomain.Task{}, jobsdomain.ErrValidation
	}
	input.Resource.ReservationBatchID, input.Resource.ReservationPosition = batchID, position
	return store.repository.CreateResourceTask(ctx, input.Resource, key, input.RequestHash, admission)
}

func (store *Store) FinishCoordinatorCancellation(ctx context.Context, id string) error {
	_, err := store.repository.Finish(ctx, id, jobsdomain.TaskStatusFailed, jobsdomain.FailureInterrupted, nil)
	return err
}
