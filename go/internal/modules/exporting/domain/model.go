package domain

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"encoding/xml"
)

type Request = librarydomain.ArchiveRequest
type Source = librarydomain.ArchiveSource
type Candidate struct {
	Source    Source
	HQState   string
	LQPath    string
	LQSize    int64
	LQCurrent bool
}
type Snapshot = librarydomain.ArchiveMetadata
type Artifact struct {
	FileName    string `json:"fileName"`
	Size        int64  `json:"size"`
	Modified    int64  `json:"modified"`
	LastSegment bool   `json:"lastSegment"`
	Path        string `json:"path,omitempty"`
}
type Manifest struct {
	Version   int        `json:"version"`
	TaskID    string     `json:"taskId"`
	Artifacts []Artifact `json:"artifacts"`
}
type ArtifactView struct {
	TaskID    string     `json:"taskId"`
	Status    string     `json:"status"`
	Published bool       `json:"published"`
	Directory *string    `json:"directory"`
	Items     []Artifact `json:"items"`
}
type Entry struct {
	Name      string
	Directory bool
	Source    *Source
	Data      []byte
}

type treeNode struct {
	id, parent, title string
	order             int
	chapter           bool
}
type comicXML struct {
	XMLName   xml.Name `xml:"ComicInfo"`
	Title     string   `xml:"Title"`
	Series    string   `xml:"Series"`
	Number    string   `xml:"Number,omitempty"`
	Writer    string   `xml:"Writer"`
	Summary   string   `xml:"Summary"`
	Genre     string   `xml:"Genre,omitempty"`
	Tags      string   `xml:"Tags,omitempty"`
	PageCount int      `xml:"PageCount"`
}
