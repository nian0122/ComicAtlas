package domain

import "io"

type Inspection struct {
	MediaType   string `json:"mediaType"`
	ContentType string `json:"contentType"`
	Width       int    `json:"width"`
	Height      int    `json:"height"`
	Video       *Video `json:"video"`
}

type inspectionReader struct {
	reader io.Reader
	err    error
}
