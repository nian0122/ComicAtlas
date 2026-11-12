// Package ingestion 将本地目录整理到受控存储，文件发布后再提交资料库。
package application

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"

	ingestionports "comicatlas.local/backend/internal/modules/ingestion/application/ports"
	ingestiondomain "comicatlas.local/backend/internal/modules/ingestion/domain"
	jobsapp "comicatlas.local/backend/internal/modules/jobs/application"
	jobsports "comicatlas.local/backend/internal/modules/jobs/application/ports"
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	libraryapp "comicatlas.local/backend/internal/modules/library/application"
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/natural"
)

const MaxEntries = 20000
const MaxMedia = 10000
const MaxFileBytes int64 = 20 << 30
const MaxTotalBytes int64 = 100 << 30

type Directory struct {
	hostRoot   string
	video      ingestionports.VideoInspector
	input      *os.Root
	media      *os.Root
	store      *jobsapp.Store
	library    *libraryapp.Service
	copyBuffer []byte
	cover      ingestionports.CoverGenerator
}

func (directory *Directory) SetCoverGenerator(generator ingestionports.CoverGenerator) {
	directory.cover = generator
}

func New(inputPath, mediaPath string, store *jobsapp.Store, books *libraryapp.Service, video ingestionports.VideoInspector) (*Directory, error) {
	for _, directory := range []string{inputPath, mediaPath} {
		if err := os.MkdirAll(directory, 0o750); err != nil {
			return nil, err
		}
	}
	inputPath, err := filepath.EvalSymlinks(inputPath)
	if err != nil {
		return nil, err
	}
	inputPath, err = filepath.Abs(inputPath)
	if err != nil {
		return nil, err
	}
	mediaPath, err = filepath.EvalSymlinks(mediaPath)
	if err != nil {
		return nil, err
	}
	for _, pair := range [][2]string{{inputPath, mediaPath}, {mediaPath, inputPath}} {
		relative, err := filepath.Rel(pair[0], pair[1])
		if err == nil && (relative == "." || filepath.IsLocal(relative)) {
			return nil, errors.New("导入根与媒体根必须互不包含")
		}
	}
	input, err := os.OpenRoot(inputPath)
	if err != nil {
		return nil, err
	}
	mediaPath, err = filepath.Abs(mediaPath)
	if err != nil {
		input.Close()
		return nil, err
	}
	media, err := os.OpenRoot(mediaPath)
	if err != nil {
		input.Close()
		return nil, err
	}
	for _, directory := range []string{"hq", "staging/import", "temp/import"} {
		if err = media.MkdirAll(directory, 0o750); err != nil {
			input.Close()
			media.Close()
			return nil, err
		}
	}
	return &Directory{input: input, media: media, store: store, library: books, video: video}, nil
}
func (directory *Directory) Close() error {
	return errors.Join(directory.input.Close(), directory.media.Close())
}

func (directory *Directory) transferBuffer() []byte {
	if directory.copyBuffer == nil {
		directory.copyBuffer = make([]byte, 256<<10)
	}
	return directory.copyBuffer
}

func (directory *Directory) OpenMedia(ctx context.Context, id string) (*os.File, string, error) {
	item, err := directory.library.Media(ctx, id)
	if err != nil {
		return nil, "", err
	}
	// 摘要不是阅读门禁；只按原件状态和登记路径打开，缺失或已释放仍拒绝。
	if !mediadomain.CanOpenOriginal(item.HQState) {
		return nil, "", librarydomain.ErrNotFound
	}
	file, err := directory.media.Open(item.RelativePath)
	if err != nil {
		return nil, "", err
	}
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() {
		file.Close()
		return nil, "", errors.New("媒体文件无效")
	}
	return file, item.ContentType, nil
}

// manifest 将内部路径/校验值与公开阅读模型分开序列化。

