package main

import (
	"fmt"
	"image"
	"image/gif"
	"image/jpeg"
	"image/png"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/chai2010/webp"
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
	InputSize    int64
	OutputSize   int64
	OutputPath   string
	OutputFormat string
}

// optimizeImageToWebP 将图片转换为 WebP；超过 WebP 边长限制时使用同名 JPEG 兜底。
func optimizeImageToWebP(filePath string, outputPath string, quality int) (OptimizeResult, error) {
	return optimizeImageToWebPWithBudget(filePath, outputPath, quality, nil)
}

func optimizeImageToWebPWithBudget(filePath string, outputPath string, quality int,
	decodeBudget *pixelBudget) (OptimizeResult, error) {
	result := OptimizeResult{}
	sourceInfo, err := os.Stat(filePath)
	if err != nil {
		return result, fmt.Errorf("获取源文件信息失败: %w", err)
	}
	result.InputSize = sourceInfo.Size()

	extension := strings.ToLower(filepath.Ext(filePath))
	width, height, hasDimensions := readImageDimension(filePath, extension)
	if decodeBudget != nil {
		pixels := int64(0)
		if hasDimensions {
			pixels = int64(width) * int64(height)
		}
		release := decodeBudget.acquire(pixels)
		defer release()
	}

	inputFile, err := os.Open(filePath)
	if err != nil {
		return result, fmt.Errorf("打开源文件失败: %w", err)
	}
	defer inputFile.Close()

	if _, err := inputFile.Seek(0, io.SeekStart); err != nil {
		return result, fmt.Errorf("重置源文件指针失败: %w", err)
	}
	imageData, err := decodeImage(inputFile, extension)
	if err != nil {
		return result, fmt.Errorf("解码图片失败: %w", err)
	}

	if !hasDimensions {
		bounds := imageData.Bounds()
		width, height = bounds.Dx(), bounds.Dy()
	}
	if width <= 0 || height <= 0 {
		return result, fmt.Errorf("图片尺寸无效: %dx%d", width, height)
	}

	encodeWebP := width <= maxWebpDimension && height <= maxWebpDimension
	outputExtension := ".webp"
	outputFormat := "webp"
	if !encodeWebP {
		outputExtension = ".jpg"
		outputFormat = "jpeg"
	}
	actualOutputPath := replaceExtension(outputPath, outputExtension)
	if err := encodeImageAtomically(actualOutputPath, imageData, quality, encodeWebP); err != nil {
		return result, err
	}

	outputInfo, err := os.Stat(actualOutputPath)
	if err != nil {
		return result, fmt.Errorf("获取输出文件信息失败: %w", err)
	}
	if outputInfo.Size() == 0 {
		return result, fmt.Errorf("输出文件为空: %s", actualOutputPath)
	}
	if !encodeWebP {
		// JPEG 兜底时删除旧 WebP，避免读取端误命中旧版本。
		_ = os.Remove(replaceExtension(outputPath, ".webp"))
	}
	result.OutputSize = outputInfo.Size()
	result.OutputPath = actualOutputPath
	result.OutputFormat = outputFormat
	return result, nil
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

func encodeImageAtomically(outputPath string, imageData image.Image, quality int, encodeWebP bool) error {
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

	if encodeWebP {
		options := &webp.Options{Lossless: false, Quality: float32(quality)}
		err = webp.Encode(temporaryFile, imageData, options)
	} else {
		err = jpeg.Encode(temporaryFile, imageData, &jpeg.Options{Quality: quality})
	}
	if closeErr := temporaryFile.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return fmt.Errorf("编码 %s 失败: %w", outputFormatName(encodeWebP), err)
	}
	if err := os.Remove(outputPath); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("替换旧输出文件失败: %w", err)
	}
	if err := os.Rename(temporaryPath, outputPath); err != nil {
		return fmt.Errorf("发布输出文件失败: %w", err)
	}
	return nil
}

func outputFormatName(encodeWebP bool) string {
	if encodeWebP {
		return "WebP"
	}
	return "JPEG"
}

func replaceExtension(filePath string, extension string) string {
	return strings.TrimSuffix(filePath, filepath.Ext(filePath)) + extension
}
