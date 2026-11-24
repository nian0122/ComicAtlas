package domain

import "time"

type DirectoryCurrentVersion struct {
	ComicID         string
	MutationVersion int64
}

type DirectoryMoveCreateRequest struct {
	ComicIDs []string `json:"comicIds"`
}

type DirectoryMoveRequest struct {
	ID        string
	ComicIDs  []string
	CreatedAt time.Time
}

type DirectoryMovePlan struct {
	ID            string
	Data          string
	CreatedAt     time.Time
	BookCount     int
	FileCount     int
	ObservedBytes int64
}
