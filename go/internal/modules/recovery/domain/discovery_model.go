package domain

const DiscoveryBatchSize = 128

type DiscoveryRun struct {
	TaskID, TaskStatus           string
	Sealed                       bool
	CandidateCount, IgnoredCount int
	Attempt                      int
}
