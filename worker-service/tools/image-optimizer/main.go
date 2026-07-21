package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	defaultQuality    = 15
	defaultExtensions = ".jpg,.jpeg,.png,.webp,.gif"
)

// CLIConfig 命令行配置
type CLIConfig struct {
	ComicID    int64
	ChapterID  int64
	ChapterNo  string
	ScanDir    string
	OutputDir  string
	Quality    int
	Workers    int
	Force      bool
	Quiet      bool
	JSON       bool
	Extensions map[string]bool
}

// PageResult 单页处理结果
type PageResult struct {
	PageNumber int64   `json:"pageNumber"`
	Status     string  `json:"status"`                // processed, skipped, failed
	InputSize  int64   `json:"inputSize,omitempty"`   // bytes
	OutputSize int64   `json:"outputSize,omitempty"`  // bytes
	Ratio      float64 `json:"ratio,omitempty"`       // output/input * 100
	Reason     string  `json:"reason,omitempty"`      // 失败/跳过原因
}

// RunResult 整章运行结果
type RunResult struct {
	ComicID   int64        `json:"comicId"`
	ChapterID int64        `json:"chapterId"`
	ChapterNo string       `json:"chapterNo"`
	ScanDir   string       `json:"scanDir"`
	OutputDir string       `json:"outputDir"`
	Total     int32        `json:"total"`
	Processed int32        `json:"processed"`
	Skipped   int32        `json:"skipped"`
	Failed    int32        `json:"failed"`
	Pages     []PageResult `json:"pages"`
	ElapsedMs int64        `json:"elapsedMs"`
	Success   bool         `json:"success"`

	mu sync.Mutex // 保护 Pages 并发追加
}

func main() {
	comicID := flag.Int64("comic-id", 0, "漫画 ID")
	chapterID := flag.Int64("chapter-id", 0, "章节 ID")
	chapterNo := flag.String("chapter-no", "", "章节编号")
	scanDir := flag.String("scan-dir", "", "扫描目录（HQ 图片所在目录）")
	outputDir := flag.String("output-dir", "", "输出目录（LQ 输出目录）")
	quality := flag.Int("quality", defaultQuality, "WebP 质量 (1-100)")
	workers := flag.Int("workers", 0, "并发数（默认 CPU 核心数）")
	force := flag.Bool("force", false, "强制重新处理")
	quiet := flag.Bool("quiet", false, "安静模式")
	jsonMode := flag.Bool("json", false, "JSON 输出模式")
	extensions := flag.String("ext", defaultExtensions, "支持的文件扩展名")
	flag.Parse()

	if *scanDir == "" {
		fmt.Fprintln(os.Stderr, "错误: -scan-dir 必须指定")
		os.Exit(2)
	}
	if *outputDir == "" {
		fmt.Fprintln(os.Stderr, "错误: -output-dir 必须指定")
		os.Exit(2)
	}
	if *workers == 0 {
		*workers = runtime.NumCPU()
	}

	cfg := &CLIConfig{
		ComicID:    *comicID,
		ChapterID:  *chapterID,
		ChapterNo:  *chapterNo,
		ScanDir:    *scanDir,
		OutputDir:  *outputDir,
		Quality:    *quality,
		Workers:    *workers,
		Force:      *force,
		Quiet:      *quiet,
		JSON:       *jsonMode,
		Extensions: parseExtensions(*extensions),
	}

	if _, err := os.Stat(cfg.ScanDir); os.IsNotExist(err) {
		fmt.Fprintf(os.Stderr, "错误: 扫描目录不存在: %s\n", cfg.ScanDir)
		os.Exit(2)
	}

	start := time.Now()
	result := run(cfg)
	result.ElapsedMs = time.Since(start).Milliseconds()
	result.ComicID = cfg.ComicID
	result.ChapterID = cfg.ChapterID
	result.ChapterNo = cfg.ChapterNo
	result.ScanDir = cfg.ScanDir
	result.OutputDir = cfg.OutputDir
	result.Success = result.Failed == 0

	if cfg.JSON {
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		if err := enc.Encode(result); err != nil {
			fmt.Fprintf(os.Stderr, "JSON 编码失败: %v\n", err)
			os.Exit(2)
		}
	} else if !cfg.Quiet {
		printTextResult(result)
	}

	if !result.Success {
		os.Exit(1)
	}
}

func parseExtensions(s string) map[string]bool {
	m := make(map[string]bool)
	for _, ext := range strings.Split(s, ",") {
		ext = strings.ToLower(strings.TrimSpace(ext))
		if ext != "" {
			m[ext] = true
		}
	}
	return m
}

