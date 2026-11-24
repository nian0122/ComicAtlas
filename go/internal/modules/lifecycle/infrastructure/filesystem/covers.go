package filesystem

import (
	lifecycledomain "comicatlas.local/backend/internal/modules/lifecycle/domain"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"
)

type CoverReader struct {
	files *Files
}

func NewCoverReader(files *Files) *CoverReader {
	return &CoverReader{files: files}
}

// ReadCover 直接读取导入时生成的封面文件；校验收件身份和文件属性，不重新渲染。
func (reader *CoverReader) ReadCover(ctx context.Context, source lifecycledomain.CoverSource) (lifecycledomain.Cover, error) {
	if err := lifecycledomain.ValidateCoverSource(source); err != nil {
		return lifecycledomain.Cover{}, err
	}
	files := reader.files
	files.mutex.Lock()
	defer files.mutex.Unlock()
	journal, err := files.load(source.EntryID)
	if errors.Is(err, os.ErrNotExist) {
		return lifecycledomain.Cover{}, lifecycledomain.ErrNotFound
	}
	if err != nil || journal.Phase != "trashed" {
		return lifecycledomain.Cover{}, lifecycledomain.ErrChanged
	}
	items, err := lifecycledomain.CoverFiles(source, journal)
	if err != nil {
		return lifecycledomain.Cover{}, lifecycledomain.ErrChanged
	}
	moves := make(map[string]lifecycledomain.MoveUnit, len(journal.Moves))
	for _, move := range journal.Moves {
		moves[move.Path] = move
	}
	last := error(lifecycledomain.ErrNotFound)
	for index, candidate := range source.Candidates {
		if err = ctx.Err(); err != nil {
			return lifecycledomain.Cover{}, err
		}
		item := items[index]
		if item.Missing {
			continue
		}
		name := lifecycledomain.RetainedPath(source.EntryID, candidate.Path)
		info, err := files.stat(name)
		if errors.Is(err, os.ErrNotExist) {
			continue
		}
		move, owned := lifecycledomain.MoveForPath(moves, candidate.Path)
		if err != nil || !owned || info.Size() != item.Size || !move.Directory && info.ModTime().UnixNano() != move.Modified {
			last = lifecycledomain.ErrChanged
			continue
		}
		input, err := files.root.Open(filepath.FromSlash(name))
		if err != nil {
			last = err
			continue
		}
		data, readErr := io.ReadAll(io.LimitReader(input, lifecycledomain.MaxCoverBytes+1))
		closeErr := input.Close()
		if readErr != nil || closeErr != nil || int64(len(data)) != info.Size() || len(data) < 1 || len(data) > lifecycledomain.MaxCoverBytes {
			last = lifecycledomain.ErrChanged
			continue
		}
		etag := fmt.Sprintf("%d-%x-%x", index, info.ModTime().UnixNano(), info.Size())
		return lifecycledomain.Cover{ETag: etag, ContentType: coverContentType(candidate.Path, data), Data: data}, nil
	}
	return lifecycledomain.Cover{}, last
}

func coverContentType(name string, data []byte) string {
	switch strings.ToLower(path.Ext(name)) {
	case ".webp":
		return "image/webp"
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".png":
		return "image/png"
	default:
		return http.DetectContentType(data)
	}
}
