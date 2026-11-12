package imaging

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"image"
	_ "image/gif"
	_ "image/jpeg"
	"image/png"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	mediaports "comicatlas.local/backend/internal/modules/media/application/ports"
	mediadomain "comicatlas.local/backend/internal/modules/media/domain"
	"comicatlas.local/backend/internal/platform/diskspace"
	"comicatlas.local/backend/internal/platform/process"
	_ "golang.org/x/image/bmp"
	"golang.org/x/image/webp"
)

type executor interface {
	Run(context.Context, process.Request) (process.Result, error)
}
type Renderer struct {
	runner       executor
	cwebp, djpeg string
	pixels       int64
	space        func(string) (diskspace.Snapshot, error)
}

func New(runner *process.Runner, cwebp, djpeg string, pixels int64) *Renderer {
	return &Renderer{runner: runner, cwebp: cwebp, djpeg: djpeg, pixels: pixels, space: diskspace.Measure}
}

var _ mediaports.ImageRenderer = (*Renderer)(nil)

func (renderer *Renderer) Render(ctx context.Context, request mediadomain.ImageRequest) (artifact mediadomain.ImageArtifact, err error) {
	if !filepath.IsAbs(request.SourcePath) || !filepath.IsAbs(request.WorkRoot) || request.SourceSHA256 != "" && len(request.SourceSHA256) != 64 {
		return artifact, mediadomain.ErrImageInvalid
	}
	if _, decodeErr := hex.DecodeString(request.SourceSHA256); decodeErr != nil {
		return artifact, mediadomain.ErrImageInvalid
	}
	input, err := os.Open(request.SourcePath)
	if err != nil {
		return artifact, mediadomain.ErrImageIO
	}
	defer input.Close()
	before, err := input.Stat()
	linked, linkErr := os.Lstat(request.SourcePath)
	if err != nil || linkErr != nil || !before.Mode().IsRegular() || !linked.Mode().IsRegular() || !os.SameFile(before, linked) || before.Size() > mediadomain.MaxImageInputBytes {
		return artifact, mediadomain.ErrImageInvalid
	}
	prefix, err := io.ReadAll(io.LimitReader(contextReader{ctx: ctx, reader: input}, 1<<20))
	if err != nil {
		return artifact, err
	}
	config, format, err := image.DecodeConfig(bytes.NewReader(prefix))
	if err != nil {
		return artifact, mediadomain.ErrImageInvalid
	}
	orientation := 1
	if format == "jpeg" {
		orientation = jpegOrientation(prefix)
	}
	plan, err := mediadomain.PlanImage(mediadomain.ImageHeader{Width: config.Width, Height: config.Height, Format: format, Orientation: orientation}, request.Spec, renderer.pixels)
	if err != nil {
		return artifact, err
	}
	digest, err := sourceDigest(ctx, input)
	if err != nil {
		return artifact, err
	}
	if request.SourceSHA256 != "" && !strings.EqualFold(digest, request.SourceSHA256) {
		return artifact, mediadomain.ErrImageChanged
	}
	if renderer.cwebp == "" || !filepath.IsAbs(renderer.cwebp) || (plan.JPEGScale < 8 && (renderer.djpeg == "" || !filepath.IsAbs(renderer.djpeg))) {
		return artifact, mediadomain.ErrImageUnavailable
	}
	workspaceBytes, err := imageWorkspaceBytes(plan, format, orientation, prefix)
	if err != nil {
		return artifact, err
	}
	space := renderer.space
	if space == nil {
		space = diskspace.Measure
	}
	if err = requireWorkspaceSpace(space, request.WorkRoot, workspaceBytes); err != nil {
		return artifact, mediadomain.ErrImageIO
	}
	workspace, err := os.MkdirTemp(request.WorkRoot, "image-")
	if err != nil {
		return artifact, mediadomain.ErrImageIO
	}
	keep := false
	defer func() {
		if !keep {
			if cleanupErr := os.RemoveAll(workspace); cleanupErr != nil {
				err = errors.Join(err, mediadomain.ErrImageIO)
			}
		}
	}()
	decodedPath := request.SourcePath
	if plan.JPEGScale < 8 {
		decodedPath = filepath.Join(workspace, "scaled.ppm")
		if err = renderer.execute(ctx, renderer.djpeg, []string{"-maxmemory", "128m", "-scale", strconv.Itoa(plan.JPEGScale) + "/8", "-outfile", decodedPath, request.SourcePath}); err != nil {
			return artifact, err
		}
		if format == "jpeg" && orientation == 1 {
			if err = validatePPM(decodedPath, plan.DecodeWidth, plan.DecodeHeight); err != nil {
				return artifact, mediadomain.ErrImageInvalid
			}
		}
	}
	normalized := filepath.Join(workspace, "normalized.png")
	encodedSource := normalized
	if (format == "png" && isStaticPNG(prefix)) || (format == "webp" && isStaticWebP(prefix)) {
		// cwebp 可直接读取静态 PNG/WebP 并缩放，避免 Go 再解码、复制整幅像素并写中间 PNG。
		encodedSource = request.SourcePath
	} else if format == "jpeg" && plan.JPEGScale < 8 && orientation == 1 {
		// djpeg 已按预算缩小；cwebp 可直接读取 PNM，跳过 Go 整帧解码和中间 PNG。
		encodedSource = decodedPath
	} else if err = normalize(ctx, decodedPath, normalized, plan, orientation); err != nil {
		return artifact, err
	}
	outputBytes, err := imageOutputBytes(plan)
	if err != nil {
		return artifact, err
	}
	if err = requireWorkspaceSpace(space, request.WorkRoot, outputBytes); err != nil {
		return artifact, mediadomain.ErrImageIO
	}
	output := filepath.Join(workspace, "image.webp")
	if err = renderer.execute(ctx, renderer.cwebp, []string{"-quiet", "-q", strconv.Itoa(request.Spec.Quality), "-m", "4", "-low_memory", "-metadata", "none", "-resize", strconv.Itoa(plan.Width), strconv.Itoa(plan.Height), "-o", output, "--", encodedSource}); err != nil {
		return artifact, err
	}
	after, err := input.Stat()
	current, currentErr := os.Lstat(request.SourcePath)
	if err != nil || currentErr != nil || !os.SameFile(before, current) || before.Size() != after.Size() || !before.ModTime().Equal(after.ModTime()) {
		return artifact, mediadomain.ErrImageChanged
	}
	finalDigest, err := sourceDigest(ctx, input)
	if err != nil {
		return artifact, err
	}
	if finalDigest != digest {
		return artifact, mediadomain.ErrImageChanged
	}
	artifact, err = inspectArtifact(ctx, output, workspace, plan)
	if err != nil {
		return artifact, err
	}
	artifact.SourceSHA256 = digest
	if request.SourceSHA256 != "" {
		artifact.SourceSHA256 = request.SourceSHA256
	}
	// 临时中间文件不随产物保留，发布者只需处理一个 WebP。
	for _, name := range []string{normalized, filepath.Join(workspace, "scaled.ppm")} {
		if removeErr := os.Remove(name); removeErr != nil && !errors.Is(removeErr, os.ErrNotExist) {
			return mediadomain.ImageArtifact{}, mediadomain.ErrImageIO
		}
	}
	keep = true
	return artifact, nil
}

