package filesystem

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"image"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"

	aiports "comicatlas.local/backend/internal/modules/ai/application/ports"
	aidomain "comicatlas.local/backend/internal/modules/ai/domain"
	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	_ "golang.org/x/image/webp"
)

type Preparer struct {
	path   string
	images mediaports.ImageRenderer
	permit chan struct{}
}

func New(path string, images mediaports.ImageRenderer) (*Preparer, error) {
	if images == nil {
		return nil, aidomain.ErrValidation
	}
	path, err := filepath.Abs(path)
	if err != nil {
		return nil, aidomain.ErrPreparation
	}
	path, err = filepath.EvalSymlinks(path)
	if err != nil {
		return nil, aidomain.ErrPreparation
	}
	root, err := os.OpenRoot(path)
	if err != nil {
		return nil, aidomain.ErrPreparation
	}
	defer root.Close()
	if err = directory(root, "staging/ai"); err != nil {
		return nil, err
	}
	return &Preparer{path: path, images: images, permit: make(chan struct{}, 1)}, nil
}

var _ aiports.SamplePreparer = (*Preparer)(nil)

func (preparer *Preparer) Prepare(ctx context.Context, images []aidomain.SourceImage) (result aiports.PreparedSamples, err error) {
	if len(images) == 0 || len(images) > aidomain.MaxSamples {
		return nil, aidomain.ErrValidation
	}
	seen := map[string]bool{}
	previous := 0
	for _, image := range images {
		if aidomain.ValidateSource(image) != nil || seen[image.MediaID] || image.Ordinal <= previous {
			return nil, aidomain.ErrSource
		}
		seen[image.MediaID], previous = true, image.Ordinal
	}
	if err = ctx.Err(); err != nil {
		return nil, err
	}
	select {
	case preparer.permit <- struct{}{}:
	default:
		return nil, aidomain.ErrBusy
	}
	owned := false
	defer func() {
		if !owned {
			<-preparer.permit
		}
	}()
	base, err := os.OpenRoot(preparer.path)
	if err != nil {
		return nil, aidomain.ErrPreparation
	}
	stage := "staging/ai/" + rand.Text()
	if err = directory(base, "staging/ai"); err != nil {
		base.Close()
		return nil, err
	}
	if err = base.Mkdir(stage, 0700); err != nil {
		base.Close()
		return nil, aidomain.ErrPreparation
	}
	root, err := base.OpenRoot(stage)
	if err != nil {
		base.RemoveAll(stage)
		base.Close()
		return nil, aidomain.ErrPreparation
	}
	set := &sampleSet{base: base, root: root, stage: stage, permit: preparer.permit, paths: map[string]string{}, files: map[*os.File]bool{}}
	owned = true
	defer func() {
		if err != nil {
			err = errors.Join(err, set.Close())
		}
	}()
	for index, source := range images {
		if err = ctx.Err(); err != nil {
			return nil, err
		}
		sample, name, prepareErr := preparer.prepareImage(ctx, set, source, index)
		if prepareErr != nil {
			return nil, prepareErr
		}
		set.samples = append(set.samples, sample)
		set.paths[source.MediaID] = name
	}
	if err = ctx.Err(); err != nil {
		return nil, err
	}
	return set, nil
}

// 每次只保留一个编码工作区；复杂图片按固定档位降低尺寸和质量，不能突破单图预算。
func (preparer *Preparer) prepareImage(ctx context.Context, set *sampleSet, source aidomain.SourceImage, index int) (aidomain.Sample, string, error) {
	var empty aidomain.Sample
	if err := regularPath(set.base, source.Path); err != nil {
		return empty, "", aidomain.ErrSource
	}
	name := fmt.Sprintf("sample_%02d.webp", index+1)
	for _, spec := range aidomain.PreparationSpecs() {
		if err := ctx.Err(); err != nil {
			return empty, "", err
		}
		if err := set.root.Mkdir("render", 0700); err != nil {
			return empty, "", aidomain.ErrPreparation
		}
		artifact, err := preparer.images.Render(ctx, mediadomain.ImageRequest{SourcePath: filepath.Join(set.base.Name(), filepath.FromSlash(source.Path)), SourceSHA256: source.SHA256, WorkRoot: filepath.Join(set.root.Name(), "render"), Spec: spec})
		if err != nil {
			if ctx.Err() != nil {
				return empty, "", ctx.Err()
			}
			return empty, "", err
		}
		relative, err := filepath.Rel(set.root.Name(), artifact.Path)
		if err != nil || !filepath.IsLocal(relative) || !strings.HasPrefix(filepath.ToSlash(relative), "render/") {
			return empty, "", aidomain.ErrPreparation
		}
		sample, tooLarge, err := inspect(ctx, set.root, relative, source, spec)
		if err != nil {
			return empty, "", err
		}
		if !tooLarge {
			if err = set.root.Rename(relative, name); err != nil {
				return empty, "", aidomain.ErrPreparation
			}
			if err = set.root.RemoveAll("render"); err != nil {
				return empty, "", aidomain.ErrPreparation
			}
			return sample, name, nil
		}
		if err = set.root.RemoveAll("render"); err != nil {
			return empty, "", aidomain.ErrPreparation
		}
	}
	return empty, "", aidomain.ErrValidation
}

