package main

import (
	"context"
	"fmt"
	"image"
	"image/gif"
	"image/jpeg"
	"image/png"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/chai2010/webp"
	"golang.org/x/image/bmp"
	"golang.org/x/image/draw"
)

const maxWebpDimension = 16383

// pixelBudget 限制同时解码的总像素量，超大图仍允许单张独占预算。
type pixelBudget struct {
	capacity int64
	used     int64
	mutex    sync.Mutex
	cond     *sync.Cond
}

func newPixelBudget(capacity int64) *pixelBudget {
	budget := &pixelBudget{capacity: capacity}
	budget.cond = sync.NewCond(&budget.mutex)
	return budget
}

func (budget *pixelBudget) acquire(pixels int64) func() {
	weight := pixels
	if weight < 1 || weight > budget.capacity {
		weight = budget.capacity
	}
	budget.mutex.Lock()
	for budget.used+weight > budget.capacity {
		budget.cond.Wait()
	}
	budget.used += weight
	budget.mutex.Unlock()
	return func() {
		budget.mutex.Lock()
		budget.used -= weight
		budget.cond.Broadcast()
		budget.mutex.Unlock()
	}
}

// OptimizeResult 描述单个媒体的实际 LQ 产物。
type OptimizeResult struct {
	InputSize  int64
	OutputSize int64
	OutputPath string
}

// optimizeImageToWebP 将图片等比缩放到 LQ 尺寸并统一转换为 WebP。
func optimizeImageToWebP(filePath string, outputPath string, quality int) (OptimizeResult, error) {
	return optimizeImageToWebPWithBudget(filePath, outputPath, quality, defaultMaxLongEdge, nil)
}

func optimizeImageToWebPWithBudget(filePath string, outputPath string, quality int,
	maxLongEdge int, decodeBudget *pixelBudget) (OptimizeResult, error) {
	result := OptimizeResult{}
	sourceInfo, err := os.Stat(filePath)
	if err != nil {
		return result, fmt.Errorf("获取源文件信息失败: %w", err)
	}
	result.InputSize = sourceInfo.Size()

	extension := strings.ToLower(filepath.Ext(filePath))
	width, height, hasDimensions := readImageDimension(filePath, extension)
	if maxLongEdge < 1 {
		return result, fmt.Errorf("LQ 最大长边必须大于 0: %d", maxLongEdge)
	}
	turboDecode := false
	turboScaleNumerator := 8
	if hasDimensions && isJpegExtension(extension) && needsResize(width, height, maxLongEdge) {
		turboScaleNumerator, turboDecode = jpegTurboDecodeStrategy(width, height, maxLongEdge)
		if turboDecode {
			_, resolveErr := resolveDjpegPath()
			if resolveErr != nil {
				turboDecode = false
			}
		}
	}
	if decodeBudget != nil {
		pixels := int64(0)
		if hasDimensions {
			pixels = estimatedDecodePixels(width, height, turboDecode, turboScaleNumerator)
		}
		release := decodeBudget.acquire(pixels)
		defer release()
	}

	if err := os.MkdirAll(filepath.Dir(outputPath), 0755); err != nil {
		return result, fmt.Errorf("创建输出目录失败: %w", err)
	}

	var imageData image.Image
	if turboDecode {
		imageData, err = decodeScaledJpegWithTurbo(filePath, turboScaleNumerator)
	} else {
		imageData, err = decodeImageFile(filePath, extension)
	}
	if err != nil {
		return result, fmt.Errorf("解码图片失败: %w", err)
	}

	imageData = resizeToLongEdge(imageData, maxLongEdge)
	outputBounds := imageData.Bounds()
	if outputBounds.Dx() <= 0 || outputBounds.Dy() <= 0 {
		return result, fmt.Errorf("缩放后图片尺寸无效: %dx%d", outputBounds.Dx(), outputBounds.Dy())
	}
	if outputBounds.Dx() > maxWebpDimension || outputBounds.Dy() > maxWebpDimension {
		return result, fmt.Errorf("缩放后图片尺寸仍超出 WebP 上限: %dx%d",
			outputBounds.Dx(), outputBounds.Dy())
	}

	actualOutputPath := replaceExtension(outputPath, ".webp")
	if err := encodeWebPAtomically(actualOutputPath, imageData, quality); err != nil {
		return result, err
	}

	outputInfo, err := os.Stat(actualOutputPath)
	if err != nil {
		return result, fmt.Errorf("获取输出文件信息失败: %w", err)
	}
	if outputInfo.Size() == 0 {
		return result, fmt.Errorf("输出文件为空: %s", actualOutputPath)
	}
	result.OutputSize = outputInfo.Size()
	result.OutputPath = actualOutputPath
	return result, nil
}

