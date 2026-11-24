package application

import (
	"context"
	"errors"
	"io"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"

	ingestiondomain "comicatlas.local/backend/internal/modules/ingestion/domain"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/relocate"
)

type materialItem struct {
	source string
	target string
	file   *ingestiondomain.PreparedFile
}

type materialGroup struct {
	items []materialItem
}

// whole 返回整组共享的目标目录；只有全部条目同名且落到同一章节目录时才允许整目录搬移。
func (group *materialGroup) whole() (string, bool) {
	if len(group.items) == 0 {
		return "", false
	}
	target := path.Dir(group.items[0].target)
	for _, item := range group.items {
		if path.Dir(item.target) != target || path.Base(item.source) != path.Base(item.target) {
			return "", false
		}
	}
	return target, true
}

// materialize 把来源里的媒体直接物化到 ready；归档的临时解压区位于任务工作区，不再有独立 temp 根。
func (directory *Directory) materialize(ctx context.Context, task jobsdomain.Task, stage string, journal *ingestiondomain.Preparation) error {
	if err := directory.media.MkdirAll(stage, 0750); err != nil {
		return storageFailure(err)
	}
	plan := journal.Layout.Plan
	bySource := make(map[string]*ingestiondomain.PreparedFile, len(journal.Files))
	for index := range journal.Files {
		bySource[journal.Files[index].Source] = &journal.Files[index]
	}
	if journal.Archive {
		if err := directory.ensureExtraction(ctx, task, stage, journal); err != nil {
			return err
		}
	}
	origin, name := directory.importSource(task, journal)
	groups, order, err := materialGroups(plan, stage, task.ComicID, journal.Layout.Sources, bySource)
	if err != nil {
		return err
	}
	if err = directory.store.UpdateProgress(ctx, task.ID, "normalizing", len(journal.Files), 0); err != nil {
		return err
	}
	completed := 0
	for _, parent := range order {
		if err = ctx.Err(); err != nil {
			return err
		}
		group := groups[parent]
		if err = directory.materializeGroup(task, origin, name, parent, group, journal.Archive); err != nil {
			return err
		}
		completed += len(group.items)
		if err = directory.store.UpdateProgress(ctx, task.ID, "normalizing", len(journal.Files), completed); err != nil {
			return err
		}
	}
	if journal.Archive {
		if err = directory.media.RemoveAll(extractionRoot(task.ID)); err != nil {
			return storageFailure(err)
		}
	} else if err = directory.restoreNonMedia(task, nonMediaRoot(task.ID)); err != nil {
		return err
	}
	return directory.store.UpdateProgress(ctx, task.ID, "normalizing", len(journal.Files), len(journal.Files))
}

func materialGroups(plan librarydomain.Plan, stage, comicID string, sources map[string]string, files map[string]*ingestiondomain.PreparedFile) (map[string]*materialGroup, []string, error) {
	groups := map[string]*materialGroup{}
	order := []string{}
	for _, item := range plan.Media {
		file, ok := files[sources[item.ID]]
		if !ok {
			return nil, nil, ingestiondomain.ErrManifest
		}
		parent := path.Dir(file.Source)
		group := groups[parent]
		if group == nil {
			group = &materialGroup{}
			groups[parent] = group
			order = append(order, parent)
		}
		group.items = append(group.items, materialItem{source: file.Source, target: stage + "/" + strings.TrimPrefix(item.RelativePath, "hq/"+comicID+"/"), file: file})
	}
	// 先深后浅，父目录的子章节搬走后才能判断整目录是否只剩媒体。
	sort.SliceStable(order, func(i, j int) bool { return parentDepth(order[i]) > parentDepth(order[j]) })
	return groups, order, nil
}

