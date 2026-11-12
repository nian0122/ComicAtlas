package application

import (
	exportports "comicatlas.local/backend/internal/modules/exporting/application/ports"
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"context"
)

type DirectoryPlanner struct {
	repository exportports.DirectoryInventoryRepository
	files      exportports.DirectoryPlanningFiles
}

func NewDirectoryPlanner(repository exportports.DirectoryInventoryRepository, files exportports.DirectoryPlanningFiles) *DirectoryPlanner {
	return &DirectoryPlanner{repository: repository, files: files}
}

// PlanDirectory 只读规划，不创建任务或检查点；确认、受理和执行仍须持久互斥及重验。
func (planner *DirectoryPlanner) PlanDirectory(ctx context.Context, inspectionID string, ids []string) (exportdomain.DirectoryPlanningResult, error) {
	var result exportdomain.DirectoryPlanningResult
	if !exportdomain.TaskID(inspectionID) {
		return result, exportdomain.ErrValidation
	}
	if err := exportdomain.DirectoryIDs(ids); err != nil {
		return result, err
	}
	if err := ctx.Err(); err != nil {
		return result, err
	}
	snapshots, err := planner.repository.DirectoryInventories(ctx, ids)
	if err != nil {
		return result, err
	}
	if len(snapshots) != len(ids) {
		return result, exportdomain.ErrManifest
	}
	for index, id := range ids {
		if snapshots[index].Inventory.Comic.ID != id {
			return result, exportdomain.ErrManifest
		}
	}
	result.Inventories, err = exportdomain.DirectoryInventoryIdentities(snapshots)
	if err != nil {
		return result, err
	}
	resolved, err := planner.files.ResolveDirectoryTrash(ctx, snapshots)
	if err != nil {
		return result, err
	}
	result.Plans, err = exportdomain.BuildDirectoryPlans(snapshots, resolved)
	if err != nil {
		return result, err
	}
	if err = ctx.Err(); err != nil {
		return result, err
	}
	inspection, err := planner.files.InspectDirectory(ctx, inspectionID, result.Plans)
	if err != nil {
		return result, err
	}
	result.Measurements, result.Seal, err = exportdomain.FreezeDirectoryMeasurement(inspectionID, result.Plans, inspection)
	return result, err
}
