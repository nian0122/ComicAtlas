package main

import (
	"bytes"
	"image"
	"image/jpeg"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// 大图占用的加权额度不足时必须等待，释放额度后继续；worker 本身仍保持并行。
func TestPixelBudget_blocksOnlyWhenAggregatePixelsExceedCapacity(t *testing.T) {
	budget := newPixelBudget(10)
	releaseFirst := budget.acquire(6)
	releaseSecond := budget.acquire(4)
	releaseSecond()

	started := make(chan struct{})
	acquired := make(chan func(), 1)
	go func() {
		close(started)
		acquired <- budget.acquire(5)
	}()
	<-started

	select {
	case release := <-acquired:
		release()
		t.Fatal("总权重超过预算时不应获取额度")
	case <-time.After(50 * time.Millisecond):
		// 符合预期：等待前一张大图释放额度。
	}

	releaseFirst()
	select {
	case release := <-acquired:
		release()
	case <-time.After(time.Second):
		t.Fatal("释放像素预算后应唤醒等待中的 worker")
	}
}

// 生成一张某边超过 WebP 上限 16383 的 JPEG，验证工具先缩放并统一输出 WebP。
func TestOptimizeImageToWebP_oversizedDimension_scalesAndOutputsWebP(t *testing.T) {
	dir := t.TempDir()
	src := filepath.Join(dir, "tall.jpg")
	img := image.NewRGBA(image.Rect(0, 0, 32, 16400)) // 高 16400 > 16383，宽极小以加速
	f, err := os.Create(src)
	if err != nil {
		t.Fatal(err)
	}
	if err := jpeg.Encode(f, img, &jpeg.Options{Quality: 75}); err != nil {
		t.Fatal(err)
	}
	f.Close()

	out := filepath.Join(dir, "out", "tall.webp")
	result, err := optimizeImageToWebP(src, out, 75)
	if err != nil {
		t.Fatalf("超限图应缩放并输出 WebP: %v", err)
	}
	if stat, statErr := os.Stat(out); statErr != nil || stat.Size() == 0 {
		t.Fatalf("缩放后应生成非空 WebP: %v", statErr)
	}
	if result.OutputPath != out {
		t.Fatalf("应返回实际 WebP 产物，结果为 %+v", result)
	}
	width, height, ok := readImageDimension(out, ".webp")
	if !ok || width > defaultMaxLongEdge || height > defaultMaxLongEdge {
		t.Fatalf("WebP 最长边必须不超过 %d，实际为 %dx%d", defaultMaxLongEdge, width, height)
	}
}

// 正常尺寸图仍走原成功路径，输出 WebP 非空。
func TestOptimizeImageToWebP_normalDimension_succeeds(t *testing.T) {
	dir := t.TempDir()
	src := filepath.Join(dir, "normal.jpg")
	img := image.NewRGBA(image.Rect(0, 0, 320, 240))
	f, err := os.Create(src)
	if err != nil {
		t.Fatal(err)
	}
	if err := jpeg.Encode(f, img, &jpeg.Options{Quality: 75}); err != nil {
		t.Fatal(err)
	}
	f.Close()

	out := filepath.Join(dir, "out", "normal.webp")
	result, err := optimizeImageToWebP(src, out, 75)
	if err != nil {
		t.Fatalf("正常图不应失败: %v", err)
	}
	if result.OutputSize <= 0 {
		t.Fatal("输出 WebP 应为非空文件")
	}
}

// 部分来源 JPEG 仅缺少 EOI（FF D9），像素数据仍完整；优化时应在内存中补齐，且不改 HQ。
func TestOptimizeImageToWebP_missingJpegEndMarker_recoversWithoutChangingSource(t *testing.T) {
	dir := t.TempDir()
	sourcePath := filepath.Join(dir, "truncated.jpg")
	imageData := image.NewRGBA(image.Rect(0, 0, 320, 240))
	outputFile, err := os.Create(sourcePath)
	if err != nil {
		t.Fatal(err)
	}
	if err := jpeg.Encode(outputFile, imageData, &jpeg.Options{Quality: 75}); err != nil {
		t.Fatal(err)
	}
	if err := outputFile.Close(); err != nil {
		t.Fatal(err)
	}
	encodedData, err := os.ReadFile(sourcePath)
	if err != nil {
		t.Fatal(err)
	}
	truncatedData := encodedData[:len(encodedData)-2]
	if err := os.WriteFile(sourcePath, truncatedData, 0644); err != nil {
		t.Fatal(err)
	}

	outputPath := filepath.Join(dir, "out", "truncated.webp")
	result, err := optimizeImageToWebP(sourcePath, outputPath, 75)
	if err != nil {
		t.Fatalf("仅缺少 EOI 的 JPEG 应成功生成 LQ: %v", err)
	}
	if result.OutputSize <= 0 {
		t.Fatal("修复解码后应生成非空 WebP")
	}
	currentSource, err := os.ReadFile(sourcePath)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(currentSource, truncatedData) {
		t.Fatal("容错解码不得修改 HQ 源文件")
	}
}

func TestScaledDimensions_preservesAspectRatioWithoutUpscaling(t *testing.T) {
	width, height := scaledDimensions(10652, 14204, 3840)
	if width != 2880 || height != 3840 {
		t.Fatalf("应等比缩放为 2880x3840，实际为 %dx%d", width, height)
	}
	originalWidth, originalHeight := scaledDimensions(1200, 1600, 3840)
	if originalWidth != 1200 || originalHeight != 1600 {
		t.Fatalf("小图不得放大，实际为 %dx%d", originalWidth, originalHeight)
	}
}

func TestJpegTurboScaleNumerator_decodesNearTargetSize(t *testing.T) {
	numerator, useTurboDecode := jpegTurboDecodeStrategy(10652, 14204, 3840)
	if numerator != 3 {
		t.Fatalf("DCT 缩放应选择 3/8，实际为 %d/8", numerator)
	}
	if !useTurboDecode {
		t.Fatal("3/8 能显著减少解码像素，应启用 libjpeg-turbo 预缩放")
	}
	pixels := estimatedDecodePixels(10652, 14204, true, numerator)
	if pixels >= int64(10652*14204) {
		t.Fatalf("缩放解码像素应显著小于原图，实际为 %d", pixels)
	}
}

func TestJpegTurboDecodeStrategy_nearThreshold_skipsEightEighthsDecode(t *testing.T) {
	numerator, useTurboDecode := jpegTurboDecodeStrategy(2728, 4096, 3840)
	if numerator != 8 {
		t.Fatalf("接近目标尺寸的 JPEG 应计算为 8/8，实际为 %d/8", numerator)
	}
	if useTurboDecode {
		t.Fatal("8/8 不减少像素，不应生成完整 BMP 中间文件")
	}
}

func TestIsRecoverableTruncatedJpegWarning_onlyAcceptsMissingEoiWarning(t *testing.T) {
	if !isRecoverableTruncatedJpegWarning([]byte("Premature end of JPEG file\n")) {
		t.Fatal("缺少 JPEG EOI 标记应识别为可恢复告警")
	}
	if isRecoverableTruncatedJpegWarning([]byte("Invalid JPEG file structure")) {
		t.Fatal("其他 JPEG 结构错误不得按可恢复告警处理")
	}
}