func (directory *Directory) materializeGroup(task jobsdomain.Task, origin *os.Root, name, parent string, group *materialGroup, archive bool) error {
	targetDir, whole := group.whole()
	if targetDir == "" {
		return directory.moveGroupFiles(origin, name, group)
	}
	targetExists, err := directory.exists(targetDir)
	if err != nil {
		return err
	}
	if targetExists {
		return directory.moveGroupFiles(origin, name, group)
	}
	sourceDir := path.Join(name, parent)
	if whole {
		planned := plannedNames(group)
		if archive {
			if !mediaOnly(origin, sourceDir, planned) {
				return ingestiondomain.ErrSourceChanged
			}
		} else {
			moved, pure, err := directory.stashGroupNonMedia(task, parent, planned)
			if err != nil {
				return err
			}
			if !moved {
				if err = directory.media.MkdirAll(targetDir, 0750); err != nil {
					return storageFailure(err)
				}
				return directory.moveGroupFiles(origin, name, group)
			}
			if !pure {
				return ingestiondomain.ErrSourceChanged
			}
		}
		if err = directory.moveTree(origin, sourceDir, directory.media, targetDir); err != nil {
			return importMoveFailure(err)
		}
		return nil
	}
	if err = directory.media.MkdirAll(targetDir, 0750); err != nil {
		return storageFailure(err)
	}
	return directory.moveGroupFiles(origin, name, group)
}

func (directory *Directory) moveGroupFiles(origin *os.Root, name string, group *materialGroup) error {
	for _, item := range group.items {
		targetExists, err := directory.exists(item.target)
		if err != nil {
			return err
		}
		if targetExists {
			if err = directory.checkMoved(directory.media, item.target, *item.file); err != nil {
				return err
			}
			present, statErr := directory.existsIn(origin, path.Join(name, item.source))
			if statErr != nil {
				return statErr
			}
			if present {
				return ingestiondomain.ErrPublishConflict
			}
			continue
		}
		source := path.Join(name, item.source)
		present, statErr := directory.existsIn(origin, source)
		if statErr != nil {
			return statErr
		}
		if !present {
			return ingestiondomain.ErrSourceChanged
		}
		if err = directory.media.MkdirAll(path.Dir(item.target), 0750); err != nil {
			return storageFailure(err)
		}
		if err = directory.moveTree(origin, source, directory.media, item.target); err != nil {
			return importMoveFailure(err)
		}
	}
	return nil
}

// ensureExtraction 只为尚未物化的归档条目补做解压；已发布的暂存文件绝不覆盖。
func (directory *Directory) ensureExtraction(ctx context.Context, task jobsdomain.Task, stage string, journal *ingestiondomain.Preparation) error {
	missing := []ingestiondomain.PreparedFile{}
	for _, file := range journal.Files {
		targetExists, err := directory.exists(stage + "/" + file.Target)
		if err != nil {
			return err
		}
		if targetExists {
			if err = directory.checkMoved(directory.media, stage+"/"+file.Target, file); err != nil {
				return err
			}
			continue
		}
		present, err := directory.existsIn(directory.media, extractionRoot(task.ID)+"/"+file.Source)
		if err != nil {
			return err
		}
		if present {
			continue
		}
		missing = append(missing, file)
	}
	if len(missing) == 0 {
		return nil
	}
	selection, err := directory.openArchive(ctx, task)
	if err != nil {
		return err
	}
	defer selection.reader.Close()
	extracted := extractionRoot(task.ID)
	if err = directory.media.MkdirAll(extracted, 0750); err != nil {
		return storageFailure(err)
	}
	for _, file := range missing {
		entry := selection.entries[file.Source]
		if entry == nil {
			return ingestiondomain.ErrManifest
		}
		stream, openErr := entry.Open()
		if openErr != nil {
			return archiveContentFailure(openErr)
		}
		err = directory.extractStream(ctx, stream, extracted+"/"+file.Source, int64(entry.UncompressedSize64))
		closeErr := stream.Close()
		if err = errors.Join(err, closeErr); err != nil {
			return archiveContentFailure(err)
		}
	}
	return selection.reader.Unchanged()
}

// importSource 返回媒体来源根与根内名称；归档来源是任务工作区内的解压目录。
func (directory *Directory) importSource(task jobsdomain.Task, journal *ingestiondomain.Preparation) (*os.Root, string) {
	if journal.Archive {
		return directory.media, extractionRoot(task.ID)
	}
	return directory.input, task.SourcePath
}

