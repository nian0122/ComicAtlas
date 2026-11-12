package domain

import librarydomain "comicatlas.local/backend/internal/modules/library/domain"

const MaxCandidates = 100000
const MaxRootEntries = 200000
const MaxSidecarBytes = 2 * librarydomain.MaxSnapshotBytes

type Sidecar struct {
	Version                                      int
	Producer, TaskID, ObservedAt, BaselineSHA256 string
	SyncRevision                                 int64
	HasObservations                              bool
	ObservationCount                             int
	Snapshot                                     librarydomain.Snapshot
}

type Document struct {
	Sidecar      Sidecar
	Path, SHA256 string
	Size         int64
}

type Discovery struct{ Candidates, Ignored int }

type HQPresence struct {
	Media       int `json:"media"`
	Other       int `json:"other"`
	Directories int `json:"directories"`
}
