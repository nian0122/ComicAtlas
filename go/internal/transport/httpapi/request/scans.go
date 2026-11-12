package request

type DirectoryScan struct {
	SourcePath string `json:"sourcePath"`
}

type DirectoryScanImport struct {
	CandidateIDs []string `json:"candidateIds"`
}

type ArchiveBatchImport struct {
	SourcePaths []string `json:"sourcePaths"`
}