func requireWorkspaceSpace(measure func(string) (diskspace.Snapshot, error), path string, required int64) error {
	if required < 0 {
		return mediadomain.ErrImageLimit
	}
	capacity, err := measure(path)
	if err != nil || capacity.Total < 0 || capacity.Available < 0 || capacity.Available > capacity.Total || capacity.Available < required {
		return mediadomain.ErrImageIO
	}
	return nil
}

// imageWorkspaceBytes 在开辟暂存目录和启动编码工具前，按像素估算产物与中间文件所需空间。
func imageWorkspaceBytes(plan mediadomain.ImagePlan, format string, orientation int, prefix []byte) (int64, error) {
	budget, err := imageOutputBytes(plan)
	if err != nil {
		return 0, err
	}
	intermediateBytesPerPixel := int64(0)
	switch {
	case format == "png" && isStaticPNG(prefix), format == "webp" && isStaticWebP(prefix):
		// 静态 PNG/WebP 直通 cwebp，不创建中间媒体文件。
	case format == "jpeg" && plan.JPEGScale < 8 && orientation == 1:
		intermediateBytesPerPixel = 3 // djpeg 的 PPM 暂存。
	default:
		intermediateBytesPerPixel = 4 // Go 规范化 PNG 的像素上界。
	}
	if intermediateBytesPerPixel == 0 {
		return budget, nil
	}
	intermediateBytes, err := imageFileBytes(plan.DecodeWidth, plan.DecodeHeight, intermediateBytesPerPixel)
	if err != nil {
		return 0, err
	}
	maxInt64 := int64(^uint64(0) >> 1)
	if budget > maxInt64-intermediateBytes {
		return 0, mediadomain.ErrImageLimit
	}
	return budget + intermediateBytes, nil
}

