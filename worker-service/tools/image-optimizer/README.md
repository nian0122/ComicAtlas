# image-optimizer

ComicAtlas 专用图片压缩工具。将 HQ 图片转换为 LQ WebP 格式。

## 编译

```bash
cd worker-service/tools/image-optimizer
go build -o image-optimizer.exe .
```

**注意**：本项目依赖 `github.com/chai2010/webp`，其底层使用 CGO 绑定 libwebp。Windows 编译需要 MinGW-w64 环境。

## 使用

### Worker 调用模式（JSON 输出）

```bash
image-optimizer.exe \
  -scan-dir "D:/manga/hq/123/001" \
  -output-dir "D:/manga/lq/123/001" \
  -comic-id 123 \
  -chapter-id 456 \
  -chapter-no "001" \
  -quality 15 \
  -workers 8 \
  -json
```

### 手动调用模式（文本输出）

```bash
image-optimizer.exe \
  -scan-dir "D:/manga/hq/123/001" \
  -output-dir "D:/manga/lq/123/001" \
  -quality 80
```

## 参数

| 参数 | 必填 | 默认值 | 说明 |
|------|------|--------|------|
| `-scan-dir` | 是 | - | HQ 图片扫描目录 |
| `-output-dir` | 是 | - | LQ 输出目录 |
| `-comic-id` | 否 | 0 | 漫画 ID（JSON 输出用）|
| `-chapter-id` | 否 | 0 | 章节 ID（JSON 输出用）|
| `-chapter-no` | 否 | - | 章节编号（JSON 输出用）|
| `-quality` | 否 | 15 | WebP 质量 1-100 |
| `-workers` | 否 | CPU核心数 | 并发数 |
| `-force` | 否 | false | 强制重新处理 |
| `-quiet` | 否 | false | 安静模式 |
| `-json` | 否 | false | JSON 输出模式 |
| `-ext` | 否 | .jpg,.jpeg,.png,.webp,.gif,.bmp | 支持的扩展名 |

## JSON 输出格式

```json
{
  "comicId": 123,
  "chapterId": 456,
  "chapterNo": "001",
  "scanDir": "D:/manga/hq/123/001",
  "outputDir": "D:/manga/lq/123/001",
  "total": 20,
  "processed": 18,
  "skipped": 1,
  "failed": 1,
  "pages": [
    {"pageNumber": 1, "status": "processed", "inputSize": 2500000, "outputSize": 150000, "ratio": 6.0},
    {"pageNumber": 2, "status": "skipped", "reason": "exists"},
    {"pageNumber": 5, "status": "failed", "reason": "decode error"}
  ],
  "elapsedMs": 5230,
  "success": false
}
```

## 退出码

- `0`：全部成功
- `1`：部分失败（有 failed 页）
- `2`：参数错误或目录不存在

## 与原 Go 工具的改进

1. **结构化 JSON 输出**：新增 `-json` 标志，输出每页详细结果，供 Java Worker 解析
2. **单目录模式**：直接扫描 `-scan-dir` 目录，不再需要 `root+series` 组合
3. **章节元数据回传**：`comicId`、`chapterId`、`chapterNo` 写入 JSON，便于 Worker 闭环
4. **并发安全**：使用 `sync.Mutex` 保护结果数组，支持高并发处理
5. **扩展格式支持**：新增 `.webp`、`.gif` 输入解码（原工具不支持）
6. **退出码语义**：明确区分"全部成功/部分失败/参数错误"