func run(cfg *CLIConfig) *RunResult {
	result := &RunResult{Pages: make([]PageResult, 0)}
	tasks := make(chan imageTask, cfg.Workers*2)
	var wg sync.WaitGroup

	for i := 0; i < cfg.Workers; i++ {
		wg.Add(1)
		go worker(i, tasks, &wg, cfg, result)
	}

	_ = filepath.Walk(cfg.ScanDir, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			atomic.AddInt32(&result.Skipped, 1)
			return nil
		}
		if info.IsDir() {
			return nil
		}

		ext := strings.ToLower(filepath.Ext(path))
		if !cfg.Extensions[ext] {
			return nil
		}
		if info.Size() == 0 {
			atomic.AddInt32(&result.Skipped, 1)
			if !cfg.Quiet && !cfg.JSON {
				fmt.Printf("跳过: %s | 空文件\n", path)
			}
			return nil
		}

		relPath, err := filepath.Rel(cfg.ScanDir, path)
		if err != nil {
			return nil
		}

		baseName := strings.TrimSuffix(filepath.Base(relPath), filepath.Ext(relPath))
		pageNum := inferPageNumber(baseName)
		lqPath := filepath.Join(cfg.OutputDir, filepath.Dir(relPath), baseName+".webp")

		atomic.AddInt32(&result.Total, 1)

		if !cfg.Force {
			if lqInfo, err := os.Stat(lqPath); err == nil {
				if lqInfo.ModTime().Unix() >= info.ModTime().Unix() {
					atomic.AddInt32(&result.Skipped, 1)
					if !cfg.Quiet && !cfg.JSON {
						fmt.Printf("跳过: %s | 已存在最新版本 (%s)\n", relPath, formatSize(lqInfo.Size()))
					}
					result.mu.Lock()
					result.Pages = append(result.Pages, PageResult{
						PageNumber: pageNum,
						Status:     "skipped",
						Reason:     "exists",
					})
					result.mu.Unlock()
					return nil
				}
			}
		}

		tasks <- imageTask{
			HQPath:       path,
			LQPath:       lqPath,
			RelativePath: relPath,
			PageNumber:   pageNum,
		}
		return nil
	})

	close(tasks)
	wg.Wait()
	return result
}

type imageTask struct {
	HQPath       string
	LQPath       string
	RelativePath string
	PageNumber   int64
}

func worker(id int, tasks <-chan imageTask, wg *sync.WaitGroup, cfg *CLIConfig, result *RunResult) {
	defer wg.Done()
	for task := range tasks {
		optResult, err := optimizeImageToWebP(task.HQPath, task.LQPath, cfg.Quality)
		page := PageResult{PageNumber: task.PageNumber}
		if err != nil {
			atomic.AddInt32(&result.Failed, 1)
			page.Status = "failed"
			page.Reason = err.Error()
			if !cfg.Quiet && !cfg.JSON {
				fmt.Printf("[Worker %d] 失败: %s → %v\n", id, task.RelativePath, err)
			}
		} else {
			atomic.AddInt32(&result.Processed, 1)
			page.Status = "processed"
			page.InputSize = optResult.InputSize
			page.OutputSize = optResult.OutputSize
			if optResult.InputSize > 0 {
				page.Ratio = float64(optResult.OutputSize) / float64(optResult.InputSize) * 100
			}
			if !cfg.Quiet && !cfg.JSON {
				fmt.Printf("[Worker %d] 完成: %s | %s → %s (%.1f%%)\n",
					id, task.RelativePath,
					formatSize(optResult.InputSize),
					formatSize(optResult.OutputSize),
					page.Ratio)
			}
		}
		result.mu.Lock()
		result.Pages = append(result.Pages, page)
		result.mu.Unlock()
	}
}

// inferPageNumber 从文件名推断页码，如 "001.jpg" → 1, "page_05.png" → 5
func inferPageNumber(baseName string) int64 {
	numStr := ""
	for _, r := range baseName {
		if r >= '0' && r <= '9' {
			numStr += string(r)
		} else if numStr != "" {
			break
		}
	}
	if numStr != "" {
		if n, err := strconv.ParseInt(numStr, 10, 64); err == nil {
			return n
		}
	}
	return 0
}

func formatSize(bytes int64) string {
	const KB = 1024
	const MB = KB * 1024
	const GB = MB * 1024
	if bytes >= GB {
		return fmt.Sprintf("%.1fGB", float64(bytes)/float64(GB))
	}
	if bytes >= MB {
		return fmt.Sprintf("%.1fMB", float64(bytes)/float64(MB))
	}
	if bytes >= KB {
		return fmt.Sprintf("%.1fKB", float64(bytes)/float64(KB))
	}
	return fmt.Sprintf("%dB", bytes)
}

func printTextResult(r *RunResult) {
	fmt.Println(strings.Repeat("-", 50))
	fmt.Println("--- 图片优化结果 ---")
	fmt.Printf("漫画:     %d\n", r.ComicID)
	fmt.Printf("章节:     %d (%s)\n", r.ChapterID, r.ChapterNo)
	fmt.Printf("扫描目录: %s\n", r.ScanDir)
	fmt.Printf("输出目录: %s\n", r.OutputDir)
	fmt.Printf("总文件:   %d\n", r.Total)
	fmt.Printf("处理成功: %d\n", r.Processed)
	fmt.Printf("跳过文件: %d\n", r.Skipped)
	fmt.Printf("失败数量: %d\n", r.Failed)
	fmt.Printf("耗时:     %d ms\n", r.ElapsedMs)
	if r.Processed > 0 && r.ElapsedMs > 0 {
		fmt.Printf("平均速度: %.0f 页/秒\n", float64(r.Processed)/float64(r.ElapsedMs)*1000)
	}
	fmt.Println(strings.Repeat("-", 50))
}
