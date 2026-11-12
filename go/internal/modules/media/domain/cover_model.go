package domain

type CoverSelection struct {
	ComicID string `json:"comicId"`
	MediaID string `json:"mediaId"`
	Version int    `json:"version"`
}
