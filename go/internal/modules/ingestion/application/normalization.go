package application

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
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

const (
	batchIncoming = "incoming"
	batchNonMedia = "nonmedia"
)

// normalize 先把来源中的非媒体文件和目录整体移出，再按章节目录批量搬移纯媒体目录。
// 非媒体资料在任务工作区暂存，导入完成后按原路径放回来源。
func (directory *Directory) normalize(ctx context.Context, task jobsdomain.Task, root string, journal *ingestiondomain.Preparation) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	work := preparationRoot(task.ID)
	incoming := work + "/" + batchIncoming
	nonmedia := work + "/" + batchNonMedia

	origin, name := directory.importSource(task, journal)
	sourceExists, err := directory.existsIn(origin, name)
	if err != nil {
		return err
	}
	incomingExists, err := directory.exists(incoming)
	if err != nil {
		return err
	}
	if !incomingExists {
		complete, completeErr := directory.normalizedComplete(ctx, root, journal)
		if completeErr != nil {
			return completeErr
		}
		if complete {
			return directory.restoreNonMedia(task, journal, nonmedia)
		}
		if !sourceExists {
			return sourceFailure(os.ErrNotExist)
		}
		conflict, conflictErr := directory.anyTargetExists(root, journal)
		if conflictErr != nil {
			return conflictErr
		}
		if conflict {
			return ingestiondomain.ErrPublishConflict
		}
		if err = directory.media.MkdirAll(work, 0750); err != nil {
			return storageFailure(err)
		}
		// 先把非媒体文件和目录移出，来源目录只留媒体后才能整目录搬移。
		if err = directory.extractSourceNonMedia(task, journal, nonmedia); err != nil {
			return err
		}
		if err = directory.moveTree(origin, name, directory.media, incoming); err != nil {
			return importMoveFailure(err)
		}
	}
	if err = directory.store.UpdateProgress(ctx, task.ID, "normalizing", len(journal.Files), 0); err != nil {
		return err
	}

	groups := map[string]*batchGroup{}
	order := []string{}
	for _, file := range journal.Files {
		parent := path.Dir(file.Source)
		group, ok := groups[parent]
		if !ok {
			group = &batchGroup{target: path.Dir(root + "/" + file.Normalized)}
			groups[parent] = group
			order = append(order, parent)
		}
		group.count++
	}
	// 先深后浅：子目录搬走后父目录才只剩媒体。
	sort.SliceStable(order, func(i, j int) bool { return parentDepth(order[i]) > parentDepth(order[j]) })
	completed := 0
	for _, parent := range order {
		if parent == "." {
			continue
		}
		if err = ctx.Err(); err != nil {
			return err
		}
		group := groups[parent]
		if err = directory.media.MkdirAll(path.Dir(group.target), 0750); err != nil {
			return storageFailure(err)
		}
		current := path.Join(incoming, parent)
		targetExists, statErr := directory.exists(group.target)
		if statErr != nil {
			return statErr
		}
		if !targetExists {
			currentExists, currentErr := directory.exists(current)
			if currentErr != nil {
				return currentErr
			}
			if !currentExists {
				return ingestiondomain.ErrSourceChanged
			}
			if err = directory.moveTree(directory.media, current, directory.media, group.target); err != nil {
				return importMoveFailure(err)
			}
		}
		completed += group.count
		if err = directory.store.UpdateProgress(ctx, task.ID, "normalizing", len(journal.Files), completed); err != nil {
			return err
		}
	}

	if rootGroup, ok := groups["."]; ok {
		if err = directory.media.MkdirAll(path.Dir(rootGroup.target), 0750); err != nil {
			return storageFailure(err)
		}
		exists, statErr := directory.exists(rootGroup.target)
		if statErr != nil {
			return statErr
		}
		if exists {
			return ingestiondomain.ErrPublishConflict
		}
		// 子目录已搬走，剩余散页随来源根目录一次移动。
		if err = directory.moveTree(directory.media, incoming, directory.media, rootGroup.target); err != nil {
			return importMoveFailure(err)
		}
	} else if err = directory.restoreSourceTree(task, journal, incoming); err != nil {
		return err
	}
	if err = directory.restoreNonMedia(task, journal, nonmedia); err != nil {
		return err
	}
	return directory.store.UpdateProgress(ctx, task.ID, "normalizing", len(journal.Files), len(journal.Files))
}

