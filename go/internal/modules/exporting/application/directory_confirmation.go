package application

import (
	exportports "comicatlas.local/backend/internal/modules/exporting/application/ports"
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	"context"
	"time"
)

type DirectoryConfirmation struct {
	repository exportports.DirectoryConfirmationRepository
	jobs       *jobsapp.Store
}

func NewDirectoryConfirmation(repository exportports.DirectoryConfirmationRepository, jobs *jobsapp.Store) *DirectoryConfirmation {
	return &DirectoryConfirmation{repository: repository, jobs: jobs}
}

func (service *DirectoryConfirmation) Submit(ctx context.Context, id, token, key string) (jobsdomain.Task, error) {
	if !exportdomain.TaskID(id) || key == "" || len(key) > 128 {
		return jobsdomain.Task{}, exportdomain.ErrValidation
	}
	hash, err := exportdomain.DirectoryConfirmationHash(token)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	state, err := service.repository.DirectoryConfirmationState(ctx, id)
	if err != nil {
		return jobsdomain.Task{}, err
	}
	if err = exportdomain.CheckDirectoryConfirmation(state, hash, time.Now().UTC(), true); err != nil {
		return jobsdomain.Task{}, err
	}
	var scope exportdomain.DirectoryAdmissionScope
	if state.TaskID == "" {
		scope, err = service.repository.DirectoryAdmissionScope(ctx, id)
		if err != nil {
			return jobsdomain.Task{}, err
		}
		if scope.PreviewID != id || scope.PlanSHA256 != state.SHA256 {
			return jobsdomain.Task{}, exportdomain.ErrManifest
		}
	}
	// 只消费有界身份与版本，不读取大计划、文件内容或等待执行中的文件互斥。
	return service.jobs.CreateResource(ctx, jobsdomain.ResourceTask{Type: "directory_move", TargetType: "directory_preview", TargetID: id, Title: "文件夹移出", Payload: "{}"}, key, service.repository.DirectoryAdmission(state, scope, hash, time.Now().UTC()))
}