func plannedNames(group *materialGroup) map[string]bool {
	names := make(map[string]bool, len(group.items))
	for _, item := range group.items {
		names[path.Base(item.source)] = true
	}
	return names
}

// stashGroupNonMedia 把组目录里的非媒体资料按源相对路径移到任务工作区，
// 目录只剩计划内媒体后即可整目录 rename；全部目录组完成后统一还原。
// 非媒体移出再移回需要两次改名，超过媒体数一半时逐文件移动媒体更少。
func (directory *Directory) stashGroupNonMedia(task jobsdomain.Task, parent string, planned map[string]bool) (moved, pure bool, err error) {
	handle, err := directory.input.Open(path.Join(task.SourcePath, parent))
	if err != nil {
		return false, false, sourceFailure(err)
	}
	entries, readErr := handle.ReadDir(-1)
	closeErr := handle.Close()
	if err = errors.Join(readErr, closeErr); err != nil {
		return false, false, sourceFailure(err)
	}
	nonMedia := 0
	for _, entry := range entries {
		if entry.IsDir() {
			nonMedia++
			continue
		}
		if entry.Type()&os.ModeType != 0 {
			return false, false, ingestiondomain.ErrUnsafeSource
		}
		if !planned[entry.Name()] {
			nonMedia++
		}
	}
	if nonMedia*2 > len(planned) {
		return false, false, nil
	}
	kept := 0
	for _, entry := range entries {
		relative := path.Join(parent, entry.Name())
		if entry.IsDir() {
			if err = directory.stashSourceEntry(task, relative, nonMediaRoot(task.ID)); err != nil {
				return false, false, err
			}
			continue
		}
		if planned[entry.Name()] {
			kept++
			continue
		}
		if err = directory.stashSourceEntry(task, relative, nonMediaRoot(task.ID)); err != nil {
			return false, false, err
		}
	}
	return true, kept == len(planned), nil
}

func (directory *Directory) stashSourceEntry(task jobsdomain.Task, relative, nonmedia string) error {
	target := path.Join(nonmedia, relative)
	present, err := directory.existsIn(directory.media, target)
	if err != nil {
		return err
	}
	if present {
		info, err := directory.media.Lstat(target)
		if err != nil {
			return err
		}
		if !info.IsDir() {
			return ingestiondomain.ErrPublishConflict
		}
		return directory.stashChildren(task, relative, nonmedia)
	}
	if err = directory.media.MkdirAll(path.Dir(target), 0750); err != nil {
		return storageFailure(err)
	}
	return directory.moveTree(directory.input, path.Join(task.SourcePath, relative), directory.media, target)
}

// stashChildren 合并到已存在的非媒体目标目录；来源侧空壳目录不再保留。
func (directory *Directory) stashChildren(task jobsdomain.Task, relative, nonmedia string) error {
	source := path.Join(task.SourcePath, relative)
	handle, err := directory.input.Open(source)
	if err != nil {
		return sourceFailure(err)
	}
	entries, readErr := handle.ReadDir(-1)
	closeErr := handle.Close()
	if err = errors.Join(readErr, closeErr); err != nil {
		return sourceFailure(err)
	}
	for _, entry := range entries {
		if err = directory.stashSourceEntry(task, path.Join(relative, entry.Name()), nonmedia); err != nil {
			return err
		}
	}
	if err = directory.input.Remove(source); err != nil {
		return storageFailure(err)
	}
	return nil
}

// restoreNonMedia 把暂存的非媒体资料按原目录结构合并回来源。
func (directory *Directory) restoreNonMedia(task jobsdomain.Task, nonmedia string) error {
	exists, err := directory.exists(nonmedia)
	if err != nil || !exists {
		return err
	}
	if err = directory.input.MkdirAll(task.SourcePath, 0750); err != nil {
		return storageFailure(err)
	}
	if err = directory.restoreNonMediaTree(nonmedia, task.SourcePath); err != nil {
		return err
	}
	if err = directory.media.RemoveAll(nonmedia); err != nil {
		return storageFailure(err)
	}
	return nil
}

