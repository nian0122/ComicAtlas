package imaging

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"image"
	"image/color"
	_ "image/gif"
	_ "image/jpeg"
	"image/png"
	"io"
	"os"
	"path/filepath"
	"strconv"
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
	runner  executor
	cwebp   string
	djpeg   string
	pixels  int64
	scratch string
	space   func(string) (diskspace.Snapshot, error)
	budget  *pixelBudget
}

// New 中的 scratch 只保存可重建的解码中间文件，正式产物仍写入调用者的媒体暂存根。
// 解码预算按像素权重共享，容量至少容纳单张上限，避免大图永远等不到全部权重。
func New(runner executor, cwebp, djpeg string, pixels int64, scratch string, concurrency int) *Renderer {
	if pixels < 1 {
		pixels = mediadomain.DefaultImagePixels
	}
	units := max(int64(concurrency), (mediadomain.MaxImageDecodePixels+pixels-1)/pixels)
	return &Renderer{runner: runner, cwebp: cwebp, djpeg: djpeg, pixels: pixels, scratch: scratch, space: diskspace.Measure, budget: newPixelBudget(units)}
}

var _ mediaports.ImageRenderer = (*Renderer)(nil)

const (
	sourceCopyMinBytes int64 = 1 << 20
	sourceCopyMaxBytes int64 = 64 << 20
)

// copySourceFile 把原件整块复制到快速暂存，避免解码工具在慢卷上做小缓冲读取。
func copySourceFile(ctx context.Context, source *os.File, target string, expected int64) error {
	if _, err := source.Seek(0, io.SeekStart); err != nil {
		return mediadomain.ErrImageIO
	}
	file, err := os.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0600)
	if err != nil {
		return mediadomain.ErrImageIO
	}
	written, copyErr := io.CopyBuffer(contextWriter{ctx: ctx, writer: file}, contextReader{ctx: ctx, reader: source}, make([]byte, 1<<20))
	closeErr := file.Close()
	if ctx.Err() != nil {
		return ctx.Err()
	}
	if copyErr != nil || closeErr != nil {
		return mediadomain.ErrImageIO
	}
	if written != expected {
		return mediadomain.ErrImageChanged
	}
	return nil
}

