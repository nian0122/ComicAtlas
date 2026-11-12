package domain

func ValidManagementQuery(query ManagementQuery) bool {
	if !ValidPage(query.Page, query.PageSize) || query.TargetType != "" && !ValidTarget(query.TargetType) {
		return false
	}
	if query.Value != "" && query.Value != ReactionLike && query.Value != ReactionDislike {
		return false
	}
	if query.MediaType != "" && (query.TargetType != "media" || query.MediaType != "image" && query.MediaType != "video") {
		return false
	}
	return true
}

func ValidReactionChanges(changes []ReactionChange) bool {
	if len(changes) == 0 || len(changes) > 100 {
		return false
	}
	seen := map[string]bool{}
	for _, change := range changes {
		key := change.TargetType + "\x00" + change.TargetID
		if !ValidTarget(change.TargetType) || change.TargetID == "" || len(change.TargetID) > 128 || seen[key] || change.Value != ReactionLike && change.Value != ReactionDislike && change.Value != ReactionNone {
			return false
		}
		seen[key] = true
	}
	return true
}

func ReactionHidden(scope ReactionScope) bool {
	return scope.Target != "active" || scope.Comic != "active" || scope.Chapter != "" && scope.Chapter != "active"
}

func CanChangeManagedReaction(scope ReactionScope) bool {
	stable := func(state string) bool { return state == "active" || state == "trashed" }
	if !stable(scope.Target) || !stable(scope.Comic) || scope.Chapter != "" && !stable(scope.Chapter) {
		return false
	}
	return true
}

func ProjectManagedReaction(item ManagedReaction, scope ReactionScope) ManagedReaction {
	item.LifecycleState = scope.Target
	item.Hidden = ReactionHidden(scope)
	item.CanChange = CanChangeManagedReaction(scope)
	if item.Hidden {
		item.PageNumber, item.PreviewURL, item.ContentURL = nil, nil, nil
		return item
	}
	preview := "/api/v1/comics/" + item.ComicID + "/cover"
	if item.TargetType == "media" {
		content := "/api/v1/media/" + item.TargetID + "/content"
		item.ContentURL = &content
		if item.MediaType != nil && *item.MediaType == "image" {
			preview = "/api/v1/media/" + item.TargetID + "/content?quality=auto"
		}
	}
	item.PreviewURL = &preview
	return item
}
