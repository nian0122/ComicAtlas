package main

import (
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

// 生成一张某边超过 WebP 上限 16383 的 JPEG，验证工具使用同名 JPEG 兜底且不缩放。
func TestOptimizeImageToWebP_oversizedDimension_usesJpegFallback(t *testing.T) {
	if _, _, err := resolveJpegTurboPaths(); err != nil {
		t.Skip("当前环境未提供 libjpeg-turbo，Docker Worker 会内置该依赖")
	}
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
		t.Fatalf("超限图应使用 JPEG 兜底成功: %v", err)
	}
	jpegOutput := filepath.Join(dir, "out", "tall.jpg")
	if stat, statErr := os.Stat(jpegOutput); statErr != nil || stat.Size() == 0 {
		t.Fatalf("JPEG 兜底应生成非空输出: %v", statErr)
	}
	if result.OutputFormat != "jpeg" || result.OutputPath != jpegOutput {
		t.Fatalf("应返回实际 JPEG 产物，结果为 %+v", result)
	}
	inputFile, err := os.Open(jpegOutput)
	if err != nil {
		t.Fatal(err)
	}
	config, err := jpeg.DecodeConfig(inputFile)
	inputFile.Close()
	if err != nil {
		t.Fatal(err)
	}
	if config.Width != 32 || config.Height != 16400 {
		t.Fatalf("JPEG 兜底不应改变尺寸，实际为 %dx%d", config.Width, config.Height)
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
