# image-optimizer

ComicAtlas 专用图片压缩工具。HQ 图片按比例缩小到配置的最大长边后统一转换为 LQ WebP；JPEG 仅在 DCT 档位小于 `8/8`、能够真实减少像素时使用 libjpeg-turbo 预缩放。其 BMP 中间文件写入系统临时目录，避免与 HQ/LQ 存储盘争抢 I/O。

普通生成只检查并跳过有效且不早于 HQ 的 WebP。

## 编译

```bash
cd worker-service/tools/image-optimizer
go build -o image-optimizer.exe .
```

**注意**：本项目依赖 `github.com/chai2010/webp`，其底层使用 CGO 绑定 libwebp。Windows 编译需要 MinGW-w64 环境。

JPEG 低内存缩放需要 libjpeg-turbo 运行时。Docker 镜像内置 `libjpeg-turbo-progs`。Windows 本地开发执行 `pwsh -NoProfile -File scripts/dev/setup-image-optimizer.ps1`，工具会下载到项目 `.runtime` 目录；`scripts/dev/start-dev.ps1` 会自动传入 `IMAGE_DJPEG_PATH`，不会修改系统 `Path`。未安装时工具仍可回退到 Go 解码，但超大图片的峰值内存会明显增加。

## 使用

### Worker 调用模式（JSON 输出）

```bash
image-optimizer.exe \
  -scan-dir "D:/manga/hq/123/001" \
  -output-dir "D:/manga/lq/123/001" \
  -comic-id 123 \
  -chapter-id 456 \
  -chapter-no "001" \
  -quality 70 \
  -max-long-edge 3840 \
  -workers 4 \
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
| `-quality` | 否 | 70 | WebP 质量 1-100 |
| `-max-long-edge` | 否 | 3840 | LQ 最大长边，保持宽高比且不放大小图 |
| `-workers` | 否 | CPU核心数 | 并发数 |
| `-max-inflight-pixels` | 否 | 80000000 | 所有 worker 的解码像素预算 |
| `-force` | 否 | false | 强制重新处理 |
| `-quiet` | 否 | false | 安静模式 |
| `-json` | 否 | false | JSON 输出模式 |
| `-ext` | 否 | .jpg,.jpeg,.png,.webp,.gif | 支持的扩展名 |

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
    {"pageNumber": 2, "status": "processed", "inputSize": 45000000, "outputSize": 900000, "ratio": 2.0},
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
7. **超大 JPEG 低内存缩放**：通过 libjpeg-turbo DCT 缩放后再精确缩放和编码，避免完整展开原始像素
8. **统一 WebP 派生格式**：所有 LQ 输出均为缩放后的 WebP