func encode(plan librarydomain.Plan) (string, error) {
	value := ingestiondomain.Manifest{Version: 2, Comic: plan.Comic, ComicInfo: plan.ComicInfo, Catalogs: plan.Catalogs, Chapters: plan.Chapters, Media: []ingestiondomain.StoredMedia{}, Progress: plan.Progress}
	for _, item := range plan.Media {
		value.Media = append(value.Media, ingestiondomain.StoredMedia{Media: item, StoragePath: item.RelativePath, Hash: item.SHA256})
	}
	data, err := json.Marshal(value)
	return string(data), err
}
func decode(data, comicID string) (librarydomain.Plan, error) {
	var value ingestiondomain.Manifest
	if err := json.Unmarshal([]byte(data), &value); err != nil {
		return librarydomain.Plan{}, jobsdomain.NewFailure(jobsdomain.FailureManifest, err)
	}
	if value.Version != 2 || value.Comic.ID != comicID || len(value.Media) == 0 || len(value.Media) > MaxMedia {
		return librarydomain.Plan{}, ingestiondomain.ErrManifest
	}
	plan := librarydomain.Plan{Comic: value.Comic, ComicInfo: value.ComicInfo, Catalogs: value.Catalogs, Chapters: value.Chapters, Media: []librarydomain.Media{}, Progress: value.Progress}
	for _, item := range value.Media {
		if !filepath.IsLocal(item.StoragePath) || !strings.HasPrefix(item.StoragePath, "hq/"+comicID+"/") || len(item.Hash) != 64 {
			return plan, ingestiondomain.ErrManifest
		}
		item.Media.RelativePath = item.StoragePath
		item.Media.SHA256 = item.Hash
		plan.Media = append(plan.Media, item.Media)
	}
	return plan, nil
}
func (directory *Directory) Run(ctx context.Context, task jobsdomain.Task) (completion jobsports.Completion, err error) {
	defer func() { err = taskFailure(err) }()
	stage := publicationRoot(task.ID)
	target := "hq/" + task.ComicID
	var plan librarydomain.Plan
	if task.Manifest != nil {
		plan, err = decode(*task.Manifest, task.ComicID)
		if err != nil {
			return completion, err
		}
		if exists, statErr := directory.exists(target); statErr != nil {
			return completion, statErr
		} else if exists {
			if err = directory.verify(ctx, plan, target); err != nil {
				return completion, err
			}
			return directory.completion(plan, task.ID), nil
		}
		if exists, statErr := directory.exists(stage); statErr != nil {
			return completion, statErr
		} else if exists {
			if err = directory.verify(ctx, plan, stage); err != nil {
				return completion, err
			}
			if err = directory.store.Publishing(ctx, task.ID, *task.Manifest); err != nil {
				return completion, err
			}
			if err = directory.publish(stage, target); err != nil {
				return completion, storageFailure(err)
			}
			return directory.completion(plan, task.ID), nil
		}
	}
	if exists, statErr := directory.exists(target); statErr != nil {
		return completion, storageFailure(statErr)
	} else if exists {
		return completion, ingestiondomain.ErrPublishConflict
	}
	if err = directory.store.ClaimImportSource(ctx, task.ID); err != nil {
		return completion, err
	}
	plan, err = directory.prepare(ctx, task, stage)
	if err != nil {
		return completion, err
	}
	data, err := encode(plan)
	if err != nil {
		return completion, err
	}
	if err = directory.store.Publishing(ctx, task.ID, data); err != nil {
		return completion, err
	}
	if err = ctx.Err(); err != nil {
		return completion, err
	}
	if err = directory.publish(stage, target); err != nil {
		return completion, storageFailure(err)
	}
	return directory.completion(plan, task.ID), nil
}
func (directory *Directory) completion(plan librarydomain.Plan, taskID string) jobsports.Completion {
	return jobsports.Completion{Persist: directory.library.PlanWriter(plan), AfterCommit: func(ctx context.Context) error {
		cleanupErr := errors.Join(directory.media.RemoveAll(importTaskRoot(taskID)), directory.media.RemoveAll(importTempRoot(taskID)))
		syncErr := errors.Join(directory.syncMediaDirectory("staging/import"), directory.syncMediaDirectory("temp/import"))
		var coverErr error
		if directory.cover != nil {
			coverErr = directory.cover.GenerateCover(ctx, plan.Comic.ID)
		}
		return errors.Join(storageFailure(cleanupErr), storageFailure(syncErr), coverErr)
	}}
}
func (directory *Directory) exists(name string) (bool, error) {
	_, err := directory.media.Lstat(name)
	if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	return err == nil, err
}

