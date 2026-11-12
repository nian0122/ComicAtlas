package application

import (
	"archive/zip"
	"context"
	"errors"
	"io"
	"io/fs"

	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/archive"
	"comicatlas.local/backend/internal/platform/relocate"
)

func taskFailure(err error) error {
	if err == nil || jobsdomain.CodeOf(err) != jobsdomain.FailureUnknown {
		return err
	}
	switch {
	case errors.Is(err, context.Canceled), errors.Is(err, context.DeadlineExceeded):
		return jobsdomain.NewFailure(jobsdomain.FailureInterrupted, err)
	case errors.Is(err, archive.ErrMissingVolume):
		return jobsdomain.NewFailure(jobsdomain.FailureVolumeMissing, err)
	case errors.Is(err, archive.ErrInvalid), errors.Is(err, zip.ErrFormat), errors.Is(err, zip.ErrChecksum), errors.Is(err, zip.ErrAlgorithm):
		return jobsdomain.NewFailure(jobsdomain.FailureArchive, err)
	}
	return err
}

func sourceFailure(err error) error {
	if err = taskFailure(err); err == nil || jobsdomain.CodeOf(err) != jobsdomain.FailureUnknown {
		return err
	}
	code := jobsdomain.FailureSourceRead
	if errors.Is(err, fs.ErrNotExist) {
		code = jobsdomain.FailureSourceMissing
	}
	if errors.Is(err, fs.ErrPermission) {
		code = jobsdomain.FailureSourcePermission
	}
	return jobsdomain.NewFailure(code, err)
}

func archiveContentFailure(err error) error {
	if err = taskFailure(err); err == nil || jobsdomain.CodeOf(err) != jobsdomain.FailureUnknown {
		return err
	}
	return jobsdomain.NewFailure(jobsdomain.FailureArchive, err)
}

func publishedReadFailure(err error) error {
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return taskFailure(err)
	}
	code := jobsdomain.FailurePublishedRead
	if errors.Is(err, fs.ErrNotExist) {
		code = jobsdomain.FailurePublishedChanged
	}
	return jobsdomain.NewFailure(code, err)
}

func storageFailure(err error) error {
	if err = taskFailure(err); err == nil || jobsdomain.CodeOf(err) != jobsdomain.FailureUnknown {
		return err
	}
	return jobsdomain.NewFailure(jobsdomain.FailureWrite, err)
}

// importMoveFailure 区分跨文件系统与普通写入失败；导入只做目录移动，不复制媒体。
func importMoveFailure(err error) error {
	if relocate.CrossDevice(err) {
		return jobsdomain.NewFailure(jobsdomain.FailureCrossDevice, err)
	}
	return storageFailure(err)
}

func videoFailure(err error) error {
	if err == nil {
		return nil
	}
	code := jobsdomain.FailureVideoProbe
	switch {
	case errors.Is(err, context.Canceled):
		return err
	case errors.Is(err, context.DeadlineExceeded):
		code = jobsdomain.FailureVideoTimeout
	case errors.Is(err, mediadomain.ErrUnavailable):
		code = jobsdomain.FailureVideoUnavailable
	case errors.Is(err, mediadomain.ErrInvalidVideo):
		code = jobsdomain.FailureVideoInvalid
	}
	return jobsdomain.NewFailure(code, err)
}

type storageWriter struct{ writer io.Writer }

func (output storageWriter) Write(data []byte) (int, error) {
	count, err := output.writer.Write(data)
	if count != len(data) && err == nil {
		err = io.ErrShortWrite
	}
	return count, storageFailure(err)
}