func inspect(ctx context.Context, root *os.Root, name string, source aidomain.SourceImage, spec mediadomain.ImageSpec) (value aidomain.Sample, tooLarge bool, err error) {
	if regularPath(root, filepath.ToSlash(name)) != nil {
		return value, false, aidomain.ErrPreparation
	}
	file, err := root.Open(name)
	if err != nil {
		return value, false, aidomain.ErrPreparation
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Size() < 1 {
		return value, false, aidomain.ErrPreparation
	}
	if info.Size() > aidomain.MaxPageBytes {
		return value, true, nil
	}
	header, format, err := image.DecodeConfig(io.LimitReader(file, aidomain.MaxPageBytes+1))
	if err != nil || format != "webp" || header.Width < 1 || header.Height < 1 || max(header.Width, header.Height) > spec.MaxLongEdge {
		return value, false, aidomain.ErrPreparation
	}
	if _, err = file.Seek(0, io.SeekStart); err != nil {
		return value, false, aidomain.ErrPreparation
	}
	digest := sha256.New()
	count, err := io.CopyBuffer(digest, io.LimitReader(contextReader{ctx: ctx, reader: file}, aidomain.MaxPageBytes+1), make([]byte, 64<<10))
	if err != nil {
		return value, false, err
	}
	if count != info.Size() || count > aidomain.MaxPageBytes {
		return value, false, aidomain.ErrPreparation
	}
	return aidomain.Sample{MediaID: source.MediaID, Ordinal: source.Ordinal, SHA256: hex.EncodeToString(digest.Sum(nil)), Size: count, Width: header.Width, Height: header.Height, ContentType: "image/webp"}, false, nil
}

func directory(root *os.Root, name string) error {
	prefix := ""
	for _, segment := range strings.Split(name, "/") {
		prefix += segment
		info, err := root.Lstat(filepath.FromSlash(prefix))
		if errors.Is(err, os.ErrNotExist) {
			if err = root.Mkdir(filepath.FromSlash(prefix), 0700); err != nil {
				return aidomain.ErrPreparation
			}
		} else if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
			return aidomain.ErrPreparation
		}
		prefix += "/"
	}
	return nil
}

func regularPath(root *os.Root, name string) error {
	if !filepath.IsLocal(name) || strings.Contains(name, "\\") {
		return aidomain.ErrSource
	}
	parts := strings.Split(name, "/")
	for index := range parts {
		info, err := root.Lstat(filepath.FromSlash(strings.Join(parts[:index+1], "/")))
		if err != nil || info.Mode()&os.ModeSymlink != 0 {
			return aidomain.ErrSource
		}
		if index < len(parts)-1 && !info.IsDir() || index == len(parts)-1 && !info.Mode().IsRegular() {
			return aidomain.ErrSource
		}
	}
	return nil
}

type contextReader struct {
	ctx    context.Context
	reader io.Reader
}

func (reader contextReader) Read(data []byte) (int, error) {
	if err := reader.ctx.Err(); err != nil {
		return 0, err
	}
	return reader.reader.Read(data)
}

type sampleSet struct {
	mutex      sync.Mutex
	base, root *os.Root
	stage      string
	permit     chan struct{}
	samples    []aidomain.Sample
	paths      map[string]string
	files      map[*os.File]bool
	closed     bool
}

func (set *sampleSet) Samples() []aidomain.Sample {
	return append([]aidomain.Sample(nil), set.samples...)
}

func (set *sampleSet) OpenSample(ctx context.Context, id string) (io.ReadCloser, error) {
	set.mutex.Lock()
	defer set.mutex.Unlock()
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	name, ok := set.paths[id]
	if set.closed || !ok {
		return nil, aidomain.ErrSource
	}
	if len(set.files) != 0 {
		return nil, aidomain.ErrBusy
	}
	if regularPath(set.root, name) != nil {
		return nil, aidomain.ErrSource
	}
	file, err := set.root.Open(name)
	if err != nil {
		return nil, aidomain.ErrSource
	}
	set.files[file] = true
	return &sampleFile{File: file, set: set}, nil
}

func (set *sampleSet) Close() error {
	set.mutex.Lock()
	defer set.mutex.Unlock()
	if set.closed {
		return nil
	}
	set.closed = true
	var err error
	for file := range set.files {
		err = errors.Join(err, file.Close())
		delete(set.files, file)
	}
	err = errors.Join(err, set.root.Close(), set.base.RemoveAll(set.stage), set.base.Close())
	<-set.permit
	if err != nil {
		return aidomain.ErrPreparation
	}
	return nil
}

type sampleFile struct {
	*os.File
	set *sampleSet
}

func (file *sampleFile) Close() error {
	file.set.mutex.Lock()
	defer file.set.mutex.Unlock()
	if !file.set.files[file.File] {
		return nil
	}
	delete(file.set.files, file.File)
	return file.File.Close()
}
