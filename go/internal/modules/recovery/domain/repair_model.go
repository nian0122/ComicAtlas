package domain

type RepairRequest struct {
	Version          int `json:"version"`
	StructureVersion int `json:"structureVersion"`
}

type RepairRun struct {
	TaskID                   string  `json:"taskId"`
	ComicID                  string  `json:"comicId"`
	ExpectedVersion          int     `json:"expectedVersion"`
	ExpectedStructureVersion int     `json:"expectedStructureVersion"`
	MarkerCreatedAt          string  `json:"-"`
	MarkerTaskID             *string `json:"-"`
	Completed                bool    `json:"completed"`
	SourceSHA                *string `json:"-"`
	CompletedAt              *string `json:"completedAt"`
	Version                  *int    `json:"version"`
	StructureVersion         *int    `json:"structureVersion"`
	PreservedMetadata        *bool   `json:"preservedMetadata"`
}

type RepairFacts struct {
	Version, StructureVersion int
	MarkerCreatedAt           string
	MarkerTaskID              *string
	HasStructure, Busy        bool
}

type RepairPolicy struct {
	Version, StructureVersion int
	PreserveMetadata          bool
}