func (directory *Directory) restoreNonMediaTree(source, target string) error {
	handle, err := directory.media.Open(source)
	if err != nil {
		return storageFailure(err)
	}
	entries, readErr := handle.ReadDir(-1)
	closeErr := handle.Close()
	if err = errors.Join(readErr, closeErr); err != nil {
		return storageFailure(err)
	}
	for _, entry := range entries {
		sourceName := path.Join(source, entry.Name())
		targetName := path.Join(target, entry.Name())
		if entry.IsDir() {
			present, statErr := directory.existsIn(directory.input, targetName)
			if statErr != nil {
				return statErr
			}
			if present {
				info, statErr := directory.input.Lstat(targetName)
				if statErr != nil {
					return statErr
				}
				if !info.IsDir() {
					return ingestiondomain.ErrPublishConflict
				}
				if err = directory.restoreNonMediaTree(sourceName, targetName); err != nil {
					return err
				}
				continue
			}
			// 目标目录不存在时整目录移回，避免把非媒体目录拆成逐文件移动。
			if err = directory.input.MkdirAll(path.Dir(targetName), 0750); err != nil {
				return storageFailure(err)
			}
			if err = directory.moveTree(directory.media, sourceName, directory.input, targetName); err != nil {
				return importMoveFailure(err)
			}
			continue
		}
		present, statErr := directory.existsIn(directory.input, targetName)
		if statErr != nil {
			return statErr
		}
		if present {
			return ingestiondomain.ErrPublishConflict
		}
		if err = directory.moveTree(directory.media, sourceName, directory.input, targetName); err != nil {
			return importMoveFailure(err)
		}
	}
	return nil
}

// mediaOnly 判断目录是否只含计划内媒体；空目录视为可随整目录移动的骨架。
func mediaOnly(source *os.Root, name string, planned map[string]bool) bool {
	file, err := source.Open(name)
	if err != nil {
		return false
	}
	defer file.Close()
	entries, err := file.ReadDir(-1)
	if err != nil {
		return false
	}
	seen := 0
	for _, entry := range entries {
		if entry.IsDir() {
			if !emptyDirectory(source, path.Join(name, entry.Name())) {
				return false
			}
			continue
		}
		if !entry.Type().IsRegular() || !planned[entry.Name()] {
			return false
		}
		seen++
	}
	return seen == len(planned)
}

func emptyDirectory(root *os.Root, name string) bool {
	file, err := root.Open(name)
	if err != nil {
		return false
	}
	defer file.Close()
	entries, err := file.ReadDir(1)
	return err == io.EOF || err == nil && len(entries) == 0
}

func parentDepth(name string) int {
	if name == "." {
		return 0
	}
	return strings.Count(name, "/") + 1
}

func (directory *Directory) existsIn(root *os.Root, name string) (bool, error) {
	_, err := root.Lstat(name)
	if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	return err == nil, err
}

// moveTree 在同文件系统的两个根之间禁止覆盖地移动文件或目录，并同步两侧目录。
func (directory *Directory) moveTree(from *os.Root, name string, to *os.Root, target string) error {
	if err := safeParents(from, name); err != nil {
		return err
	}
	if err := safeParents(to, target); err != nil {
		return err
	}
	info, err := from.Lstat(name)
	if err != nil {
		return err
	}
	if !info.IsDir() && !info.Mode().IsRegular() {
		return ingestiondomain.ErrUnsafeSource
	}
	sourceParent, err := from.Open(path.Dir(name))
	if err != nil {
		return err
	}
	defer sourceParent.Close()
	targetParent, err := to.Open(path.Dir(target))
	if err != nil {
		return err
	}
	defer targetParent.Close()
	if err = relocate.NoReplace(sourceParent, targetParent, path.Base(name), path.Base(target), info); err != nil {
		return err
	}
	if err = relocate.SyncDirectory(sourceParent); err != nil {
		return err
	}
	if err = relocate.SyncDirectory(targetParent); err != nil {
		return err
	}
	if from == directory.media {
		if err = directory.syncMediaParents(path.Dir(name)); err != nil {
			return err
		}
	}
	if to == directory.media {
		if err = directory.syncMediaParents(path.Dir(target)); err != nil {
			return err
		}
	}
	if from == directory.input {
		if err = directory.syncInputParents(path.Dir(name)); err != nil {
			return err
		}
	}
	if to == directory.input {
		if err = directory.syncInputParents(path.Dir(target)); err != nil {
			return err
		}
	}
	return nil
}

