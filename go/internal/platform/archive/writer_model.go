package archive

import (
	"context"
	"io"
)

type WriteEntry struct {
	Directory bool
	Name      string
	Size      int64
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
	Modified     int64         `json:"modified"`
	LastSegment  bool          `json:"lastSegment"`
}
