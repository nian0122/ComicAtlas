package main

import (
	"image"
	"image/color"
	"image/png"
	"os"
	"path/filepath"
	"testing"

	"golang.org/x/image/bmp"
)

// 构造单章测试配置：静默模式、固定质量与并发，扩展白名单取默认值。
func newTestCfg(scanDir, outDir string) *CLIConfig {
	return &CLIConfig{
		ScanDir:    scanDir,
		OutputDir:  outDir,
		Workers:    2,
		Quality:    50,
		Quiet:      true,
		Extensions: parseExtensions(defaultExtensions),
	}
}

// writeTestPNG 写一个 4x4 不透明 PNG 测试图片。
func writeTestPNG(t *testing.T, path string) {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, 4, 4))
	img.Set(0, 0, color.RGBA{R: 255, G: 0, B: 0, A: 255})
	f, err := os.Create(path)
	if err != nil {
		t.Fatalf("创建测试 PNG 失败: %v", err)
	}
	defer f.Close()
	if err := png.Encode(f, img); err != nil {
		t.Fatalf("编码测试 PNG 失败: %v", err)
	}
}

// writeTestBMP 写一个 4x4 BMP 测试图片（导入侧与 .bmp 一致）。
func writeTestBMP(t *testing.T, path string) {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, 4, 4))
	img.Set(0, 0, color.RGBA{R: 0, G: 0, B: 255, A: 255})
	f, err := os.Create(path)
	if err != nil {
		t.Fatalf("创建测试 BMP 失败: %v", err)
	}
	defer f.Close()
	if err := bmp.Encode(f, img); err != nil {
		t.Fatalf("编码测试 BMP 失败: %v", err)
	}
}

// 每个结果应携带规范化的源相对路径（相对扫描根）、目标相对路径（相对输出根）与真实产物大小。
// Java Worker 只依据 SourceRelPath 精确映射 mediaId，不能从数字文件名推断页码。
func TestRunReportsSourceTargetRelPathAndOutputSize(t *testing.T) {
	scanDir := t.TempDir()
	outDir := t.TempDir()
	writeTestPNG(t, filepath.Join(scanDir, "001.png"))
	writeTestPNG(t, filepath.Join(scanDir, "page_02.png"))

	result := run(newTestCfg(scanDir, outDir))

	if result.Total != 2 {
		t.Fatalf("Total = %d, 期望 2", result.Total)
	}
	if result.Processed != 2 {
		t.Fatalf("Processed = %d, 期望 2（失败=%d，结果=%+v）", result.Processed, result.Failed, result.Pages)
	}
	if len(result.Pages) != 2 {
		t.Fatalf("Pages 数量 = %d, 期望 2", len(result.Pages))
	}

	bySource := map[string]PageResult{}
	for _, p := range result.Pages {
		bySource[p.SourceRelPath] = p
	}

	first, ok := bySource["001.png"]
	if !ok {
		t.Fatalf("缺少 SourceRelPath=001.png 的结果: %+v", result.Pages)
	}
	if first.Status != "processed" {
		t.Fatalf("001.png status = %s, 期望 processed", first.Status)
	}
	if first.TargetRelPath != "001.webp" {
		t.Fatalf("001.png TargetRelPath = %q, 期望 001.webp", first.TargetRelPath)
	}
	if first.OutputSize <= 0 {
		t.Fatalf("001.png OutputSize = %d, 期望 > 0", first.OutputSize)
	}

	second, ok := bySource["page_02.png"]
	if !ok {
		t.Fatalf("缺少 SourceRelPath=page_02.png 的结果: %+v", result.Pages)
	}
	if second.TargetRelPath != "page_02.webp" {
		t.Fatalf("page_02.png TargetRelPath = %q, 期望 page_02.webp", second.TargetRelPath)
	}
	if second.OutputSize <= 0 {
		t.Fatalf("page_02.png OutputSize = %d, 期望 > 0", second.OutputSize)
	}
}

// 非数字文件名（cover.bmp）也能按 .bmp 白名单进入处理管线并输出规范化相对路径。
func TestRunProcessesBmp(t *testing.T) {
	scanDir := t.TempDir()
	outDir := t.TempDir()
	writeTestBMP(t, filepath.Join(scanDir, "cover.bmp"))

	result := run(newTestCfg(scanDir, outDir))

	if result.Total != 1 {
		t.Fatalf("Total = %d, 期望 1（.bmp 应在扩展白名单内）", result.Total)
	}
	if result.Processed != 1 {
		t.Fatalf("Processed = %d, 期望 1（失败=%d，结果=%+v）", result.Processed, result.Failed, result.Pages)
	}
	if len(result.Pages) != 1 {
		t.Fatalf("Pages 数量 = %d, 期望 1", len(result.Pages))
	}
	p := result.Pages[0]
	if p.SourceRelPath != "cover.bmp" {
		t.Fatalf("SourceRelPath = %q, 期望 cover.bmp", p.SourceRelPath)
	}
	if p.TargetRelPath != "cover.webp" {
		t.Fatalf("TargetRelPath = %q, 期望 cover.webp", p.TargetRelPath)
	}
	if p.OutputSize <= 0 {
		t.Fatalf("OutputSize = %d, 期望 > 0", p.OutputSize)
	}
}

// 已存在最新产物的 skipped 结果也应携带源/目标相对路径，供 Java 侧读取真实大小置 READY。
func TestRunSkippedExistingReportsTargetRelPath(t *testing.T) {
	scanDir := t.TempDir()
	outDir := t.TempDir()
	src := filepath.Join(scanDir, "001.png")
	writeTestPNG(t, src)
	if err := os.WriteFile(filepath.Join(outDir, "001.webp"), []byte("existing lq"), 0o644); err != nil {
		t.Fatalf("预置已存在产物失败: %v", err)
	}

	result := run(newTestCfg(scanDir, outDir))

	if result.Skipped != 1 {
		t.Fatalf("Skipped = %d, 期望 1（结果=%+v）", result.Skipped, result.Pages)
	}
	if len(result.Pages) != 1 {
		t.Fatalf("Pages 数量 = %d, 期望 1", len(result.Pages))
	}
	p := result.Pages[0]
	if p.Status != "skipped" {
		t.Fatalf("status = %s, 期望 skipped", p.Status)
	}
	if p.SourceRelPath != "001.png" {
		t.Fatalf("SourceRelPath = %q, 期望 001.png", p.SourceRelPath)
	}
	if p.TargetRelPath != "001.webp" {
		t.Fatalf("TargetRelPath = %q, 期望 001.webp", p.TargetRelPath)
	}
}

// inferPageNumber 保留数字/非数字文件名推断能力（仅作展示用，Java 侧不据此匹配媒体）。
func TestInferPageNumber(t *testing.T) {
	cases := map[string]int64{
		"001":       1,
		"page_05":   5,
		"cover":     0,
		"ch01_p03":  1,
		"":          0,
	}
	for name, want := range cases {
		if got := inferPageNumber(name); got != want {
			t.Fatalf("inferPageNumber(%q) = %d, 期望 %d", name, got, want)
		}
	}
}
