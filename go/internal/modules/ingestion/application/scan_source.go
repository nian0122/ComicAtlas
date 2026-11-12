package application

import (
	ingestiondomain "comicatlas.local/backend/internal/modules/ingestion/domain"
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
	"strings"
)

func (directory *Directory) inputPathSafe(name string) error {
	prefix := ""
	for _, part := range strings.Split(name, "/") {
		if prefix != "" {
			prefix += "/"
		}
		prefix += part
		info, err := directory.input.Lstat(prefix)
		if err != nil {
			return sourceFailure(err)
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return ingestiondomain.ErrUnsafeSource
		}
	}
	return nil
}
func (directory *Directory) checkedInputRoot(name string) (*os.Root, error) {
	if err := directory.inputPathSafe(name); err != nil {
		return nil, err
	}
	root, err := directory.input.OpenRoot(name)
	return root, sourceFailure(err)
}
func (directory *Directory) Candidates(ctx context.Context, parent string) ([]ingestiondomain.ScanTarget, error) {
	root, err := directory.checkedInputRoot(parent)
	if err != nil {
		return nil, err
	}
	defer root.Close()
	file, err := root.Open(".")
	if err != nil {
		return nil, sourceFailure(err)
	}
	defer file.Close()
	targets := []ingestiondomain.ScanTarget{}
	entriesCount := 0
	for {
		entries, readErr := file.ReadDir(128)
		if readErr != nil && !errors.Is(readErr, io.EOF) {
			return nil, sourceFailure(readErr)
		}
		for _, entry := range entries {
			if err = ctx.Err(); err != nil {
				return nil, err
			}
			entriesCount++
			if entriesCount > MaxEntries {
				return nil, ingestiondomain.ErrLimit
			}
			kind := ""
			if entry.Type()&os.ModeSymlink != 0 {
				kind = "unsupported"
			} else if entry.IsDir() {
				kind = "directory"
			}
			if kind != "" {
				name := filepath.Join(directory.input.Name(), filepath.FromSlash(parent), entry.Name())
				if directory.hostRoot != "" {
					name = strings.TrimSuffix(directory.hostRoot, "/") + "/"
					if parent != "." {
						name += parent + "/"
					}
					name += entry.Name()
				}
				targets = append(targets, ingestiondomain.ScanTarget{ID: ingestiondomain.CandidateID(name), SourcePath: name, Kind: kind, Name: entry.Name()})
			}
			if len(targets) > ingestiondomain.MaxCandidates {
				return nil, ingestiondomain.ErrLimit
			}
		}
		if errors.Is(readErr, io.EOF) {
			break
		}
	}
	if len(targets) > ingestiondomain.MaxCandidates {
		return nil, ingestiondomain.ErrLimit
	}
	ingestiondomain.SortTargets(targets)
	return targets, nil
}
