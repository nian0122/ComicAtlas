package main

import (
	"image"
	"image/jpeg"
	"os"
	"path/filepath"
	"testing"
)

// 生成一张某边超过 WebP 上限 16383 的 JPEG，验证工具快速失败且不残留空输出。
// 回归场景：漫画 247 的 2129x42294 超长图——旧实现完整解码+像素转换耗时数分钟，
// 最后 libwebp 编码失败，且已 os.Create 的空输出文件残留（Java 端误捡为封面）。
func TestOptimizeImageToWebP_oversizedDimension_failsFastNoResidue(t *testing.T) {
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
	_, err = optimizeImageToWebP(src, out, 75)
	if err == nil {
		t.Fatal("超限图必须快速失败")
	}
	if _, statErr := os.Stat(out); !os.IsNotExist(statErr) {
		t.Fatalf("失败后不得残留空输出文件: %v", statErr)
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
