// Package request 声明 HTTP 写入接口的 JSON 请求 DTO。
package request

import (
	"encoding/json"

	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	readingdomain "comicatlas.local/backend/internal/modules/reading/domain"
)

type ReadingProgress struct {
	ChapterID  string `json:"chapterId"`
	PageNumber int    `json:"pageNumber"`
	MediaID    string `json:"mediaId"`
}

type Empty struct{}

type Import struct {
	SourcePath string `json:"sourcePath"`
	Title      string `json:"title"`
}

type MetadataBatch struct {
	Items []librarydomain.MetadataChange `json:"items"`
}

type TermCreate struct {
	Name string `json:"name"`
}

type TermUpdate struct {
	Name    string `json:"name"`
	Version int    `json:"version"`
}

type ReactionUpdate struct {
	Value string `json:"value"`
}

type ReactionBatch struct {
	Items []readingdomain.ReactionChange `json:"items"`
}

type MediaOrder struct {
	Version int      `json:"version"`
	IDs     []string `json:"ids"`
}

type StructureOrder struct {
	Version  int                     `json:"version"`
	ParentID *string                 `json:"parentId"`
	Items    []librarydomain.NodeRef `json:"items"`
}

type StructureMove struct {
	Version  int                     `json:"version"`
	ParentID json.RawMessage         `json:"parentId"`
	Items    []librarydomain.NodeRef `json:"items"`
}

type CatalogDelete struct {
	Version    int             `json:"version"`
	ReparentTo json.RawMessage `json:"reparentTo"`
}
