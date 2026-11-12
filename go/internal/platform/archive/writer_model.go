package archive

import (
	"context"
	"io"
)

type WriteEntry struct {
	Directory bool
	Name      string
	Size      int64
	SHA256    string
	Open      func(context.Context) (io.ReadCloser, error)
}

type WriteOptions struct {
	Name      string
	PartBytes int64
}

type Artifact struct {
	VerifiedInfo *WriteReceipt `json:"-"`
	FileName     string        `json:"fileName"`
	Size         int64         `json:"size"`
	SHA256       string        `json:"sha256"`
	LastSegment  bool          `json:"lastSegment"`
}
