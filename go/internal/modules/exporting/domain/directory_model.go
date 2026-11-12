package domain

const MaxDirectoryFiles = 100000
const MaxDirectoryBooks = 10000
const MaxDirectoryPlanBytes = 32 << 20
const MaxDirectoryAbsences = 2 * MaxDirectoryFiles

// DirectoryPlan 的来源可分散在原件、上传、转码及派生目录；目标均相对 Name。
type DirectoryPlan struct {
	ComicID          string              `json:"comicId"`
	Name             string              `json:"name"`
	Directories      []string            `json:"directories"`
	OwnedDirectories []string            `json:"ownedDirectories"`
	TrashIDs         []string            `json:"trashIds"`
	AbsentSources    []string            `json:"absentSources"`
	Files            []DirectoryFile     `json:"files"`
	Documents        []DirectoryDocument `json:"documents"`
}

type DirectoryFile struct {
	Source   string `json:"source"`
	Target   string `json:"target"`
	Size     int64  `json:"size"`
	SHA256   string `json:"sha256"`
	Optional bool   `json:"optional"`
}

type DirectoryDocument struct {
	Name string `json:"name"`
	Data []byte `json:"data"`
}

type DirectoryObservation struct {
	Identity string
	Modified int64
	ComicID  string
	Source   string
	Target   string
	Size     int64
	SHA256   string
	Missing  bool
}

type DirectoryInspection struct {
	PlanSHA256    string
	Files         []DirectoryObservation
	ObservedBytes int64
}
