package main

import (
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
