package application

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"strings"

	ingestiondomain "comicatlas.local/backend/internal/modules/ingestion/domain"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/relocate"
)

func importTempRoot(id string) string { return "temp/import/" + id }

func importTaskRoot(id string) string  { return "staging/import/" + id }
func preparationRoot(id string) string { return importTaskRoot(id) + "/work" }
func publicationRoot(id string) string { return importTaskRoot(id) + "/ready" }

// prepare 依次完成解压、来源整理、稳定 ID 分配和媒体分析；任何搬移后的错误都保留现场。
func (directory *Directory) prepare(ctx context.Context, task jobsdomain.Task, stage string) (librarydomain.Plan, error) {
	root := preparationRoot(task.ID)
	journal, err := directory.loadPreparation(task, root)
	if errors.Is(err, os.ErrNotExist) {
		journal, err = directory.startPreparation(ctx, task, root)
	}
	if err != nil {
		return librarydomain.Plan{}, err
	}
	bySource := make(map[string]*ingestiondomain.PreparedFile, len(journal.Files))
	analyzedHere := false
	normalizedHere := false
	for index := range journal.Files {
		file := &journal.Files[index]
		bySource[file.Source] = file
	}
	if journal.Layout == nil {
		if err = directory.normalize(ctx, task, root, &journal); err != nil {
			return librarydomain.Plan{}, err
		}
		normalizedHere = true
		names := make([]string, 0, len(journal.Files))
		for _, file := range journal.Files {
			names = append(names, file.Source)
		}
		layout, layoutErr := ingestiondomain.NormalizeLayout(task.ID, librarydomain.Comic{ID: task.ComicID, Title: task.Title, CreatedAt: task.CreatedAt}, names)
		if layoutErr != nil {
			return librarydomain.Plan{}, layoutErr
		}
		layout.Plan.ComicInfo = journal.ComicInfo
		layout.Plan.Comic.Author = journal.Comic.Author
		layout.Plan.Comic.Description = journal.Comic.Description
		layout.Plan.Comic.CategoryName = journal.Comic.CategoryName
		layout.Plan.Comic.Tags = journal.Comic.Tags
		if !task.TitleExplicit && journal.Comic.Title != "" {
			layout.Plan.Comic.Title = journal.Comic.Title
		}
		if journal.Document != nil {
			copied := map[string]librarydomain.Media{}
			for index, item := range layout.Plan.Media {
				file := bySource[layout.Sources[item.ID]]
				if file == nil {
					return librarydomain.Plan{}, ingestiondomain.ErrManifest
				}
				if err = directory.inspectPrepared(ctx, root+"/"+file.Normalized, &item, file.Hash); err != nil {
					return librarydomain.Plan{}, err
				}
				copied[file.Source] = item
				layout.Plan.Media[index] = item
			}
			if err = restoreArchiveMetadata(task, &layout.Plan, *journal.Document, copied); err != nil {
				return librarydomain.Plan{}, err
			}
			analyzedHere = true
		}
		journal.Layout = &layout
		if err = directory.savePreparation(root, journal); err != nil {
			return librarydomain.Plan{}, err
		}
	}
	plan := journal.Layout.Plan
	if err = directory.media.MkdirAll(stage, 0750); err != nil {
		return plan, storageFailure(err)
	}
	if err = directory.stageChapters(ctx, task, root, stage, journal); err != nil {
		return plan, err
	}
	if err = directory.store.UpdateProgress(ctx, task.ID, "analyzing", len(plan.Media), 0); err != nil {
		return plan, err
	}
	for index := range plan.Media {
		if err = ctx.Err(); err != nil {
			return plan, err
		}
		item := &plan.Media[index]
		file := bySource[journal.Layout.Sources[item.ID]]
		if file == nil {
			return plan, ingestiondomain.ErrManifest
		}
		relative := strings.TrimPrefix(item.RelativePath, "hq/"+task.ComicID+"/")
		target := stage + "/" + relative
		if err = directory.checkMoved(directory.media, target, *file); err != nil {
			return plan, err
		}
		if item.SHA256 == "" {
			verifiedHash := ""
			if normalizedHere {
				verifiedHash = file.Hash
			}
			if err = directory.inspectPrepared(ctx, target, item, verifiedHash); err != nil {
				return plan, err
			}
		} else if !analyzedHere {
			if err = directory.checkHash(ctx, target, item.Size, item.SHA256); err != nil {
				return plan, err
			}
		}
		if file.Hash != "" && item.SHA256 != file.Hash {
			return plan, ingestiondomain.ErrPublishedChanged
		}
		if index+1 == len(plan.Media) || plan.Media[index+1].ChapterID != item.ChapterID {
			journal.Layout.Plan = plan
			if err = directory.savePreparation(root, journal); err != nil {
				return plan, err
			}
		}
		if (index+1)%8 == 0 || index+1 == len(plan.Media) {
			if err = directory.store.UpdateProgress(ctx, task.ID, "analyzing", len(plan.Media), index+1); err != nil {
				return plan, err
			}
		}
	}
	return plan, nil
}

