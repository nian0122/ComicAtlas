package main

import (
	"bytes"
	"context"
	"fmt"
	"image"
	"image/gif"
	"image/jpeg"
	"image/png"
	"io"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/chai2010/webp"
	"golang.org/x/image/draw"
)

// maxWebpDimension libwebp 硬性尺寸上限（WEBP_MAX_DIMENSION）。宽或高超过该值的图片
// 编码必然失败，且完整解码 + 像素转换会耗费数分钟（事故场景：2129x42294 超长图），
// 必须在解码前快速拒绝。
const maxWebpDimension = 16383

// maxDecodePixels 限制单张图片的解码像素数。Go image 解码通常至少需要
// 4 bytes/pixel，libwebp 编码还会建立额外的像素缓冲；超过该值即使宽高
// 未超过 WebP 限制，也可能在容器内存较小时触发 OOM killer。
const maxDecodePixels int64 = 80_000_000

// ImageSkipError 表示源图超出 LQ WebP 的处理边界。该类图片保留 HQ，
// 不生成 LQ，但不应把整章任务判定为失败。
type ImageSkipError struct{ Reason string }

func (e *ImageSkipError) Error() string { return e.Reason }

// 大图使用独立的单槽位，普通图片仍按 workers 并行；大图完成后立即释放，
// 不会把整章永久降为单线程。
var largeImageSemaphore = make(chan struct{}, 1)

// OptimizeResult 包含单文件的优化结果
type OptimizeResult struct {
	InputSize  int64 // 原始文件大小（字节）
	OutputSize int64 // 输出文件大小（字节）
}

// optimizeImageToWebP 将图片转换为 WebP 格式
func optimizeImageToWebP(filePath string, outputPath string, quality int) (OptimizeResult, error) {
	return optimizeImageToWebPWithFfmpeg(filePath, outputPath, quality, "ffmpeg")
}

func optimizeImageToWebPWithFfmpeg(filePath string, outputPath string, quality int, ffmpegPath string) (OptimizeResult, error) {
	result := OptimizeResult{}

	// 获取源文件大小
	sourceInfo, err := os.Stat(filePath)
	if err != nil {
		return result, fmt.Errorf("获取源文件信息失败: %w", err)
	}
	result.InputSize = sourceInfo.Size()

	// 先读取文件头。超大 JPEG/PNG 必须交给 FFmpeg 在解码阶段缩放，
	// 不能先由 image.Decode 完整展开到内存后再缩放。
	ext := strings.ToLower(filepath.Ext(filePath))
	if width, height, ok := readImageDimension(filePath, ext); ok &&
		(int64(width)*int64(height) > maxDecodePixels || width > maxWebpDimension || height > maxWebpDimension) {
		return result, &ImageSkipError{Reason: fmt.Sprintf("图片尺寸超出 LQ WebP 处理范围，已跳过: %dx%d", width, height)}
	}

	// 打开源文件
	file, err := os.Open(filePath)
	if err != nil {
		return result, fmt.Errorf("打开源文件失败: %w", err)
	}
	defer file.Close()

	// 解码前预检尺寸：仅解析文件头，避免对超限图做数分钟的完整解码与像素转换。
	if err := precheckDimension(file, ext); err != nil {
		return result, err
	}

	// 解码图片
	var img image.Image
	switch ext {
	case ".jpg", ".jpeg":
		img, err = jpeg.Decode(file)
	case ".png":
		img, err = png.Decode(file)
	case ".webp":
		img, err = webp.Decode(file)
	case ".gif":
		img, err = gif.Decode(file)
	default:
		return result, fmt.Errorf("不支持的格式: %s", ext)
	}

	if err != nil {
		return result, fmt.Errorf("解码图片失败: %w", err)
	}

	// 解码后兜底检查（webp/gif 等无 DecodeConfig 的格式）。超出 WebP
	// 边长时按比例缩小，避免整章因少数超长页失败。
	if bounds := img.Bounds(); int64(bounds.Dx())*int64(bounds.Dy()) > maxDecodePixels {
		return result, fmt.Errorf("图片像素数过高，为避免内存耗尽拒绝处理: %dx%d (%d MP)",
			bounds.Dx(), bounds.Dy(), int64(bounds.Dx())*int64(bounds.Dy())/1_000_000)
	} else if bounds.Dx() > maxWebpDimension || bounds.Dy() > maxWebpDimension {
		img = resizeToWebpBounds(img)
		runtime.GC()
	}

	// 先编码到内存，成功后再落盘——失败路径绝不残留 0 字节输出文件
	options := &webp.Options{
		Lossless: false,
		Quality:  float32(quality),
	}
	var buf bytes.Buffer
	if err := webp.Encode(&buf, img, options); err != nil {
		return result, fmt.Errorf("编码 WebP 失败: %w", err)
	}

	outputDir := filepath.Dir(outputPath)
	if err := os.MkdirAll(outputDir, 0755); err != nil {
		return result, fmt.Errorf("创建输出目录失败: %w", err)
	}
	if err := os.WriteFile(outputPath, buf.Bytes(), 0644); err != nil {
		return result, fmt.Errorf("写入输出文件失败: %w", err)
	}

	// 获取输出文件大小
	outputInfo, err := os.Stat(outputPath)
	if err != nil {
		return result, fmt.Errorf("获取输出文件信息失败: %w", err)
	}
	result.OutputSize = outputInfo.Size()

	return result, nil
}