type batchGroup struct {
	target string
	count  int
}

func parentDepth(name string) int {
	if name == "." {
		return 0
	}
	return strings.Count(name, "/") + 1
}

// importSource 返回来源目录所在的根与根内名称；归档来源位于媒体根的解压临时目录。
func (directory *Directory) importSource(task jobsdomain.Task, journal *ingestiondomain.Preparation) (*os.Root, string) {
	if journal.Archive {
		return directory.media, importTempRoot(task.ID)
	}
	return directory.input, task.SourcePath
}

// extractSourceNonMedia 在搬移前遍历来源，把非媒体文件和无媒体目录整体移到任务工作区。
// 章节目录及其祖先保留，来源目录因此只剩媒体，可以整目录一次移动。
func (directory *Directory) extractSourceNonMedia(task jobsdomain.Task, journal *ingestiondomain.Preparation, nonmedia string) error {
	if journal.Archive {
		return nil
	}
	keep := map[string]bool{".": true}
	for _, file := range journal.Files {
		for current := path.Dir(file.Source); ; current = path.Dir(current) {
			keep[current] = true
			if current == "." {
				break
			}
		}
	}
	var walk func(string) error
	walk = func(relative string) error {
		handle, err := directory.input.Open(path.Join(task.SourcePath, relative))
		if err != nil {
			return sourceFailure(err)
		}
		entries, readErr := handle.ReadDir(-1)
		closeErr := handle.Close()
		if err = errors.Join(readErr, closeErr); err != nil {
			return sourceFailure(err)
		}
		for _, entry := range entries {
			child := path.Join(relative, entry.Name())
			if entry.IsDir() {
				if keep[child] {
					if err = walk(child); err != nil {
						return err
					}
					continue
				}
				if err = directory.stashSourceEntry(task, child, nonmedia); err != nil {
					return err
				}
				continue
			}
			if entry.Type()&os.ModeType != 0 {
				return ingestiondomain.ErrUnsafeSource
			}
			if _, _, ok := ingestiondomain.MediaFormat(entry.Name()); ok {
				continue
			}
			if err = directory.stashSourceEntry(task, child, nonmedia); err != nil {
				return err
			}
		}
		return nil
	}
	return walk(".")
}

func (directory *Directory) stashSourceEntry(task jobsdomain.Task, relative, nonmedia string) error {
	target := path.Join(nonmedia, relative)
	if err := directory.media.MkdirAll(path.Dir(target), 0750); err != nil {
		return storageFailure(err)
	}
	return directory.moveTree(directory.input, path.Join(task.SourcePath, relative), directory.media, target)
}

// restoreNonMedia 把暂存的非媒体文件和无媒体目录按原路径放回来源目录。
func (directory *Directory) restoreNonMedia(task jobsdomain.Task, journal *ingestiondomain.Preparation, nonmedia string) error {
	exists, err := directory.exists(nonmedia)
	if err != nil || !exists {
		return err
	}
	if journal.Archive {
		return jobsdomain.NewFailure(jobsdomain.FailureInternal, errors.New("归档导入出现非媒体资料"))
	}
	if err = directory.input.MkdirAll(task.SourcePath, 0750); err != nil {
		return storageFailure(err)
	}
	handle, err := directory.media.Open(nonmedia)
	if err != nil {
		return storageFailure(err)
	}
	entries, readErr := handle.ReadDir(-1)
	closeErr := handle.Close()
	if err = errors.Join(readErr, closeErr); err != nil {
		return storageFailure(err)
	}
	for _, entry := range entries {
		target := path.Join(task.SourcePath, entry.Name())
		present, presentErr := directory.existsIn(directory.input, target)
		if presentErr != nil {
			return presentErr
		}
		if present {
			return ingestiondomain.ErrPublishConflict
		}
		if err = directory.moveTree(directory.media, path.Join(nonmedia, entry.Name()), directory.input, target); err != nil {
			return importMoveFailure(err)
		}
	}
	if err = directory.media.RemoveAll(nonmedia); err != nil {
		return storageFailure(err)
	}
	return nil
}

