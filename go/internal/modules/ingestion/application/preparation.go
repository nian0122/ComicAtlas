package application

import (
	"context"
	"encoding/json"
	"errors"
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

// preparationVersion 4 把业务布局提前固定，只使用 staging/import/{taskId} 一个任务工作区。
const preparationVersion = 4

func importTaskRoot(id string) string  { return "staging/import/" + id }
func preparationRoot(id string) string { return importTaskRoot(id) + "/work" }
func extractionRoot(id string) string  { return preparationRoot(id) + "/extract" }
func nonMediaRoot(id string) string    { return preparationRoot(id) + "/nonmedia" }
func publicationRoot(id string) string { return importTaskRoot(id) + "/ready" }

const publicationMarker = ".comicatlas-import"

func (directory *Directory) writePublicationMarker(stage, taskID string) error {
	name := stage + "/" + publicationMarker
	file, err := directory.media.OpenFile(name, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if errors.Is(err, os.ErrExist) {
		if err := directory.checkPublicationMarker(stage, taskID); err == nil {
			return directory.syncMediaDirectory(stage)
		}
		return ingestiondomain.ErrPublishedChanged
	}
	if err != nil {
		return storageFailure(err)
	}
	_, writeErr := io.WriteString(file, taskID)
	if err = errors.Join(writeErr, file.Sync(), file.Close()); err != nil {
		return storageFailure(err)
	}
	return directory.syncMediaDirectory(stage)
}

func (directory *Directory) checkPublicationMarker(base, taskID string) error {
	name := base + "/" + publicationMarker
	if err := safeParents(directory.media, name); err != nil {
		return err
	}
	info, err := directory.media.Lstat(name)
	if err != nil {
		return publishedReadFailure(err)
	}
	if !info.Mode().IsRegular() || info.Size() != int64(len(taskID)) {
		return ingestiondomain.ErrPublishedChanged
	}
	file, err := directory.media.Open(name)
	if err != nil {
		return publishedReadFailure(err)
	}
	data, readErr := io.ReadAll(io.LimitReader(file, int64(len(taskID)+1)))
	if err = errors.Join(readErr, file.Close()); err != nil {
		return publishedReadFailure(err)
	}
	if string(data) != taskID {
		return ingestiondomain.ErrPublishedChanged
	}
	return nil
}

// prepare 先固化完整布局，再把媒体物化到 ready，最后在 ready 内分析。
// 检查点只保存在 staging/import/{taskId}，解压、整理和发布都不再使用 temp。
func (directory *Directory) prepare(ctx context.Context, task jobsdomain.Task, stage string) (librarydomain.Plan, error) {
	root := preparationRoot(task.ID)
	journal, err := directory.loadPreparation(task, root)
	if errors.Is(err, os.ErrNotExist) {
		journal, err = directory.startPreparation(ctx, task, root)
	}
	if err != nil {
		return librarydomain.Plan{}, err
	}
	if err = directory.materialize(ctx, task, stage, &journal); err != nil {
		return journal.Layout.Plan, err
	}
	if err = directory.analyze(ctx, task, stage, &journal); err != nil {
		return journal.Layout.Plan, err
	}
	return journal.Layout.Plan, nil
}

// startPreparation 在搬移来源前完成扫描、资料读取和稳定布局分配。
// 归档先解压到任务工作区再建立计划；目录直接建立计划，随后由 materialize 搬移。
func (directory *Directory) startPreparation(ctx context.Context, task jobsdomain.Task, root string) (ingestiondomain.Preparation, error) {
	journal := ingestiondomain.Preparation{Version: preparationVersion, TaskID: task.ID, SourcePath: task.SourcePath, Archive: task.Type == "archive_import", Files: []ingestiondomain.PreparedFile{}}
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
		extracted := extractionRoot(task.ID)
		// 只在尚无任务计划时重建解压产物；原压缩包始终保留。
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
	for _, name := range names {
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
		journal.Files = append(journal.Files, ingestiondomain.PreparedFile{Source: name, Size: info.Size(), Modified: info.ModTime().UnixNano(), Identity: identity})
	}
	layout, layoutErr := ingestiondomain.NormalizeLayout(task.ID, librarydomain.Comic{ID: task.ComicID, Title: task.Title, CreatedAt: task.CreatedAt}, names)
	if layoutErr != nil {
		return journal, layoutErr
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
		bySource := make(map[string]*ingestiondomain.PreparedFile, len(journal.Files))
		for index := range journal.Files {
			bySource[journal.Files[index].Source] = &journal.Files[index]
		}
		copied := map[string]librarydomain.Media{}
		for index, item := range layout.Plan.Media {
			file := bySource[layout.Sources[item.ID]]
			if file == nil {
				return journal, ingestiondomain.ErrManifest
			}
			if err = directory.inspectPrepared(ctx, extractionRoot(task.ID)+"/"+file.Source, &item); err != nil {
				return journal, err
			}
			copied[file.Source] = item
			layout.Plan.Media[index] = item
		}
		if err = restoreArchiveMetadata(task, &layout.Plan, *journal.Document, copied); err != nil {
			return journal, err
		}
	}
	if err = setTargets(&journal, layout, task.ComicID); err != nil {
		return journal, err
	}
	journal.Layout = &layout
	if err = directory.savePreparation(root, journal); err != nil {
		return journal, err
	}
	return journal, nil
}

func setTargets(journal *ingestiondomain.Preparation, layout ingestiondomain.Layout, comicID string) error {
	targets := make(map[string]string, len(layout.Plan.Media))
	for _, item := range layout.Plan.Media {
		source, ok := layout.Sources[item.ID]
		if !ok {
			return ingestiondomain.ErrManifest
		}
		target := strings.TrimPrefix(item.RelativePath, "hq/"+comicID+"/")
		if target == item.RelativePath || strings.Count(target, "/") != 1 {
			return ingestiondomain.ErrManifest
		}
		targets[source] = target
	}
	if len(targets) != len(journal.Files) {
		return ingestiondomain.ErrManifest
	}
	for index := range journal.Files {
		target, ok := targets[journal.Files[index].Source]
		if !ok {
			return ingestiondomain.ErrManifest
		}
		journal.Files[index].Target = target
	}
	return nil
}

// analyze 在 ready 中核验暂存文件并读取图片/视频资料；每章结束刷新检查点。
func (directory *Directory) analyze(ctx context.Context, task jobsdomain.Task, stage string, journal *ingestiondomain.Preparation) error {
	plan := journal.Layout.Plan
	bySource := make(map[string]*ingestiondomain.PreparedFile, len(journal.Files))
	for index := range journal.Files {
		bySource[journal.Files[index].Source] = &journal.Files[index]
	}
	if err := directory.store.UpdateProgress(ctx, task.ID, "analyzing", len(plan.Media), 0); err != nil {
		return err
	}
	for index := range plan.Media {
		if err := ctx.Err(); err != nil {
			return err
		}
		item := &plan.Media[index]
		file := bySource[journal.Layout.Sources[item.ID]]
		if file == nil {
			return ingestiondomain.ErrManifest
		}
		target := stage + "/" + strings.TrimPrefix(item.RelativePath, "hq/"+task.ComicID+"/")
		if err := directory.checkMoved(directory.media, target, *file); err != nil {
			return err
		}
		if item.Modified == 0 {
			if err := directory.inspectPrepared(ctx, target, item); err != nil {
				return err
			}
		}
		if item.Size != file.Size || item.Modified != file.Modified {
			return ingestiondomain.ErrPublishedChanged
		}
		if index+1 == len(plan.Media) || plan.Media[index+1].ChapterID != item.ChapterID {
			journal.Layout.Plan = plan
			if err := directory.savePreparation(preparationRoot(task.ID), *journal); err != nil {
				return err
			}
		}
		if (index+1)%8 == 0 || index+1 == len(plan.Media) {
			if err := directory.store.UpdateProgress(ctx, task.ID, "analyzing", len(plan.Media), index+1); err != nil {
				return err
			}
		}
	}
	return nil
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
	if len(data) > 32<<20 || json.Unmarshal(data, &journal) != nil || journal.Version != preparationVersion || journal.TaskID != task.ID || journal.SourcePath != task.SourcePath || journal.Archive != (task.Type == "archive_import") || journal.Layout == nil || len(journal.Files) == 0 || len(journal.Files) > MaxMedia {
		return journal, ingestiondomain.ErrManifest
	}
	if journal.Layout.Plan.Comic.ID != task.ComicID || len(journal.Layout.Plan.Media) != len(journal.Files) {
		return journal, ingestiondomain.ErrManifest
	}
	bySource := make(map[string]*ingestiondomain.PreparedFile, len(journal.Files))
	for index := range journal.Files {
		file := &journal.Files[index]
		if !safeRelative(file.Source) || !safeRelative(file.Target) || file.Size < 0 || file.Size > MaxFileBytes || file.Modified == 0 || !journal.Archive && file.Identity == "" {
			return journal, ingestiondomain.ErrManifest
		}
		if bySource[file.Source] != nil {
			return journal, ingestiondomain.ErrManifest
		}
		bySource[file.Source] = file
	}
	seen := make(map[string]bool, len(journal.Files))
	for _, item := range journal.Layout.Plan.Media {
		source, ok := journal.Layout.Sources[item.ID]
		if !ok {
			return journal, ingestiondomain.ErrManifest
		}
		file, ok := bySource[source]
		if !ok {
			return journal, ingestiondomain.ErrManifest
		}
		target := strings.TrimPrefix(item.RelativePath, "hq/"+task.ComicID+"/")
		if target == item.RelativePath || file.Target != target || strings.Count(target, "/") != 1 || seen[target] {
			return journal, ingestiondomain.ErrManifest
		}
		seen[target] = true
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
