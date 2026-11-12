package domain

import "bytes"

const MaxDirectoryScopeBytes = 4 << 20

type DirectoryAdmissionScope struct {
	PreviewID, PlanSHA256 string
	Seal                  DirectorySeal
	Inventories           []DirectoryInventoryIdentity
}

type DirectoryCurrentVersion struct {
	ComicID         string
	MutationVersion int64
}

type directoryScopeHeader struct {
	Version, Books        int
	PreviewID, PlanSHA256 string
	Seal                  DirectorySeal
}
type directoryScopeBuffer struct{ value bytes.Buffer }
