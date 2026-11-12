package domain

func MissingMetadataKind(presence HQPresence) string {
	if presence.Media == 0 && presence.Other == 0 {
		return CandidateEmpty
	}
	return CandidateNeedsMetadata
}
