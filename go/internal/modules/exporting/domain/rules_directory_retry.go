package domain

// 重试受理只确认原任务仍持有未完成范围；完整计划和外部效果由执行器复核。
func ValidateDirectoryRetry(id string, state DirectoryRetryState) error {
	if !TaskID(id) {
		return ErrValidation
	}
	if state.TaskID != id || state.Type != "directory_move" || state.Status != "failed" || state.ComicID != "" || state.TargetType != "directory_preview" || state.Payload != "{}" || !TaskID(state.TargetID) {
		return ErrConflict
	}
	if !state.HasPlan {
		if state.Completed || state.Members != 0 || state.DetachedMembers != 0 {
			return ErrConflict
		}
		return nil
	}
	if state.PlanID != state.TargetID || state.Completed || state.Members < 1 || state.Members > MaxDirectoryBooks || state.DetachedMembers != 0 {
		return ErrConflict
	}
	return nil
}