func readImageDimension(filePath, ext string) (int, int, bool) {
	file, err := os.Open(filePath)
	if err != nil {
		return 0, 0, false
	}
	defer file.Close()
	var width, height int
	switch ext {
	case ".jpg", ".jpeg":
		if cfg, decodeErr := jpeg.DecodeConfig(file); decodeErr == nil {
			width, height = cfg.Width, cfg.Height
		}
	case ".png":
		if cfg, decodeErr := png.DecodeConfig(file); decodeErr == nil {
			width, height = cfg.Width, cfg.Height
		}
	default:
		return 0, 0, false
	}
	return width, height, width > 0 && height > 0
}

func optimizeLargeImageWithFfmpeg(filePath, outputPath string, quality int, _ string,
	result OptimizeResult) (OptimizeResult, error) {
	largeImageSemaphore <- struct{}{}
	defer func() { <-largeImageSemaphore }()

	convertPath := findImageMagick()
	if convertPath == "" {
		return result, fmt.Errorf("超大图片需要 ImageMagick 缩放，但未找到 magick/convert")
	}

	/*
		Windows 自带的 convert.exe 是 NTFS 文件系统工具，不是 ImageMagick，
		因此不能只依赖 PATH 命中判断。
	*/
	if output, err := exec.Command(convertPath, "-version").CombinedOutput(); err != nil ||
		!strings.Contains(strings.ToLower(string(output)), "imagemagick") {
		return result, fmt.Errorf("找到的 convert 不是 ImageMagick: %s", convertPath)
	}

	outputDir := filepath.Dir(outputPath)
	if err := os.MkdirAll(outputDir, 0755); err != nil {
		return result, fmt.Errorf("创建 LQ 目录失败: %w", err)
	}
	tempFile, err := os.CreateTemp(outputDir, ".image-optimizer-*.webp")
	if err != nil {
		return result, fmt.Errorf("创建临时输出失败: %w", err)
	}
	tempPath := tempFile.Name()
	_ = tempFile.Close()
	defer os.Remove(tempPath)

	args := []string{"-limit", "width", "30000", "-limit", "height", "30000",
		"-limit", "area", "4GiB", "-limit", "memory", "2GiB", "-limit", "map", "4GiB",
		filePath, "-auto-orient", "-resize",
		fmt.Sprintf("%dx%d>", maxWebpDimension, maxWebpDimension), "-strip", "-quality", fmt.Sprint(quality),
		"-define", "webp:method=6", tempPath}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
	defer cancel()
	command := exec.CommandContext(ctx, convertPath, args...)
	if policyPath := findImageMagickPolicy(); policyPath != "" {
		command.Env = append(os.Environ(), "MAGICK_CONFIGURE_PATH="+filepath.Dir(policyPath))
	}
	if output, runErr := command.CombinedOutput(); runErr != nil {
		if ctx.Err() != nil {
			return result, fmt.Errorf("超大图片 ImageMagick 处理超时: %w", ctx.Err())
		}
		return result, fmt.Errorf("超大图片 ImageMagick 处理失败: %w: %s", runErr, strings.TrimSpace(string(output)))
	}
	if err := os.Rename(tempPath, outputPath); err != nil {
		return result, fmt.Errorf("发布 LQ 文件失败: %w", err)
	}
	info, err := os.Stat(outputPath)
	if err != nil || info.Size() == 0 {
		return result, fmt.Errorf("ImageMagick 未生成有效 LQ 文件: %w", err)
	}
	result.OutputSize = info.Size()
	return result, nil
}

