package filesystem

import (
	"context"
	"errors"
	"io"
	"os"
	"path"
	"path/filepath"
	"strings"

	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
)

// UploadPublisherFiles 只发布已完成分片接收的暂存文件。
// 内容完整性由接收分片时的服务端摘要保证；发布阶段不再重读整文件计算摘要。
type UploadPublisherFiles struct {
	files *Storage
	probe mediaports.Probe
}

func NewUploadPublisher(files *Storage, probe mediaports.Probe) *UploadPublisherFiles {
	return &UploadPublisherFiles{files: files, probe: probe}
}

var _ mediaports.UploadPublishStorage = (*UploadPublisherFiles)(nil)

func uploadStageFile(id string, file mediadomain.UploadPublishFile) (string, error) {
	parent, err := uploadDirectory(id)
	if err != nil || !mediadomain.UploadID(file.FileID, "uf_") {
		return "", mediadomain.ErrUploadValidation
	}
	return parent + "/" + file.FileID + ".part", nil
}

// checkSize 只核对文件存在、类型与长度；不读取内容。
func (storage *UploadPublisherFiles) checkSize(name string, file mediadomain.UploadPublishFile) error {
	if !filepath.IsLocal(name) {
		return mediadomain.ErrUploadValidation
	}
	if err := storage.files.existingParents(name); err != nil {
		return mediadomain.ErrUploadIO
	}
	info, err := storage.files.root.Lstat(filepath.FromSlash(name))
	if err != nil || !info.Mode().IsRegular() || info.Size() != file.Size {
		return mediadomain.ErrUploadChecksum
	}
	return nil
}

func (storage *UploadPublisherFiles) InspectUpload(ctx context.Context, id string, file mediadomain.UploadPublishFile) (mediadomain.Inspection, error) {
	var info mediadomain.Inspection
	name, err := uploadStageFile(id, file)
	if err != nil {
		return info, err
	}
	if err = storage.checkSize(name, file); err != nil {
		return info, err
	}
	if mediadomain.UploadMediaKind(file.Name) == "image" {
		input, err := storage.files.root.Open(filepath.FromSlash(name))
		if err != nil {
			return info, mediadomain.ErrUploadIO
		}
		info, err = mediadomain.InspectImageReader(io.LimitReader(input, 1<<20))
		input.Close()
		if err != nil {
			if !errors.Is(err, mediadomain.ErrImageInvalid) {
				return info, mediadomain.ErrUploadIO
			}
			return info, mediadomain.ErrUploadMedia
		}
	} else {
		if storage.probe == nil {
			return info, mediadomain.ErrUnavailable
		}
		probe, err := storage.probe.Probe(ctx, filepath.Join(storage.files.root.Name(), filepath.FromSlash(name)))
		if err != nil {
			return info, err
		}
		probe.Extension = strings.ToLower(path.Ext(file.Name))
		video, err := mediadomain.Analyze(probe)
		if err != nil {
			return info, err
		}
		info = mediadomain.Inspection{MediaType: "video", ContentType: mediadomain.ContentType(video.Container), Width: video.Width, Height: video.Height, Video: &video}
	}
	return info, mediadomain.ValidateInspection(info, file.Name)
}

func (storage *UploadPublisherFiles) PublishUpload(ctx context.Context, id string, file mediadomain.UploadPublishFile) error {
	if file.Info == nil {
		return mediadomain.ErrUploadMedia
	}
	if err := mediadomain.ValidateInspection(*file.Info, file.Name); err != nil {
		return err
	}
	source, err := uploadStageFile(id, file)
	if err != nil {
		return err
	}
	target := mediadomain.UploadTargetPath(file)
	if target == "" {
		return mediadomain.ErrUploadValidation
	}
	if err = storage.files.directory(path.Dir(target), 0750); err != nil {
		return mediadomain.ErrUploadIO
	}
	_, err = storage.files.root.Lstat(filepath.FromSlash(target))
	if err == nil {
		if err = storage.checkSize(target, file); err != nil {
			return err
		}
		return storage.files.syncPublishedFile(target)
	}
	if !errors.Is(err, os.ErrNotExist) {
		return mediadomain.ErrUploadIO
	}
	if err = storage.checkSize(source, file); err != nil {
		return err
	}
	if err = ctx.Err(); err != nil {
		return err
	}
	err = storage.files.publishFile(ctx, source, target, func(name string) error { return storage.checkSize(name, file) })
	if errors.Is(err, os.ErrExist) || errors.Is(err, mediadomain.ErrImageChanged) {
		return mediadomain.ErrUploadConflict
	}
	return err
}

func (storage *UploadPublisherFiles) RetireUpload(ctx context.Context, id string, newFile mediadomain.UploadPublishFile, item mediadomain.UploadRetired) (string, error) {
	if !mediadomain.UploadID(id, "us_") || (item.Kind != "hq" && item.Kind != "lq") || !filepath.IsLocal(item.Path) || !strings.HasPrefix(item.Path, item.Kind+"/") || strings.ContainsAny(item.Path, "\\:") {
		return "", mediadomain.ErrUploadMedia
	}
	if err := storage.checkSize(mediadomain.UploadTargetPath(newFile), newFile); err != nil {
		return "", err
	}
	parent := "retired/uploads/" + id
	target := parent + "/" + item.Kind + ".bin"
	if err := storage.files.directory(parent, 0700); err != nil {
		return "", err
	}
	if err := storage.files.existingParents(item.Path); err != nil {
		return "", err
	}
	_, sourceErr := storage.files.root.Lstat(filepath.FromSlash(item.Path))
	_, targetErr := storage.files.root.Lstat(filepath.FromSlash(target))
	old := mediadomain.UploadPublishFile{Size: item.Size, SHA256: item.SHA256}
	if targetErr == nil {
		if err := storage.checkSize(target, old); err != nil {
			return "", err
		}
		if sourceErr == nil {
			if err := storage.checkSize(item.Path, old); err != nil {
				return "", err
			}
			if err := storage.files.root.Remove(filepath.FromSlash(item.Path)); err != nil {
				return "", err
			}
		} else if !errors.Is(sourceErr, os.ErrNotExist) {
			return "", sourceErr
		}
	} else {
		if !errors.Is(targetErr, os.ErrNotExist) {
			return "", targetErr
		}
		if errors.Is(sourceErr, os.ErrNotExist) {
			return "absent", nil
		}
		if sourceErr != nil {
			return "", sourceErr
		}
		if err := storage.checkSize(item.Path, old); err != nil {
			return "", err
		}
		if err := ctx.Err(); err != nil {
			return "", err
		}
		if err := storage.files.publishFile(ctx, item.Path, target, func(name string) error { return storage.checkSize(name, old) }); err != nil {
			if errors.Is(err, os.ErrExist) || errors.Is(err, mediadomain.ErrImageChanged) {
				return "", mediadomain.ErrUploadConflict
			}
			return "", err
		}
	}
	retained, err := storage.files.root.OpenFile(filepath.FromSlash(target), os.O_RDWR, 0)
	if err != nil {
		return "", err
	}
	err = retained.Sync()
	retained.Close()
	if err != nil {
		return "", err
	}
	if err = storage.files.syncParents(target); err != nil {
		return "", err
	}
	if err = storage.files.syncDirectory(path.Dir(item.Path)); err != nil {
		return "", err
	}
	return "moved", nil
}

func (storage *UploadPublisherFiles) RemoveUploadStage(ctx context.Context, id string) error {
	return storage.files.removeUploadStage(ctx, id)
}
