package domain

const MaxSelectedEntries = 10000

type EntryFilter struct {
	Keyword    string `json:"keyword,omitempty"`
	TargetType string `json:"targetType,omitempty"`
}
type EntrySelection struct {
	Mode        string       `json:"mode"`
	IDs         []string     `json:"ids,omitempty"`
	Filter      *EntryFilter `json:"filter,omitempty"`
	ExcludedIDs []string     `json:"excludedIds,omitempty"`
}
type SelectedEntry struct {
	Entry       Entry
	Stamp       string
	AncestorIDs []string
	Blocker     string
}