// restoreSourceTree 在来源没有根散页时，把暂存中剩余的目录和资料整体放回来源。
func (directory *Directory) restoreSourceTree(task jobsdomain.Task, journal *ingestiondomain.Preparation, incoming string) error {
	exists, err := directory.exists(incoming)
	if err != nil || !exists {
		return err
	}
	if journal.Archive {
		if err = directory.media.RemoveAll(incoming); err != nil {
			return storageFailure(err)
		}
		return nil
	}
	if err = directory.input.MkdirAll(task.SourcePath, 0750); err != nil {
		return storageFailure(err)
	}
	handle, err := directory.media.Open(incoming)
	if err != nil {
		return storageFailure(err)
	}
	entries, readErr := handle.ReadDir(-1)
	closeErr := handle.Close()
	if err = errors.Join(readErr, closeErr); err != nil {
		return storageFailure(err)
	}
	for _, entry := range entries {
		target := path.Join(task.SourcePath, entry.Name())
		present, presentErr := directory.existsIn(directory.input, target)
		if presentErr != nil {
			return presentErr
		}
		if present {
			return ingestiondomain.ErrPublishConflict
		}
		if err = directory.moveTree(directory.media, path.Join(incoming, entry.Name()), directory.input, target); err != nil {
			return importMoveFailure(err)
		}
	}
	if err = directory.media.RemoveAll(incoming); err != nil {
		return storageFailure(err)
	}
	return nil
}

func (directory *Directory) normalizedComplete(ctx context.Context, root string, journal *ingestiondomain.Preparation) (bool, error) {
	for _, file := range journal.Files {
		if err := ctx.Err(); err != nil {
			return false, err
		}
		if directory.checkMoved(directory.media, root+"/"+file.Normalized, file) != nil {
			return false, nil
		}
		if file.Hash != "" && directory.checkHash(ctx, root+"/"+file.Normalized, file.Size, file.Hash) != nil {
			return false, nil
		}
	}
	return true, nil
}

func (directory *Directory) anyTargetExists(root string, journal *ingestiondomain.Preparation) (bool, error) {
	for _, file := range journal.Files {
		exists, err := directory.existsIn(directory.media, root+"/"+file.Normalized)
		if err != nil {
			return false, err
		}
		if exists {
			return true, nil
		}
	}
	return false, nil
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

// pureChapter 判断目录是否恰好只包含这些普通文件；平面目录 "." 只扫自身，不检查祖先路径。
func pureChapter(source *os.Root, name string, count int) bool {
	if name != "." && safeParents(source, name) != nil {
		return false
	}
	file, err := source.Open(name)
	if err != nil {
		return false
	}
	defer file.Close()
	entries, err := file.ReadDir(count + 1)
	if err != nil && !errors.Is(err, io.EOF) || len(entries) != count {
		return false
	}
	for _, entry := range entries {
		if !entry.Type().IsRegular() {
			return false
		}
	}
	return true
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
	if !os.SameFile(before, info) || !info.Mode().IsRegular() || info.Size() != file.Size || file.Hash == "" && (source && identity != file.Identity || info.ModTime().UnixNano() != file.Modified) {
		return ingestiondomain.ErrSourceChanged
	}
	return nil
}

func (directory *Directory) checkHash(ctx context.Context, name string, size int64, expected string) error {
	file, err := directory.media.Open(name)
	if err != nil {
		return publishedReadFailure(err)
	}
	defer file.Close()
	hash := sha256.New()
	count, err := io.CopyBuffer(hash, contextReader{ctx: ctx, reader: io.LimitReader(file, size+1)}, directory.transferBuffer())
	if err != nil {
		return publishedReadFailure(err)
	}
	if count != size || hex.EncodeToString(hash.Sum(nil)) != expected {
		return ingestiondomain.ErrPublishedChanged
	}
	return nil
}

func (directory *Directory) inspectPrepared(ctx context.Context, name string, item *librarydomain.Media, verifiedHash string) error {
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
	if verifiedHash != "" {
		item.SHA256 = verifiedHash
	} else {
		if _, err = file.Seek(0, io.SeekStart); err != nil {
			return err
		}
		hash := sha256.New()
		count, err := io.CopyBuffer(hash, contextReader{ctx: ctx, reader: io.LimitReader(file, item.Size+1)}, directory.transferBuffer())
		if err != nil {
			return err
		}
		if count != item.Size {
			return ingestiondomain.ErrSourceChanged
		}
		item.SHA256 = hex.EncodeToString(hash.Sum(nil))
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