func findImageMagickPolicy() string {
	paths := []string{
		filepath.Join(filepath.Dir(os.Args[0]), "image-optimizer-policy.xml"),
		filepath.Join(filepath.Dir(os.Args[0]), "policy.xml"),
		filepath.Join(".", "policy.xml"),
	}
	for _, path := range paths {
		if info, err := os.Stat(path); err == nil && !info.IsDir() {
			return path
		}
	}
	return ""
}

func findImageMagick() string {
	candidates := []string{}
	if configured := os.Getenv("IMAGE_MAGICK_PATH"); configured != "" {
		candidates = append(candidates, configured)
	}
	candidates = append(candidates, "magick", "convert")
	for _, candidate := range candidates {
		if path, err := exec.LookPath(candidate); err == nil {
			if output, versionErr := exec.Command(path, "-version").CombinedOutput(); versionErr == nil && strings.Contains(strings.ToLower(string(output)), "imagemagick") {
				return path
			}
		}
	}
	return ""
}

// precheckDimension 用 DecodeConfig 读取图片尺寸，超限直接失败（不进入像素解码）。
// 仅对支持 DecodeConfig 的格式生效（jpg/png）；格式不支持或头部解析失败时
// 回退到完整解码路径，由解码后 Bounds 检查兜底。
func precheckDimension(file *os.File, ext string) error {
	var (
		width, height int
		ok            bool
	)
	switch ext {
	case ".jpg", ".jpeg":
		if cfg, err := jpeg.DecodeConfig(file); err == nil {
			width, height, ok = cfg.Width, cfg.Height, true
		}
	case ".png":
		if cfg, err := png.DecodeConfig(file); err == nil {
			width, height, ok = cfg.Width, cfg.Height, true
		}
	}
	if ok && int64(width)*int64(height) > maxDecodePixels {
		return fmt.Errorf("图片像素数过高，为避免内存耗尽拒绝处理: %dx%d (%d MP)",
			width, height, int64(width)*int64(height)/1_000_000)
	}
	// 无论预检结果如何都重置文件指针，供后续完整解码使用
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return fmt.Errorf("重置文件指针失败: %w", err)
	}
	return nil
}

// resizeToWebpBounds 使用 Catmull-Rom 高质量重采样，仅在超过 WebP
// 硬性边长限制时缩放，保持原始比例，避免不必要的画质损失。
func resizeToWebpBounds(src image.Image) image.Image {
	bounds := src.Bounds()
	scale := math.Min(float64(maxWebpDimension)/float64(bounds.Dx()),
		float64(maxWebpDimension)/float64(bounds.Dy()))
	width := int(math.Floor(float64(bounds.Dx()) * scale))
	height := int(math.Floor(float64(bounds.Dy()) * scale))
	if width < 1 {
		width = 1
	}
	if height < 1 {
		height = 1
	}
	dst := image.NewRGBA(image.Rect(0, 0, width, height))
	draw.CatmullRom.Scale(dst, dst.Bounds(), src, bounds, draw.Over, nil)
	return dst
}