func (directory *Directory) startPreparation(ctx context.Context, task jobsdomain.Task, root string) (ingestiondomain.Preparation, error) {
	journal := ingestiondomain.Preparation{Version: 2, TaskID: task.ID, SourcePath: task.SourcePath, Archive: task.Type == "archive_import", Files: []ingestiondomain.PreparedFile{}}
	var source *os.Root
	var names []string
	var err error
	if journal.Archive {
		selection, openErr := directory.openArchive(ctx, task)
		if openErr != nil {
			return journal, openErr
		}
		defer selection.reader.Close()
		journal.Comic, journal.ComicInfo, journal.Document = selection.metadata, selection.comicInfo, selection.document
		extracted := importTempRoot(task.ID)
		// 只允许在尚无整理计划时重建解压产物；原压缩包始终保留。
		if err = directory.media.RemoveAll(extracted); err != nil {
			return journal, storageFailure(err)
		}
		if err = directory.media.MkdirAll(extracted, 0750); err != nil {
			return journal, storageFailure(err)
		}
		if err = directory.store.UpdateProgress(ctx, task.ID, "extracting", len(selection.files), 0); err != nil {
			return journal, err
		}
		var total int64
		for index, name := range selection.files {
			entry := selection.entries[name]
			if entry.UncompressedSize64 > uint64(min(MaxFileBytes, MaxTotalBytes-total)) {
				return journal, ingestiondomain.ErrLimit
			}
			stream, openErr := entry.Open()
			if openErr != nil {
				return journal, archiveContentFailure(openErr)
			}
			target := extracted + "/" + name
			err = directory.extractStream(ctx, stream, target, int64(entry.UncompressedSize64))
			closeErr := stream.Close()
			if err = errors.Join(err, closeErr); err != nil {
				return journal, archiveContentFailure(err)
			}
			total += int64(entry.UncompressedSize64)
			if (index+1)%16 == 0 || index+1 == len(selection.files) {
				if err = directory.store.UpdateProgress(ctx, task.ID, "extracting", len(selection.files), index+1); err != nil {
					return journal, err
				}
			}
		}
		if err = selection.reader.Unchanged(); err != nil {
			return journal, sourceFailure(err)
		}
		source, err = directory.media.OpenRoot(extracted)
		names = selection.files
	} else {
		source, err = directory.checkedInputRoot(task.SourcePath)
		if err == nil {
			names, err = scan(ctx, source)
		}
	}
	if source != nil {
		defer source.Close()
	}
	if err != nil {
		return journal, err
	}
	if !journal.Archive {
		metadata, info, readErr := directory.readDirectoryMetadata(ctx, source, task)
		if readErr != nil {
			return journal, readErr
		}
		journal.Comic, journal.ComicInfo = metadata, info
	}
	// 同目录的媒体归入一个平铺章节；编号只是暂存位置，不是业务 ID。
	groups := map[string]int{}
	for _, name := range names {
		parent := path.Dir(name)
		number, ok := groups[parent]
		if !ok {
			number = len(groups) + 1
			groups[parent] = number
		}
		file, openErr := source.Open(name)
		if openErr != nil {
			return journal, sourceFailure(openErr)
		}
		info, statErr := file.Stat()
		identity, idErr := relocate.Identity(file)
		// 搬移前只做图片头预检，避免明显损坏的来源进入需要恢复的移动阶段。
		if kind, _, ok := ingestiondomain.MediaFormat(name); ok && kind == "image" && statErr == nil && idErr == nil {
			header, readErr := io.ReadAll(io.LimitReader(contextReader{ctx: ctx, reader: file}, 1<<20))
			if readErr != nil {
				file.Close()
				return journal, sourceFailure(readErr)
			}
			if _, decodeErr := mediadomain.InspectImage(header); decodeErr != nil {
				file.Close()
				return journal, ingestiondomain.ErrImage
			}
		}
		file.Close()
		if statErr != nil || idErr != nil {
			return journal, errors.Join(statErr, idErr)
		}
		journal.Files = append(journal.Files, ingestiondomain.PreparedFile{Source: name, Normalized: fmt.Sprintf("chapters/%d/%s", number, path.Base(name)), Size: info.Size(), Modified: info.ModTime().UnixNano(), Identity: identity})
	}
	if err = directory.savePreparation(root, journal); err != nil {
		return journal, err
	}
	return journal, nil
}

func (directory *Directory) extractStream(ctx context.Context, stream io.Reader, target string, size int64) error {
	if err := directory.media.MkdirAll(path.Dir(target), 0750); err != nil {
		return storageFailure(err)
	}
	file, err := directory.media.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0640)
	if err != nil {
		return storageFailure(err)
	}
	count, copyErr := io.CopyBuffer(storageWriter{writer: file}, contextReader{ctx: ctx, reader: io.LimitReader(stream, size+1)}, directory.transferBuffer())
	err = errors.Join(copyErr, file.Sync(), file.Close())
	if err != nil {
		return err
	}
	if count != size {
		return ingestiondomain.ErrSourceChanged
	}
	return nil
}

