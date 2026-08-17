package main

import (
	"bytes"
	"fmt"
	"image"
	"image/gif"
	"image/jpeg"
	"image/png"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/chai2010/webp"
)

// maxWebpDimension libwebp 硬性尺寸上限（WEBP_MAX_DIMENSION）。宽或高超过该值的图片
// 编码必然失败，且完整解码 + 像素转换会耗费数分钟（事故场景：2129x42294 超长图），
// 必须在解码前快速拒绝。
const maxWebpDimension = 16383

// OptimizeResult 包含单文件的优化结果
type OptimizeResult struct {
	InputSize  int64 // 原始文件大小（字节）
	OutputSize int64 // 输出文件大小（字节）
}

// optimizeImageToWebP 将图片转换为 WebP 格式
func optimizeImageToWebP(filePath string, outputPath string, quality int) (OptimizeResult, error) {
	result := OptimizeResult{}

	// 获取源文件大小
	sourceInfo, err := os.Stat(filePath)
	if err != nil {
		return result, fmt.Errorf("获取源文件信息失败: %w", err)
	}
	result.InputSize = sourceInfo.Size()

	// 打开源文件
	file, err := os.Open(filePath)
	if err != nil {
		return result, fmt.Errorf("打开源文件失败: %w", err)
	}
	defer file.Close()

	// 解码前预检尺寸：仅解析文件头，避免对超限图做数分钟的完整解码与像素转换。
	ext := strings.ToLower(filepath.Ext(filePath))
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

	// 解码后兜底检查（webp/gif 等无 DecodeConfig 的格式）
	if bounds := img.Bounds(); bounds.Dx() > maxWebpDimension || bounds.Dy() > maxWebpDimension {
		return result, fmt.Errorf("图片尺寸超出 WebP 上限: %dx%d", bounds.Dx(), bounds.Dy())
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
	if ok && (width > maxWebpDimension || height > maxWebpDimension) {
		return fmt.Errorf("图片尺寸超出 WebP 上限: %dx%d", width, height)
	}
	// 无论预检结果如何都重置文件指针，供后续完整解码使用
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return fmt.Errorf("重置文件指针失败: %w", err)
	}
	return nil
}
