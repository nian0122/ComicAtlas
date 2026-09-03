package main

import (
	"image"
	"image/jpeg"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestInferPageNumber(t *testing.T) {
	cases := []struct {
		name string
		want int64
	}{
		{"001", 1},
		{"page_05", 5},
		{"IMG_6513", 6513},
		{"123", 123},
		{"no-number", 0},
		{"", 0},
		{"12-34", 34},
		{"001-1", 1},
	}
	for _, c := range cases {
		got := inferPageNumber(c.name)
		if got != c.want {
			t.Errorf("inferPageNumber(%q) = %d, want %d", c.name, got, c.want)
		}
	}
}

func TestRun_existingLqReportsActualOutputSize(t *testing.T) {
	scanDir := t.TempDir()
	outputDir := t.TempDir()
	sourcePath := filepath.Join(scanDir, "001.jpg")
	outputPath := filepath.Join(outputDir, "001.webp")
	if err := os.WriteFile(sourcePath, []byte("source"), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(outputPath, []byte("existing-lq"), 0644); err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	if err := os.Chtimes(sourcePath, now.Add(-time.Minute), now.Add(-time.Minute)); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(outputPath, now, now); err != nil {
		t.Fatal(err)
	}

	result := run(&CLIConfig{
		ScanDir:           scanDir,
		OutputDir:         outputDir,
		Workers:           1,
		MaxLongEdge:       defaultMaxLongEdge,
		MaxInflightPixels: defaultMaxInflightPixels,
		Quiet:             true,
		Extensions:        parseExtensions(defaultExtensions),
	})

	if result.Processed != 0 || result.Skipped != 1 || len(result.Pages) != 1 {
		t.Fatalf("既有 LQ 应记录为单个跳过页: %+v", result)
	}
	if result.Pages[0].OutputSize != int64(len("existing-lq")) {
		t.Fatalf("既有 LQ 必须回传实际大小，得到 %d", result.Pages[0].OutputSize)
	}
}

func TestRun_legacyJpegLqIsReplacedWithWebP(t *testing.T) {
	scanDir := t.TempDir()
	outputDir := t.TempDir()
	sourcePath := filepath.Join(scanDir, "001.jpg")
	legacyLqPath := filepath.Join(outputDir, "001.jpg")
	webpLqPath := filepath.Join(outputDir, "001.webp")

	sourceFile, err := os.Create(sourcePath)
	if err != nil {
		t.Fatal(err)
	}
	if encodeErr := jpeg.Encode(sourceFile, image.NewRGBA(image.Rect(0, 0, 16, 16)),
		&jpeg.Options{Quality: 75}); encodeErr != nil {
		_ = sourceFile.Close()
		t.Fatal(encodeErr)
	}
	if closeErr := sourceFile.Close(); closeErr != nil {
		t.Fatal(closeErr)
	}
	if err := os.WriteFile(legacyLqPath, []byte("legacy-jpeg-lq"), 0644); err != nil {
		t.Fatal(err)
	}

	result := run(&CLIConfig{
		ScanDir:           scanDir,
		OutputDir:         outputDir,
		Quality:           defaultQuality,
		Workers:           1,
		MaxLongEdge:       defaultMaxLongEdge,
		MaxInflightPixels: defaultMaxInflightPixels,
		Quiet:             true,
		Extensions:        parseExtensions(defaultExtensions),
	})

	if result.Processed != 1 || result.Skipped != 0 || result.Failed != 0 {
		t.Fatalf("旧 LQ JPG 必须重新生成 WebP: %+v", result)
	}
	if _, err := os.Stat(webpLqPath); err != nil {
		t.Fatalf("必须生成 WebP LQ: %v", err)
	}
	if _, err := os.Stat(legacyLqPath); !os.IsNotExist(err) {
		t.Fatalf("WebP 生成成功后必须删除旧 LQ JPG，statErr=%v", err)
	}
}