func (directory *Directory) loadPreparation(task jobsdomain.Task, root string) (ingestiondomain.Preparation, error) {
	var journal ingestiondomain.Preparation
	file, err := directory.media.Open(root + "/preparation.json")
	if err != nil {
		return journal, err
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, 32<<20+1))
	if err != nil {
		return journal, err
	}
	if len(data) > 32<<20 || json.Unmarshal(data, &journal) != nil || journal.Version != 2 || journal.TaskID != task.ID || journal.SourcePath != task.SourcePath || journal.Archive != (task.Type == "archive_import") || len(journal.Files) == 0 || len(journal.Files) > MaxMedia {
		return journal, ingestiondomain.ErrManifest
	}
	for _, file := range journal.Files {
		if !safeRelative(file.Source) || !safeRelative(file.Normalized) || !strings.HasPrefix(file.Normalized, "chapters/") || file.Size < 0 || file.Size > MaxFileBytes || file.Identity == "" {
			return journal, ingestiondomain.ErrManifest
		}
	}
	if journal.Layout != nil && journal.Layout.Plan.Comic.ID != task.ComicID {
		return journal, ingestiondomain.ErrManifest
	}
	return journal, nil
}
func safeRelative(name string) bool {
	return name != "." && path.Clean(name) == name && !strings.HasPrefix(name, "/") && name != ".." && !strings.HasPrefix(name, "../") && !strings.ContainsAny(name, "\\:\x00")
}

func (directory *Directory) savePreparation(root string, journal ingestiondomain.Preparation) error {
	if err := directory.media.MkdirAll(root, 0750); err != nil {
		return storageFailure(err)
	}
	data, err := json.Marshal(journal)
	if err != nil {
		return err
	}
	if len(data) > 32<<20 {
		return ingestiondomain.ErrLimit
	}
	temporary := root + "/preparation.next"
	if err = directory.media.Remove(temporary); err != nil && !errors.Is(err, os.ErrNotExist) {
		return storageFailure(err)
	}
	file, err := directory.media.OpenFile(temporary, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0640)
	if err != nil {
		return storageFailure(err)
	}
	_, writeErr := file.Write(data)
	if err = errors.Join(writeErr, file.Sync(), file.Close()); err != nil {
		return storageFailure(err)
	}
	if err = directory.media.Rename(temporary, root+"/preparation.json"); err != nil {
		return storageFailure(err)
	}
	return directory.syncMediaDirectory(root)
}

func (directory *Directory) publish(stage, target string) error {
	return directory.moveBetween(directory.media, stage, target)
}

func (directory *Directory) moveBetween(source *os.Root, name, target string) error {
	if err := directory.renameBetween(source, name, target); err != nil {
		return err
	}
	parent, err := source.Open(path.Dir(name))
	if err != nil {
		return err
	}
	defer parent.Close()
	if err = directory.syncMediaParents(path.Dir(target)); err != nil {
		return err
	}
	return relocate.SyncDirectory(parent)
}

func (directory *Directory) renameBetween(source *os.Root, name, target string) error {
	if err := safeParents(source, name); err != nil {
		return err
	}
	if err := safeParents(directory.media, target); err != nil {
		return err
	}
	info, err := source.Lstat(name)
	if err != nil {
		return err
	}
	if !info.IsDir() && !info.Mode().IsRegular() {
		return ingestiondomain.ErrUnsafeSource
	}
	parent, err := source.Open(path.Dir(name))
	if err != nil {
		return err
	}
	defer parent.Close()
	destination, err := directory.media.Open(path.Dir(target))
	if err != nil {
		return err
	}
	defer destination.Close()
	if err = relocate.NoReplace(parent, destination, path.Base(name), path.Base(target), info); err != nil {
		return err
	}
	return nil
}

func safeParents(root *os.Root, name string) error {
	if !safeRelative(name) {
		return ingestiondomain.ErrUnsafeSource
	}
	current := ""
	for _, part := range strings.Split(path.Dir(name), "/") {
		current = path.Join(current, part)
		info, err := root.Lstat(current)
		if err != nil {
			return err
		}
		if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return ingestiondomain.ErrUnsafeSource
		}
	}
	return nil
}
func (directory *Directory) syncMediaDirectory(name string) error {
	file, err := directory.media.Open(name)
	if err != nil {
		return err
	}
	defer file.Close()
	return relocate.SyncDirectory(file)
}

func (directory *Directory) syncMediaParents(name string) error {
	for {
		if err := directory.syncMediaDirectory(name); err != nil {
			return err
		}
		if name == "." {
			return nil
		}
		name = path.Dir(name)
	}
}
