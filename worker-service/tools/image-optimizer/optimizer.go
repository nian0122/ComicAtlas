package main

import (
	"fmt"
	"image"
	"image/gif"
	"image/jpeg"
	"image/png"
	"os"
	"path/filepath"
	"strings"

	"github.com/chai2010/webp"
)

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

	// 解码图片
	var img image.Image
	ext := strings.ToLower(filepath.Ext(filePath))
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

	// 确保输出目录存在
	outputDir := filepath.Dir(outputPath)
	if err := os.MkdirAll(outputDir, 0755); err != nil {
		return result, fmt.Errorf("创建输出目录失败: %w", err)
	}

	// 创建输出文件
	outputFile, err := os.Create(outputPath)
	if err != nil {
		return result, fmt.Errorf("创建输出文件失败: %w", err)
	}
	defer outputFile.Close()

	// WebP 编码选项
	options := &webp.Options{
		Lossless: false,
		Quality:  float32(quality),
	}

	if err := webp.Encode(outputFile, img, options); err != nil {
		return result, fmt.Errorf("编码 WebP 失败: %w", err)
	}

	// 获取输出文件大小
	outputInfo, err := os.Stat(outputPath)
	if err != nil {
		return result, fmt.Errorf("获取输出文件信息失败: %w", err)
	}
	result.OutputSize = outputInfo.Size()

	return result, nil
}
