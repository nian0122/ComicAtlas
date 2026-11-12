package domain

type NodePreview struct {
	NodeRef
	MediaCount int            `json:"mediaCount"`
	Images     []PreviewImage `json:"images"`
}

type PreviewImage struct {
	MediaID    string `json:"mediaId"`
	PreviewURL string `json:"previewUrl"`
}
