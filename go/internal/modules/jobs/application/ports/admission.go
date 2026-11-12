package ports

import "comicatlas.local/backend/internal/platform/persistence"

func CombineAdmissions(first, second Admission) Admission {
	if first == nil && second == nil {
		return nil
	}
	return func(task string) persistence.WriteAction {
		var actions []persistence.WriteAction
		if first != nil {
			actions = append(actions, first(task))
		}
		if second != nil {
			actions = append(actions, second(task))
		}
		return persistence.Sequence(actions...)
	}
}
