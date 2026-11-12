package filesystem

import (
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	recoverydomain "comicatlas.local/backend/internal/modules/recovery/domain"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
)

// MeasureHQ 不信任扩展名或侧车摘要，不写入、修复或删除来源。
func (files *Files) MeasureHQ(ctx context.Context, comic, name string) (result recoverydomain.Measurement, err error) {
	if err = recoverydomain.ValidateHQPath(comic, name); err != nil {
		return result, err
	}
	return files.measure(ctx, name, nil)
}

// MeasureLQ 仅读取已由侧车绑定媒体身份的精确派生路径，不枚举其他任务目录。
func (files *Files) MeasureLQ(ctx context.Context, name string) (result recoverydomain.Measurement, err error) {
	if err = recoverydomain.ValidateLQPath(name); err != nil {
		return result, err
	}
	return files.measure(ctx, name, nil)
}

func (files *Files) measure(ctx context.Context, name string, inspectVideo func(context.Context, *os.File) (mediadomain.Video, error)) (result recoverydomain.Measurement, err error) {
	defer func() {
		err = errors.Join(err, ctx.Err())
		if err != nil {
			result = recoverydomain.Measurement{}
		}
	}()
	if err = ctx.Err(); err != nil {
		return result, err
	}
	if err = files.parents(name); err != nil {
		return result, err
	}
	before, err := files.root.Lstat(name)
	if errors.Is(err, os.ErrNotExist) {
		return result, recoverydomain.ErrMissing
	}
	if err != nil {
		return result, err
	}
	if !before.Mode().IsRegular() {
		return result, recoverydomain.ErrUnsafe
	}
	if before.Size() > recoverydomain.MaxMediaBytes {
		return result, recoverydomain.ErrLimit
	}
	input, err := files.root.Open(name)
	if err != nil {
		return result, err
	}
	defer func() { err = errors.Join(err, input.Close()) }()
	opened, err := input.Stat()
	if err != nil || !os.SameFile(before, opened) || opened.Size() != before.Size() || !opened.ModTime().Equal(before.ModTime()) {
		return result, recoverydomain.ErrChanged
	}
	// 同一打开身份下读取有界图片头；未知字节仍可测摘要，但不能通过图片业务核验。
	info, inspectErr := mediadomain.InspectImageReader(io.LimitReader(&measurementReader{ctx: ctx, input: input}, 1<<20))
	if inspectErr == nil {
		result.Image = &info
	} else if !errors.Is(inspectErr, mediadomain.ErrImageInvalid) {
		return result, inspectErr
	}
	if _, err = input.Seek(0, io.SeekStart); err != nil {
		return result, err
	}
	digest := sha256.New()
	// 长度受开始时文件大小约束，持续增长的文件也不能无限读取。
	reader := io.LimitReader(input, before.Size()+1)
	buffer := make([]byte, 64<<10)
	for {
		if err = ctx.Err(); err != nil {
			return result, err
		}
		n, readErr := reader.Read(buffer)
		result.Size += int64(n)
		if result.Size > before.Size() {
			return result, recoverydomain.ErrChanged
		}
		_, _ = digest.Write(buffer[:n])
		if errors.Is(readErr, io.EOF) {
			break
		}
		if readErr != nil {
			return result, readErr
		}
	}
	if inspectVideo != nil && result.Image == nil {
		video, inspectErr := inspectVideo(ctx, input)
		if inspectErr != nil {
			return result, inspectErr
		}
		result.Video = &video
	}
	after, statErr := input.Stat()
	current, currentErr := files.root.Lstat(name)
	if statErr != nil || currentErr != nil || !os.SameFile(before, current) || !os.SameFile(before, after) || result.Size != before.Size() || after.Size() != before.Size() || current.Size() != before.Size() || !after.ModTime().Equal(before.ModTime()) || !current.ModTime().Equal(before.ModTime()) {
		return result, recoverydomain.ErrChanged
	}
	if err = files.parents(name); err != nil {
		return result, err
	}
	result.Path, result.SHA256 = name, hex.EncodeToString(digest.Sum(nil))
	if files.observe != nil {
		err = files.observe(name, before)
	}
	return result, nil
}

type measurementReader struct {
	ctx   context.Context
	input io.Reader
}

func (reader *measurementReader) Read(buffer []byte) (int, error) {
	if err := reader.ctx.Err(); err != nil {
		return 0, err
	}
	return reader.input.Read(buffer)
}