func decodeImageFile(filePath string, extension string) (image.Image, error) {
	inputFile, err := os.Open(filePath)
	if err != nil {
		return nil, fmt.Errorf("打开源文件失败: %w", err)
	}
	defer inputFile.Close()
	return decodeImage(inputFile, extension)
}

// decodeScaledJpegWithTurbo 利用 JPEG DCT 缩放在完整像素解码前降低分辨率。
// BMP 中间文件必须写入系统临时目录，避免与 HQ/LQ 所在机械盘争抢读写。
func decodeScaledJpegWithTurbo(filePath string, scaleNumerator int) (image.Image, error) {
	djpegPath, err := resolveDjpegPath()
	if err != nil {
		return nil, err
	}
	temporaryFile, err := os.CreateTemp("", ".image-optimizer-*.bmp")
	if err != nil {
		return nil, fmt.Errorf("创建缩放解码临时文件失败: %w", err)
	}
	temporaryPath := temporaryFile.Name()
	if closeErr := temporaryFile.Close(); closeErr != nil {
		_ = os.Remove(temporaryPath)
		return nil, fmt.Errorf("关闭缩放解码临时文件失败: %w", closeErr)
	}
	defer os.Remove(temporaryPath)

	contextValue, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
	defer cancel()
	decodeCommand := exec.CommandContext(contextValue, djpegPath,
		"-scale", fmt.Sprintf("%d/8", scaleNumerator),
		"-bmp", "-outfile", temporaryPath, filePath)
	decodeOutput, decodeErr := decodeCommand.CombinedOutput()
	if contextValue.Err() != nil {
		return nil, fmt.Errorf("libjpeg-turbo 缩放解码超时: %w", contextValue.Err())
	}
	if decodeErr != nil {
		return nil, fmt.Errorf("libjpeg-turbo 缩放解码失败: %w: %s", decodeErr,
			strings.TrimSpace(string(decodeOutput)))
	}

	decodedFile, err := os.Open(temporaryPath)
	if err != nil {
		return nil, fmt.Errorf("打开缩放解码产物失败: %w", err)
	}
	defer decodedFile.Close()
	decodedImage, err := bmp.Decode(decodedFile)
	if err != nil {
		return nil, fmt.Errorf("读取缩放解码产物失败: %w", err)
	}
	return decodedImage, nil
}

func resizeToLongEdge(sourceImage image.Image, maxLongEdge int) image.Image {
	sourceBounds := sourceImage.Bounds()
	sourceWidth := sourceBounds.Dx()
	sourceHeight := sourceBounds.Dy()
	targetWidth, targetHeight := scaledDimensions(sourceWidth, sourceHeight, maxLongEdge)
	if targetWidth == sourceWidth && targetHeight == sourceHeight {
		return sourceImage
	}
	targetImage := image.NewRGBA(image.Rect(0, 0, targetWidth, targetHeight))
	draw.CatmullRom.Scale(targetImage, targetImage.Bounds(), sourceImage, sourceBounds, draw.Src, nil)
	return targetImage
}

func scaledDimensions(width int, height int, maxLongEdge int) (int, int) {
	if width <= 0 || height <= 0 || maxLongEdge <= 0 || !needsResize(width, height, maxLongEdge) {
		return width, height
	}
	longEdge := width
	if height > longEdge {
		longEdge = height
	}
	scale := float64(maxLongEdge) / float64(longEdge)
	targetWidth := max(1, int(math.Round(float64(width)*scale)))
	targetHeight := max(1, int(math.Round(float64(height)*scale)))
	return targetWidth, targetHeight
}

func needsResize(width int, height int, maxLongEdge int) bool {
	return width > maxLongEdge || height > maxLongEdge
}

func jpegTurboScaleNumerator(width int, height int, maxLongEdge int) int {
	longEdge := width
	if height > longEdge {
		longEdge = height
	}
	if longEdge <= maxLongEdge {
		return 8
	}
	numerator := int(math.Ceil(float64(maxLongEdge) * 8 / float64(longEdge)))
	if numerator < 1 {
		return 1
	}
	if numerator > 8 {
		return 8
	}
	return numerator
}

