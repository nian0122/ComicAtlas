package domain

import "time"

func PreparePlaceholder(comic, task, recordedAt string, presence HQPresence) (PlaceholderPlan, error) {
	if !Key(comic) || !Key(task) || len(recordedAt) > 40 {
		return PlaceholderPlan{}, ErrValidation
	}
	if _, err := time.Parse(time.RFC3339Nano, recordedAt); err != nil {
		return PlaceholderPlan{}, ErrValidation
	}
	if presence.Media < 0 || presence.Other < 0 || presence.Directories < 0 || presence.Media > MaxRootEntries || presence.Other > MaxRootEntries || presence.Directories > MaxRootEntries || presence.Media+presence.Other+presence.Directories > MaxRootEntries || MissingMetadataKind(presence) != CandidateNeedsMetadata {
		return PlaceholderPlan{}, ErrValidation
	}
	return PlaceholderPlan{ComicID: comic, TaskID: task, Title: "待补全 · " + comic, CreatedAt: recordedAt, Presence: presence}, nil
}