func imageOutputBytes(plan mediadomain.ImagePlan) (int64, error) {
	bytes, err := imageFileBytes(plan.Width, plan.Height, 4)
	if err != nil {
		return 0, err
	}
	return min(bytes, mediadomain.MaxImageOutputBytes), nil
}

func imageFileBytes(width, height int, bytesPerPixel int64) (int64, error) {
	const overhead int64 = 64 << 10
	maxInt64 := int64(^uint64(0) >> 1)
	w, h := int64(width), int64(height)
	if w < 1 || h < 1 || bytesPerPixel < 1 || w > maxInt64/h {
		return 0, mediadomain.ErrImageLimit
	}
	pixels := w * h
	if pixels > (maxInt64-overhead)/bytesPerPixel {
		return 0, mediadomain.ErrImageLimit
	}
	return pixels*bytesPerPixel + overhead, nil
}

func (renderer *Renderer) execute(ctx context.Context, executable string, args []string) error {
	_, err := renderer.runner.Run(ctx, process.Request{Executable: executable, Args: args, Timeout: 2 * time.Minute})
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return err
	}
	if errors.Is(err, process.ErrStart) {
		return mediadomain.ErrImageUnavailable
	}
	if err != nil {
		return mediadomain.ErrImageProcess
	}
	return nil
}

func normalize(ctx context.Context, source, target string, plan mediadomain.ImagePlan, orientation int) error {
	input, err := os.Open(source)
	if err != nil {
		return mediadomain.ErrImageIO
	}
	defer input.Close()
	config, _, err := image.DecodeConfig(io.LimitReader(contextReader{ctx: ctx, reader: input}, 1<<20))
	if err != nil || config.Width != plan.DecodeWidth || config.Height != plan.DecodeHeight {
		return mediadomain.ErrImageInvalid
	}
	if _, err = input.Seek(0, io.SeekStart); err != nil {
		return mediadomain.ErrImageIO
	}
	decoded, _, err := image.Decode(contextReader{ctx: ctx, reader: input})
	if err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return mediadomain.ErrImageInvalid
	}
	if decoded.Bounds().Dx() != config.Width || decoded.Bounds().Dy() != config.Height {
		return mediadomain.ErrImageInvalid
	}
	output, err := os.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return mediadomain.ErrImageIO
	}
	encoder := png.Encoder{CompressionLevel: png.BestSpeed}
	encodeErr := encoder.Encode(contextWriter{ctx: ctx, writer: output}, oriented{source: decoded, orientation: orientation})
	closeErr := output.Close()
	if ctx.Err() != nil {
		return ctx.Err()
	}
	if encodeErr != nil || closeErr != nil {
		return mediadomain.ErrImageIO
	}
	return nil
}

