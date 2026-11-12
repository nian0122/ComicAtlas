package domain

import (
	"bytes"
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
)

type DirectoryTrashResolution struct {
	ComicID  string
	EntryID  string
	Manifest DirectoryStoredFile
	Journal  lifecycledomain.FileJournal
}

type DirectoryPlacement struct {
	MediaID        string `json:"mediaId,omitempty"`
	Role           string `json:"role"`
	EntryID        string `json:"entryId,omitempty"`
	OriginalSource string `json:"originalSource"`
	PhysicalSource string `json:"physicalSource"`
	Target         string `json:"target"`
	Availability   string `json:"availability"`
}

type DirectoryExportDocument struct {
	Version         int                  `json:"version"`
	Kind            string               `json:"kind"`
	InventorySHA256 string               `json:"inventorySha256"`
	Inventory       DirectoryInventory   `json:"inventory"`
	Files           []DirectoryPlacement `json:"files"`
}

type DirectoryInventoryIdentity struct {
	ComicID         string
	SHA256          string
	MutationVersion int64
}

type DirectoryPlanningResult struct {
	Inventories  []DirectoryInventoryIdentity
	Plans        []DirectoryPlan
	Measurements []DirectoryFileState
	Seal         DirectorySeal
}

// directoryBinding 是纯装配过程的内部状态，不进入持久协议。
type directoryBinding struct {
	DirectoryPlacement
	file    DirectoryStoredFile
	hidden  bool
	chapter string
}

type directoryDocumentBuffer struct{ value bytes.Buffer }
