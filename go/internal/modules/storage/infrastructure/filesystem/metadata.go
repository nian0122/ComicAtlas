package filesystem

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	storageports "comicatlas.local/backend/internal/modules/storage/application/ports"
	storagedomain "comicatlas.local/backend/internal/modules/storage/domain"
	"comicatlas.local/backend/internal/platform/natural"
	"context"
	"errors"
	"io"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"
)

type MetadataFiles struct {
	scanner *Scanner
	video   storageports.MetadataVideo
}

func NewMetadata(scanner *Scanner, video storageports.MetadataVideo) *MetadataFiles {
	return &MetadataFiles{scanner, video}
}

var _ storageports.MetadataFiles = (*MetadataFiles)(nil)

func metadataPath(name, prefix string) bool {
	return filepath.IsLocal(name) && path.Clean(name) == name && strings.HasPrefix(name, prefix+"/") && !strings.ContainsAny(name, "\\:") && len(name) <= 2048
}
func (files *MetadataFiles) parents(name string) error {
	parts := strings.Split(name, "/")
	current := ""
	for _, part := range parts[:len(parts)-1] {
		current = path.Join(current, part)
		info, err := files.scanner.root.Lstat(filepath.FromSlash(current))
		if err != nil {
			return err
		}
		if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return storagedomain.ErrScanRead
		}
	}
	return nil
}
func (files *MetadataFiles) measure(ctx context.Context, name string, inspect bool) (info mediadomain.Inspection, size, modified int64, err error) {
	if err = ctx.Err(); err != nil {
		return
	}
	if err = files.parents(name); err != nil {
		return
	}
	before, err := files.scanner.root.Lstat(filepath.FromSlash(name))
	if err != nil {
		return
	}
	if !before.Mode().IsRegular() {
		err = storagedomain.ErrScanRead
		return
	}
	input, err := files.scanner.root.Open(filepath.FromSlash(name))
	if err != nil {
		return
	}
	defer input.Close()
	opened, err := input.Stat()
	if err != nil {
		return
	}
	if !os.SameFile(before, opened) {
		err = storagedomain.ErrScanRead
		return
	}
	if inspect {
		info, err = mediadomain.InspectImageReader(io.LimitReader(input, 1<<20))
		if err != nil {
			return
		}
	}
	after, statErr := input.Stat()
	current, currentErr := files.scanner.root.Lstat(filepath.FromSlash(name))
	if statErr != nil || currentErr != nil || !os.SameFile(before, current) || after.Size() != before.Size() || !after.ModTime().Equal(before.ModTime()) {
		err = storagedomain.ErrScanRead
		return
	}
	size, modified = before.Size(), before.ModTime().UnixNano()
	return
}

func (files *MetadataFiles) inspect(ctx context.Context, name, kind string) (mediadomain.Inspection, int64, int64, error) {
	if kind == "video" {
		return files.measureVideo(ctx, name)
	}
	return files.measure(ctx, name, true)
}