func scan(ctx context.Context, source *os.Root) ([]string, error) {
	files := []string{}
	entries := 1
	var total int64
	var walk func(string, int) error
	walk = func(name string, depth int) error {
		if err := ctx.Err(); err != nil {
			return err
		}
		if depth > 64 || len(name) > 2048 {
			return ingestiondomain.ErrLimit
		}
		before, err := source.Lstat(name)
		if err != nil {
			return err
		}
		if !before.IsDir() || before.Mode()&os.ModeSymlink != 0 {
			return ingestiondomain.ErrUnsafeSource
		}
		file, err := source.Open(name)
		if err != nil {
			return err
		}
		defer file.Close()
		opened, err := file.Stat()
		if err != nil {
			return err
		}
		if !os.SameFile(before, opened) {
			return ingestiondomain.ErrSourceChanged
		}
		for {
			children, readErr := file.ReadDir(128)
			if readErr != nil && !errors.Is(readErr, io.EOF) {
				return readErr
			}
			for _, child := range children {
				if err := ctx.Err(); err != nil {
					return err
				}
				entries++
				target := path.Join(name, child.Name())
				if entries > MaxEntries || len(target) > 2048 {
					return ingestiondomain.ErrLimit
				}
				info, err := source.Lstat(target)
				if err != nil {
					return err
				}
				if info.Mode()&os.ModeSymlink != 0 {
					return ingestiondomain.ErrUnsafeSource
				}
				if info.IsDir() {
					if err = walk(target, depth+1); err != nil {
						return err
					}
					continue
				}
				if !info.Mode().IsRegular() {
					return ingestiondomain.ErrUnsafeSource
				}
				if _, _, ok := ingestiondomain.MediaFormat(target); !ok {
					continue
				}
				if info.Size() > MaxFileBytes {
					return ingestiondomain.ErrLimit
				}
				total += info.Size()
				if total > MaxTotalBytes {
					return ingestiondomain.ErrLimit
				}
				files = append(files, target)
				if len(files) > MaxMedia {
					return ingestiondomain.ErrLimit
				}
			}
			if errors.Is(readErr, io.EOF) {
				return nil
			}
		}
	}
	if err := walk(".", 0); err != nil {
		return nil, sourceFailure(err)
	}
	if len(files) == 0 {
		return nil, ingestiondomain.ErrNoMedia
	}
	sort.Slice(files, func(i, j int) bool {
		comparison := natural.Compare(files[i], files[j])
		if comparison == 0 {
			return files[i] < files[j]
		}
		return comparison < 0
	})
	return files, nil
}

type contextReader struct {
	ctx    context.Context
	reader io.Reader
}

func (reader contextReader) Read(buffer []byte) (int, error) {
	if err := reader.ctx.Err(); err != nil {
		return 0, err
	}
	return reader.reader.Read(buffer)
}

func (directory *Directory) verify(ctx context.Context, plan librarydomain.Plan, base string) error {
	for _, item := range plan.Media {
		relative := strings.TrimPrefix(item.RelativePath, "hq/"+plan.Comic.ID+"/")
		file, err := directory.media.Open(base + "/" + relative)
		if err != nil {
			return publishedReadFailure(err)
		}
		info, err := file.Stat()
		if err != nil {
			file.Close()
			return publishedReadFailure(err)
		}
		if !info.Mode().IsRegular() || info.Size() != item.Size {
			file.Close()
			return ingestiondomain.ErrPublishedChanged
		}
		hash := sha256.New()
		_, copyErr := io.CopyBuffer(hash, contextReader{ctx: ctx, reader: io.LimitReader(file, item.Size+1)}, directory.transferBuffer())
		closeErr := file.Close()
		if err = errors.Join(copyErr, closeErr); err != nil {
			return publishedReadFailure(err)
		}
		if hex.EncodeToString(hash.Sum(nil)) != item.SHA256 {
			return ingestiondomain.ErrPublishedChanged
		}
	}
	return nil
}
