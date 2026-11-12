package domain

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
)

type StoredMedia struct {
	librarydomain.Media
	StoragePath string `json:"storagePath"`
	Hash        string `json:"hash"`
}

type Manifest struct {
	Progress  *librarydomain.Progress  `json:"progress"`
	ComicInfo *librarydomain.ComicInfo `json:"comicInfo"`
	Version   int                      `json:"version"`
	Comic     librarydomain.Comic      `json:"comic"`
	Catalogs  []librarydomain.Catalog  `json:"catalogs"`
	Chapters  []librarydomain.Chapter  `json:"chapters"`
	Media     []StoredMedia            `json:"media"`
}

type Layout struct {
	Plan    librarydomain.Plan
	Sources map[string]string
}