// measureVideo 只打开并探测视频，不读取整个文件内容。
func (files *MetadataFiles) measureVideo(ctx context.Context, name string) (mediadomain.Inspection, int64, int64, error) {
	var info mediadomain.Inspection
	if err := files.parents(name); err != nil {
		return info, 0, 0, err
	}
	before, err := files.scanner.root.Lstat(filepath.FromSlash(name))
	if err != nil {
		return info, 0, 0, err
	}
	if !before.Mode().IsRegular() {
		return info, 0, 0, storagedomain.ErrScanRead
	}
	input, err := files.scanner.root.Open(filepath.FromSlash(name))
	if err != nil {
		return info, 0, 0, err
	}
	defer input.Close()
	opened, err := input.Stat()
	if err != nil {
		return info, 0, 0, err
	}
	if !os.SameFile(before, opened) {
		return info, 0, 0, storagedomain.ErrScanRead
	}
	if files.video == nil {
		return info, 0, 0, mediadomain.ErrUnavailable
	}
	video, err := files.video.Inspect(ctx, filepath.Join(files.scanner.root.Name(), filepath.FromSlash(name)))
	if err != nil {
		return info, 0, 0, err
	}
	info = mediadomain.Inspection{MediaType: "video", ContentType: mediadomain.ContentType(video.Container), Width: video.Width, Height: video.Height, Video: &video}
	after, statErr := input.Stat()
	current, currentErr := files.scanner.root.Lstat(filepath.FromSlash(name))
	if statErr != nil || currentErr != nil || !os.SameFile(before, current) || before.Size() != after.Size() || !after.ModTime().Equal(before.ModTime()) {
		return mediadomain.Inspection{}, 0, 0, storagedomain.ErrScanRead
	}
	return info, before.Size(), before.ModTime().UnixNano(), nil
}
func (files *MetadataFiles) ObserveMetadata(ctx context.Context, snapshot librarydomain.Snapshot, progress func(int) error) ([]storagedomain.MediaObservation, error) {
	items := []storagedomain.MediaObservation{}
	known := map[string]bool{}
	for _, name := range snapshot.SuppressedPaths {
		known[strings.ToLower(name)] = true
	}
	parents := map[string]bool{}
	for _, media := range snapshot.Media {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		id, chapter := media.ID, media.ChapterID
		item := storagedomain.MediaObservation{MediaID: &id, ChapterID: &chapter, FileName: media.FileName, StoragePath: media.StoragePath, Status: "released"}
		if !metadataPath(media.StoragePath, "hq") {
			return nil, storagedomain.ErrScanRead
		}
		known[strings.ToLower(media.StoragePath)] = true
		parents[path.Dir(media.StoragePath)] = true
		if media.HQState != "released" {
			info, size, modified, err := files.inspect(ctx, media.StoragePath, media.MediaType)
			switch {
			case ctx.Err() != nil:
				return nil, ctx.Err()
			case errors.Is(err, os.ErrNotExist):
				item.Status = "missing"
			case errors.Is(err, mediadomain.ErrImageInvalid), errors.Is(err, mediadomain.ErrInvalidVideo):
				item.Status = "invalid"
				code := "METADATA_MEDIA_INVALID"
				item.ErrorCode = &code
			case err != nil:
				return nil, err
			default:
				item.Status = storagedomain.ObservedMediaStatus(media, info, size, modified)
				item.Inspection = &info
				item.Size, item.Modified = size, modified
			}
		}
		if media.LQFile != nil {
			lq := media.LQFile
			if !metadataPath(lq.StoragePath, "lq") {
				return nil, storagedomain.ErrScanRead
			}
			_, size, _, err := files.measure(ctx, lq.StoragePath, false)
			if ctx.Err() != nil {
				return nil, ctx.Err()
			}
			if err != nil && !errors.Is(err, os.ErrNotExist) {
				return nil, err
			}
			valid := lq.Valid && err == nil && size == lq.Size && item.Status != "changed"
			item.LQValid = &valid
		}
		item.Position = len(items) + 1
		items = append(items, item)
		if err := progress(len(items)); err != nil {
			return nil, err
		}
	}
	discovered, err := files.discover(ctx, snapshot, known, parents, func(count int) error { return progress(len(items) + count) })
	if err != nil {
		return nil, err
	}
	for _, item := range discovered {
		item.Position = len(items) + 1
		items = append(items, item)
	}
	return items, nil
}
func (files *MetadataFiles) discover(ctx context.Context, snapshot librarydomain.Snapshot, known, parents map[string]bool, progress func(int) error) ([]storagedomain.MediaObservation, error) {
	root := "hq/" + snapshot.Detail.ID
	if !metadataPath(root, "hq") {
		return nil, storagedomain.ErrScanRead
	}
	seen := map[string]bool{}
	items := []storagedomain.MediaObservation{}
	entries := 0
	owners := storagedomain.MetadataDirectoryChapters(snapshot)
	names := storagedomain.MetadataNames(snapshot)
	var walk func(string, int, bool) error
	walk = func(name string, depth int, recurse bool) error {
		if seen[name] {
			return nil
		}
		seen[name] = true
		if depth > storagedomain.MaxScanDepth {
			return storagedomain.ErrScanLimit
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		if err := files.parents(name + "/entry"); err != nil {
			if errors.Is(err, os.ErrNotExist) {
				return nil
			}
			return err
		}
		before, err := files.scanner.root.Lstat(filepath.FromSlash(name))
		if errors.Is(err, os.ErrNotExist) {
			return nil
		}
		if err != nil || !before.IsDir() || before.Mode()&os.ModeSymlink != 0 {
			return storagedomain.ErrScanRead
		}
		dir, err := files.scanner.root.Open(filepath.FromSlash(name))
		if err != nil {
			return err
		}
		defer dir.Close()
		opened, err := dir.Stat()
		if err != nil || !os.SameFile(before, opened) {
			return storagedomain.ErrScanRead
		}
		for {
			children, readErr := dir.ReadDir(128)
			if readErr != nil && readErr != io.EOF {
				return readErr
			}
			for _, child := range children {
				if err := ctx.Err(); err != nil {
					return err
				}
				entries++
				if entries > storagedomain.MaxScanEntries {
					return storagedomain.ErrScanLimit
				}
				if strings.HasPrefix(child.Name(), ".") {
					continue
				}
				target := path.Join(name, child.Name())
				if len(target) > 2048 {
					return storagedomain.ErrScanLimit
				}
				info, err := files.scanner.root.Lstat(filepath.FromSlash(target))
				if err != nil {
					return err
				}
				if known[strings.ToLower(target)] {
					continue
				}
				if info.IsDir() && info.Mode()&os.ModeSymlink == 0 {
					if recurse {
						if err = walk(target, depth+1, true); err != nil {
							return err
						}
					}
					continue
				}
				item := storagedomain.MediaObservation{FileName: child.Name(), StoragePath: target, Size: info.Size(), Status: "discovered"}
				if !info.Mode().IsRegular() || mediadomain.UploadMediaKind(child.Name()) == "" {
					item.Status = "ignored"
					code := "METADATA_UNSUPPORTED_ENTRY"
					item.ErrorCode = &code
				}
				if item.Status == "discovered" && owners[name] != nil {
					chapter := *owners[name]
					if !storagedomain.DiscoveryNameAllowed(child.Name()) || names[chapter+"\x00"+strings.ToLower(child.Name())] {
						item.Status = "ignored"
						code := "METADATA_NAME_CONFLICT"
						item.ErrorCode = &code
					} else {
						kind := mediadomain.UploadMediaKind(child.Name())
						inspection, size, modified, inspectErr := files.inspect(ctx, target, kind)
						if errors.Is(inspectErr, mediadomain.ErrImageInvalid) || errors.Is(inspectErr, mediadomain.ErrInvalidVideo) {
							item.Status = "ignored"
							code := "METADATA_MEDIA_INVALID"
							item.ErrorCode = &code
						} else if inspectErr != nil {
							return inspectErr
						} else {
							id := storagedomain.DiscoveredMediaID(snapshot.Detail.ID, chapter, target)
							item.MediaID, item.ChapterID = &id, &chapter
							item.Inspection, item.Size, item.Modified = &inspection, size, modified
						}
					}
				}
				items = append(items, item)
				if len(items) > librarydomain.MaxSnapshotMedia {
					return storagedomain.ErrScanLimit
				}
				if err = progress(len(items)); err != nil {
					return err
				}
			}
			if readErr == io.EOF {
				break
			}
		}
		return nil
	}
	if err := walk(root, 0, true); err != nil {
		return nil, err
	}
	directories := make([]string, 0, len(parents))
	for name := range parents {
		directories = append(directories, name)
	}
	sort.Strings(directories)
	for _, name := range directories {
		if err := walk(name, 0, false); err != nil {
			return nil, err
		}
	}
	sort.Slice(items, func(i, j int) bool {
		comp := natural.Compare(items[i].StoragePath, items[j].StoragePath)
		if comp == 0 {
			return items[i].StoragePath < items[j].StoragePath
		}
		return comp < 0
	})
	return items, nil
}