func sourceDigest(ctx context.Context, file *os.File) (string, error) {
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return "", mediadomain.ErrImageIO
	}
	hash := sha256.New()
	count, err := io.CopyBuffer(hash, contextReader{ctx: ctx, reader: io.LimitReader(file, mediadomain.MaxImageInputBytes+1)}, make([]byte, 64<<10))
	if err != nil {
		if ctx.Err() != nil {
			return "", ctx.Err()
		}
		return "", mediadomain.ErrImageIO
	}
	if count > mediadomain.MaxImageInputBytes {
		return "", mediadomain.ErrImageChanged
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}

func inspectArtifact(ctx context.Context, path, workspace string, plan mediadomain.ImagePlan) (mediadomain.ImageArtifact, error) {
	file, err := os.Open(path)
	if err != nil {
		return mediadomain.ImageArtifact{}, mediadomain.ErrImageOutput
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Size() <= 0 || info.Size() > mediadomain.MaxImageOutputBytes {
		return mediadomain.ImageArtifact{}, mediadomain.ErrImageOutput
	}
	if err = validateRIFF(file, info.Size()); err != nil {
		return mediadomain.ImageArtifact{}, mediadomain.ErrImageOutput
	}
	config, err := webp.DecodeConfig(io.LimitReader(file, 1<<20))
	if err != nil || config.Width != plan.Width || config.Height != plan.Height {
		return mediadomain.ImageArtifact{}, mediadomain.ErrImageOutput
	}
	if _, err = file.Seek(0, io.SeekStart); err != nil {
		return mediadomain.ImageArtifact{}, mediadomain.ErrImageIO
	}
	hash := sha256.New()
	count, err := io.CopyBuffer(hash, contextReader{ctx: ctx, reader: file}, make([]byte, 64<<10))
	if err != nil || count != info.Size() {
		if ctx.Err() != nil {
			return mediadomain.ImageArtifact{}, ctx.Err()
		}
		return mediadomain.ImageArtifact{}, mediadomain.ErrImageOutput
	}
	if err = ctx.Err(); err != nil {
		return mediadomain.ImageArtifact{}, err
	}
	return mediadomain.ImageArtifact{Path: path, TemporaryDirectory: workspace, Width: config.Width, Height: config.Height, Size: info.Size(), SHA256: hex.EncodeToString(hash.Sum(nil)), JPEGScale: plan.JPEGScale}, nil
}

// 校验完整容器长度与块边界；像素编码由受信任的 cwebp 生成，不再分配整帧重复解码。
func validateRIFF(file *os.File, size int64) error {
	var header [12]byte
	if _, err := io.ReadFull(file, header[:]); err != nil || string(header[:4]) != "RIFF" || string(header[8:]) != "WEBP" || int64(binary.LittleEndian.Uint32(header[4:8]))+8 != size {
		return mediadomain.ErrImageOutput
	}
	position := int64(12)
	for position < size {
		var chunk [8]byte
		if _, err := io.ReadFull(file, chunk[:]); err != nil {
			return mediadomain.ErrImageOutput
		}
		length := int64(binary.LittleEndian.Uint32(chunk[4:]))
		padded := length + (length & 1)
		if padded > size-position-8 {
			return mediadomain.ErrImageOutput
		}
		position += 8 + padded
		if _, err := file.Seek(padded, io.SeekCurrent); err != nil {
			return err
		}
	}
	_, err := file.Seek(0, io.SeekStart)
	return err
}

type contextReader struct {
	ctx    context.Context
	reader io.Reader
}

func (value contextReader) Read(data []byte) (int, error) {
	if err := value.ctx.Err(); err != nil {
		return 0, err
	}
	return value.reader.Read(data)
}

type contextWriter struct {
	ctx    context.Context
	writer io.Writer
}

func (value contextWriter) Write(data []byte) (int, error) {
	if err := value.ctx.Err(); err != nil {
		return 0, err
	}
	return value.writer.Write(data)
}