// jpegTurboDecodeStrategy 仅在 DCT 档位能够真实减少像素时启用外部解码。
// 8/8 不产生预缩放，直接内存解码可避免完整 BMP 的磁盘往返。
func jpegTurboDecodeStrategy(width int, height int, maxLongEdge int) (int, bool) {
	numerator := jpegTurboScaleNumerator(width, height, maxLongEdge)
	return numerator, needsResize(width, height, maxLongEdge) && numerator < 8
}

func estimatedDecodePixels(width int, height int, turboDecode bool, turboScaleNumerator int) int64 {
	pixels := int64(width) * int64(height)
	if !turboDecode {
		return pixels
	}
	scaleNumerator := int64(turboScaleNumerator)
	return max(1, pixels*scaleNumerator*scaleNumerator/64)
}

func isJpegExtension(extension string) bool {
	return extension == ".jpg" || extension == ".jpeg"
}

func resolveDjpegPath() (string, error) {
	djpegPath := os.Getenv("IMAGE_DJPEG_PATH")
	if djpegPath == "" {
		djpegPath = "djpeg"
	}
	resolvedPath, err := resolveExecutablePath(djpegPath)
	if err != nil {
		return "", fmt.Errorf("JPEG 缩放解码需要 libjpeg-turbo，请配置 IMAGE_DJPEG_PATH: %w", err)
	}
	return resolvedPath, nil
}

func resolveExecutablePath(path string) (string, error) {
	if filepath.IsAbs(path) {
		if _, err := os.Stat(path); err == nil {
			return path, nil
		}
		return "", fmt.Errorf("可执行文件不存在: %s", path)
	}
	return exec.LookPath(path)
}

func decodeImage(inputFile *os.File, extension string) (image.Image, error) {
	switch extension {
	case ".jpg", ".jpeg":
		return jpeg.Decode(inputFile)
	case ".png":
		return png.Decode(inputFile)
	case ".webp":
		return webp.Decode(inputFile)
	case ".gif":
		return gif.Decode(inputFile)
	default:
		return nil, fmt.Errorf("不支持的格式: %s", extension)
	}
}

func readImageDimension(filePath string, extension string) (int, int, bool) {
	inputFile, err := os.Open(filePath)
	if err != nil {
		return 0, 0, false
	}
	defer inputFile.Close()
	var width, height int
	switch extension {
	case ".jpg", ".jpeg":
		if config, decodeErr := jpeg.DecodeConfig(inputFile); decodeErr == nil {
			width, height = config.Width, config.Height
		}
	case ".png":
		if config, decodeErr := png.DecodeConfig(inputFile); decodeErr == nil {
			width, height = config.Width, config.Height
		}
	case ".webp":
		if config, decodeErr := webp.DecodeConfig(inputFile); decodeErr == nil {
			width, height = config.Width, config.Height
		}
	case ".gif":
		if config, decodeErr := gif.DecodeConfig(inputFile); decodeErr == nil {
			width, height = config.Width, config.Height
		}
	default:
		return 0, 0, false
	}
	return width, height, width > 0 && height > 0
}

func encodeWebPAtomically(outputPath string, imageData image.Image, quality int) error {
	outputDirectory := filepath.Dir(outputPath)
	if err := os.MkdirAll(outputDirectory, 0755); err != nil {
		return fmt.Errorf("创建输出目录失败: %w", err)
	}
	temporaryFile, err := os.CreateTemp(outputDirectory, ".image-optimizer-*")
	if err != nil {
		return fmt.Errorf("创建临时输出失败: %w", err)
	}
	temporaryPath := temporaryFile.Name()
	defer os.Remove(temporaryPath)

	options := &webp.Options{Lossless: false, Quality: float32(quality)}
	err = webp.Encode(temporaryFile, imageData, options)
	if closeErr := temporaryFile.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return fmt.Errorf("编码 WebP 失败: %w", err)
	}
	if err := os.Remove(outputPath); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("替换旧输出文件失败: %w", err)
	}
	if err := os.Rename(temporaryPath, outputPath); err != nil {
		return fmt.Errorf("发布输出文件失败: %w", err)
	}
	return nil
}

func replaceExtension(filePath string, extension string) string {
	return strings.TrimSuffix(filePath, filepath.Ext(filePath)) + extension
}
