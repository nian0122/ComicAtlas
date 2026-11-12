package application

import (
	ingestionports "comicatlas.local/backend/internal/modules/ingestion/application/ports"
	ingestiondomain "comicatlas.local/backend/internal/modules/ingestion/domain"
	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"sort"
	"time"
)

type ScanService struct {
	repository ingestionports.ScanRepository
	source     ingestionports.ScanSource
	jobs       *jobsapp.Store
}

func NewScans(repository ingestionports.ScanRepository, source ingestionports.ScanSource, jobs *jobsapp.Store) *ScanService {
	return &ScanService{repository: repository, source: source, jobs: jobs}
}
func (service *ScanService) Submit(ctx context.Context, root, key string) (jobsdomain.Task, error) {
	root, err := service.source.SourcePath(root, true)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	data, _ := json.Marshal(struct {
		SourcePath string `json:"sourcePath"`
	}{root})
	sum := sha256.Sum256([]byte(root))
	return service.jobs.CreateResource(ctx, jobsdomain.ResourceTask{Type: "directory_scan", TargetType: "import_root", TargetID: hex.EncodeToString(sum[:]), Title: "导入候选扫描", Payload: string(data)}, key, service.repository.Admission(root))
}
func (service *ScanService) Run(ctx context.Context, task jobsdomain.Task) (completion jobsports.Completion, resultErr error) {
	deferCtx, cancel := context.WithTimeout(ctx, 15*time.Minute)
	defer cancel()
	defer func() {
		if resultErr != nil && ctx.Err() == nil && errors.Is(deferCtx.Err(), context.DeadlineExceeded) {
			resultErr = jobsdomain.NewFailure(jobsdomain.FailureScanTimeout, resultErr)
		}
	}()
	var request struct {
		SourcePath string `json:"sourcePath"`
	}
	if task.RequestJSON == nil || json.Unmarshal([]byte(*task.RequestJSON), &request) != nil {
		return jobsports.Completion{}, jobsdomain.NewFailure(jobsdomain.FailureInternal, nil)
	}
	if err := service.repository.Begin(deferCtx, task.ID); err != nil {
		return jobsports.Completion{}, err
	}
	targets, err := service.source.Candidates(deferCtx, request.SourcePath)
	if err != nil {
		return jobsports.Completion{}, taskFailure(err)
	}
	if err = service.jobs.UpdateProgress(deferCtx, task.ID, "scanning", len(targets), 0); err != nil {
		return jobsports.Completion{}, err
	}
	totalBytes := 0
	for index, target := range targets {
		candidate := ingestiondomain.Candidate{ID: target.ID, SourcePath: target.SourcePath, Kind: target.Kind, Title: target.Name, Selectable: target.Kind == "directory"}
		data, err := json.Marshal(candidate)
		if err != nil {
			return jobsports.Completion{}, err
		}
		totalBytes += len(data)
		if totalBytes > ingestiondomain.MaxScanResultBytes {
			return jobsports.Completion{}, ingestiondomain.ErrLimit
		}
		if err = service.repository.Save(deferCtx, task.ID, index+1, candidate); err != nil {
			return jobsports.Completion{}, err
		}
		if err = service.jobs.UpdateProgress(deferCtx, task.ID, "scanning", len(targets), index+1); err != nil {
			return jobsports.Completion{}, err
		}
	}
	return jobsports.Completion{Persist: service.repository.Completion(task.ID)}, nil
}
func (service *ScanService) Results(ctx context.Context, id string, page, size int) (ingestiondomain.CandidatePage, error) {
	if page < 1 || page > 1000000 || size < 1 || size > 100 {
		return ingestiondomain.CandidatePage{}, jobsdomain.ErrValidation
	}
	result, err := service.repository.List(ctx, id, page, size)
	if err != nil {
		return result, err
	}
	for _, item := range result.Items {
		if _, err = service.source.SourcePath(item.SourcePath, false); err != nil {
			return ingestiondomain.CandidatePage{}, jobsdomain.ErrConflict
		}
	}
	return result, nil
}
func (service *ScanService) Import(ctx context.Context, id string, ids []string, key string) (jobsdomain.TaskBatch, error) {
	if len(ids) < 1 || len(ids) > jobsdomain.MaxImportBatchItems {
		return jobsdomain.TaskBatch{}, jobsdomain.ErrValidation
	}
	ids = append([]string(nil), ids...)
	sort.Strings(ids)
	items, err := service.repository.Selection(ctx, id, ids)
	if err != nil {
		return jobsdomain.TaskBatch{}, err
	}
	if err = ingestiondomain.ValidateSelection(items); err != nil {
		return jobsdomain.TaskBatch{}, err
	}
	inputs := make([]jobsdomain.ImportInput, 0, len(items))
	for _, item := range items {
		kind := "directory_import"
		relative, sourceErr := service.source.SourcePath(item.SourcePath, false)
		if sourceErr != nil {
			return jobsdomain.TaskBatch{}, sourceErr
		}
		inputs = append(inputs, jobsdomain.ImportInput{Type: kind, SourcePath: relative, ReferenceID: item.ID})
	}
	return service.jobs.CreateImportBatch(ctx, inputs, id, key, service.repository.SelectionAdmission(id, items))
}

func (service *ScanService) ImportArchives(ctx context.Context, paths []string, key string) (jobsdomain.TaskBatch, error) {
	if len(paths) < 1 || len(paths) > jobsdomain.MaxImportBatchItems {
		return jobsdomain.TaskBatch{}, jobsdomain.ErrValidation
	}
	paths = append([]string(nil), paths...)
	sort.Strings(paths)
	inputs := make([]jobsdomain.ImportInput, 0, len(paths))
	for index, name := range paths {
		if index > 0 && name == paths[index-1] {
			return jobsdomain.TaskBatch{}, jobsdomain.ErrValidation
		}
		relative, sourceErr := service.source.SourcePath(name, false)
		if sourceErr != nil {
			return jobsdomain.TaskBatch{}, sourceErr
		}
		inputs = append(inputs, jobsdomain.ImportInput{Type: "archive_import", SourcePath: relative, ReferenceID: ingestiondomain.CandidateID(relative)})
	}
	return service.jobs.CreateImportBatch(ctx, inputs, "archive_paths", key, nil)
}