func (renderer *Renderer) Render(ctx context.Context, request mediadomain.ImageRequest) (artifact mediadomain.ImageArtifact, err error) {
	if !filepath.IsAbs(request.SourcePath) || !filepath.IsAbs(request.WorkRoot) || !filepath.IsAbs(renderer.scratch) {
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
	header := mediadomain.ImageHeader{
		Width: config.Width, Height: config.Height, Format: format, Orientation: orientation,
		SimpleLossyWebP: format == "webp" && isSimpleLossyWebP(prefix, before.Size()),
	}
	plan, err := mediadomain.PlanImage(header, request.Spec)
	if err != nil {
		return artifact, err
	}
	units := (int64(plan.DecodeWidth)*int64(plan.DecodeHeight) + renderer.pixels - 1) / renderer.pixels
	release, err := renderer.budget.acquire(ctx, units)
	if err != nil {
		return artifact, err
	}
	defer release()
	if !filepath.IsAbs(renderer.cwebp) || (format == "jpeg" && !filepath.IsAbs(renderer.djpeg)) {
		return artifact, mediadomain.ErrImageUnavailable
	}
	mode := selectInputMode(plan, format, orientation, prefix)
	intermediateBytes, err := imageIntermediateBytes(plan, mode)
	if err != nil {
		return artifact, err
	}
	// 媒体卷上的小缓冲读取远慢于本地暂存；大原件先整块复制再交给解码工具。
	copySource := before.Size() >= sourceCopyMinBytes && before.Size() <= sourceCopyMaxBytes
	requiredBytes := intermediateBytes
	if copySource {
		requiredBytes += before.Size()
	}
	space := renderer.space
	if space == nil {
		space = diskspace.Measure
	}
	if err = os.MkdirAll(renderer.scratch, 0750); err != nil {
		return artifact, mediadomain.ErrImageIO
	}
	if err = requireWorkspaceSpace(space, renderer.scratch, requiredBytes); err != nil {
		return artifact, mediadomain.ErrImageIO
	}
	scratch, err := os.MkdirTemp(renderer.scratch, "image-")
	if err != nil {
		return artifact, mediadomain.ErrImageIO
	}
	defer func() {
		if cleanupErr := os.RemoveAll(scratch); cleanupErr != nil {
			err = errors.Join(err, mediadomain.ErrImageIO)
		}
	}()
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
	toolSource := request.SourcePath
	if copySource {
		toolSource = filepath.Join(scratch, "source")
		if err = copySourceFile(ctx, input, toolSource, before.Size()); err != nil {
			return artifact, err
		}
		afterCopy, statErr := input.Stat()
		if statErr != nil || !os.SameFile(before, afterCopy) || afterCopy.Size() != before.Size() || !afterCopy.ModTime().Equal(before.ModTime()) {
			return artifact, mediadomain.ErrImageChanged
		}
	}
	decodedPath := filepath.Join(scratch, "decoded")
	normalized := filepath.Join(scratch, "normalized.png")
	encodedSource := toolSource
	switch mode {
	case jpegPPM, orientedJPEG:
		outputFormat := "-pnm"
		if mode == orientedJPEG {
			outputFormat = "-bmp"
		}
		args := []string{"-maxmemory", "192m", "-scale", strconv.Itoa(plan.JPEGScale) + "/8", outputFormat}
		// 灰度图强制输出三通道；CMYK 使用 djpeg 的输出颜色转换。
		if config.ColorModel == color.GrayModel {
			args = append(args, "-rgb")
		}
		args = append(args, "-outfile", decodedPath, toolSource)
		if err = renderer.execute(ctx, scratch, renderer.djpeg, args); err != nil {
			return artifact, err
		}
		encodedSource = decodedPath
	case webpPPM:
		decoder := filepath.Join(filepath.Dir(renderer.cwebp), "dwebp"+filepath.Ext(renderer.cwebp))
		args := []string{"-resize", strconv.Itoa(plan.Width), strconv.Itoa(plan.Height), "-ppm", "-o", decodedPath, "--", toolSource}
		if err = renderer.execute(ctx, scratch, decoder, args); err != nil {
			return artifact, err
		}
		encodedSource = decodedPath
	}
	switch mode {
	case jpegPPM, webpPPM:
		if err = validatePPM(encodedSource, plan.DecodeWidth, plan.DecodeHeight); err != nil {
			if mode == jpegPPM {
				return artifact, mediadomain.ErrImageInvalid
			}
			return artifact, mediadomain.ErrImageOutput
		}
	case orientedJPEG, normalizedInput:
		if err = normalize(ctx, encodedSource, normalized, plan, orientation); err != nil {
			return artifact, err
		}
		encodedSource = normalized
	}
	outputBytes, err := imageOutputBytes(plan)
	if err != nil {
		return artifact, err
	}
	if err = requireWorkspaceSpace(space, request.WorkRoot, outputBytes); err != nil {
		return artifact, mediadomain.ErrImageIO
	}
	output := filepath.Join(workspace, "image.webp")
	if err = renderer.execute(ctx, scratch, renderer.cwebp, []string{"-quiet", "-q", strconv.Itoa(request.Spec.Quality), "-m", "2", "-low_memory", "-metadata", "none", "-resize", strconv.Itoa(plan.Width), strconv.Itoa(plan.Height), "-o", output, "--", encodedSource}); err != nil {
		return artifact, err
	}
	after, err := input.Stat()
	current, currentErr := os.Lstat(request.SourcePath)
	if err != nil || currentErr != nil || !os.SameFile(before, current) || before.Size() != after.Size() || !before.ModTime().Equal(after.ModTime()) {
		return artifact, mediadomain.ErrImageChanged
	}
	artifact, err = inspectArtifact(ctx, output, workspace, plan)
	if err != nil {
		return artifact, err
	}
	// 中间文件都在快速暂存目录，产物目录只保留最终 WebP。
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

type inputMode uint8

const (
	directInput inputMode = iota
	jpegPPM
	orientedJPEG
	webpPPM
	normalizedInput
)

// 暂存空间估算和实际编码共用同一次输入路径选择。
func selectInputMode(plan mediadomain.ImagePlan, format string, orientation int, prefix []byte) inputMode {
	switch {
	case plan.ScaleWebP:
		return webpPPM
	case format == "png" && isStaticPNG(prefix), format == "webp" && isStaticWebP(prefix):
		return directInput
	case format == "jpeg" && orientation == 1:
		return jpegPPM
	case format == "jpeg":
		return orientedJPEG
	default:
		return normalizedInput
	}
}

// imageIntermediateBytes 按像素估算快速暂存目录中可能出现的 PPM、BMP 或 PNG 中间文件。
func imageIntermediateBytes(plan mediadomain.ImagePlan, mode inputMode) (int64, error) {
	switch mode {
	case directInput:
		return 0, nil
	case jpegPPM, webpPPM:
		return imageFileBytes(plan.DecodeWidth, plan.DecodeHeight, 3)
	case orientedJPEG:
		// 对齐后的 BMP 和规范化 PNG 同时占用暂存空间。
		bmp, err := imageFileBytes(plan.DecodeWidth, plan.DecodeHeight, 4)
		if err != nil {
			return 0, err
		}
		png, err := imageFileBytes(plan.DecodeWidth, plan.DecodeHeight, 4)
		if err != nil {
			return 0, err
		}
		return bmp + png, nil
	default:
		return imageFileBytes(plan.DecodeWidth, plan.DecodeHeight, 4) // Go 规范化 PNG 的像素上界。
	}
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

func (renderer *Renderer) execute(ctx context.Context, workspace, executable string, args []string) error {
	result, err := renderer.runner.Run(ctx, process.Request{Directory: workspace, Executable: executable, Args: args, Timeout: 2 * time.Minute})
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return err
	}
	if errors.Is(err, process.ErrStart) {
		return mediadomain.ErrImageUnavailable
	}
	if errors.Is(err, process.ErrExit) && executable == renderer.djpeg {
		if bytes.Contains(result.Stderr, []byte("Memory limit exceeded")) {
			return mediadomain.ErrImageLimit
		}
		// libjpeg-turbo 3 以退出码 2 表示解码完成但有可恢复告警（例如局部哈夫曼码损坏、
		// 提前结束），输出仍是完整图像；后续仍核对 PPM/BMP 尺寸与最终产物。
		if result.ExitCode == 2 {
			return nil
		}
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

func inspectArtifact(ctx context.Context, path, directory string, plan mediadomain.ImagePlan) (mediadomain.ImageArtifact, error) {
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
	if err = ctx.Err(); err != nil {
		return mediadomain.ImageArtifact{}, err
	}
	return mediadomain.ImageArtifact{Path: path, TemporaryDirectory: directory, Width: config.Width, Height: config.Height, Size: info.Size(), JPEGScale: plan.JPEGScale}, nil
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