func (directory *Directory) syncInputParents(name string) error {
	for {
		file, err := directory.input.Open(name)
		if err != nil {
			return err
		}
		err = relocate.SyncDirectory(file)
		file.Close()
		if err != nil {
			return err
		}
		if name == "." {
			return nil
		}
		name = path.Dir(name)
	}
}

// checkPrepared 核验仍在原路径的来源：路径身份、长度和修改时间都必须与整理计划一致。
func (directory *Directory) checkPrepared(root *os.Root, name string, file ingestiondomain.PreparedFile) error {
	return directory.checkEntry(root, name, file, true)
}

// checkMoved 核验已搬移的条目：exFAT 上单独重命名会改变文件索引，恢复时只比较路径身份、长度和修改时间。
func (directory *Directory) checkMoved(root *os.Root, name string, file ingestiondomain.PreparedFile) error {
	return directory.checkEntry(root, name, file, false)
}

func (directory *Directory) checkEntry(root *os.Root, name string, file ingestiondomain.PreparedFile, source bool) error {
	if err := safeParents(root, name); err != nil {
		return err
	}
	before, err := root.Lstat(name)
	if err != nil {
		return sourceFailure(err)
	}
	if !before.Mode().IsRegular() {
		return ingestiondomain.ErrUnsafeSource
	}
	opened, err := root.Open(name)
	if err != nil {
		return sourceFailure(err)
	}
	defer opened.Close()
	info, err := opened.Stat()
	if err != nil {
		return err
	}
	identity, err := relocate.Identity(opened)
	if err != nil {
		return err
	}
	if !os.SameFile(before, info) || !info.Mode().IsRegular() || info.Size() != file.Size || source && identity != file.Identity || info.ModTime().UnixNano() != file.Modified {
		return ingestiondomain.ErrSourceChanged
	}
	return nil
}

func (directory *Directory) inspectPrepared(ctx context.Context, name string, item *librarydomain.Media) error {
	if err := safeParents(directory.media, name); err != nil {
		return err
	}
	before, err := directory.media.Lstat(name)
	if err != nil || !before.Mode().IsRegular() {
		return ingestiondomain.ErrUnsafeSource
	}
	file, err := directory.media.Open(name)
	if err != nil {
		return storageFailure(err)
	}
	defer file.Close()
	opened, err := file.Stat()
	if err != nil || !os.SameFile(before, opened) {
		return ingestiondomain.ErrSourceChanged
	}
	item.Size = opened.Size()
	item.Modified = opened.ModTime().UnixNano()
	if item.MediaType == "image" {
		header, readErr := io.ReadAll(io.LimitReader(contextReader{ctx: ctx, reader: file}, 1<<20))
		if readErr != nil {
			return readErr
		}
		dimensions, decodeErr := mediadomain.InspectImage(header)
		if decodeErr != nil {
			return ingestiondomain.ErrImage
		}
		item.Width, item.Height = &dimensions.Width, &dimensions.Height
		item.ContentType = dimensions.ContentType
	}
	after, err := file.Stat()
	if err != nil || after.Size() != before.Size() || !after.ModTime().Equal(before.ModTime()) {
		return ingestiondomain.ErrSourceChanged
	}
	if item.MediaType == "video" {
		if directory.video == nil {
			return videoFailure(mediadomain.ErrUnavailable)
		}
		video, probeErr := directory.video.Inspect(ctx, filepath.Join(directory.media.Name(), filepath.FromSlash(name)))
		if probeErr != nil {
			return videoFailure(probeErr)
		}
		item.Video, item.Width, item.Height = &video, &video.Width, &video.Height
		item.ContentType = mediadomain.ContentType(video.Container)
	}
	return nil
}
