# worker/file 模块归位审视与 EhentaiDownloadService 归位设计

**日期**: 2026-08-07
**状态**: 已批准（最小归位方案）
**范围**: worker-service（`EhentaiDownloadService` 移包 + 同步 import）

## 1. 背景

对 `worker-service/.../worker/file` 模块（10 子包 + 1 根目录散落类，共 30 类）做完整归位审视，结论：

- **需归位（1 处）**：`EhentaiDownloadService` 裸露在 `file/` 根目录，而下载域 5 类全部在 `file/download/` 子包（`DownloadContext`/`HttpDownloader`/`ArchiveDownloader`/`TorrentDownloader`/`DownloadStrategy`）。它是之前 `FileService` 重构时的临时放置，职责（EHENTAI 下载 + 解压）与 `download/` 子包完全重合。
- **保持（单类子包，语义域清晰）**：
  - `file/transcode/VideoNormalizer`——导入链路视频标准化（被 `DirectoryImportHandler` 注入），与 event 按需转码场景不同
  - `file/trash/TrashManifestStore`——回收站文件子域（被 event 的 Trash/Restore/Purge 3 handler 注入）
  - `image/ImageOptimizer`——跨场景图片优化独立域
  - `common/ComicTitleSanitizer`——通用工具
- **已规整**：parse/storage/handler/download/extract/manifest 子包边界清晰、依赖方向正确。

## 2. 变更

### 2.1 `EhentaiDownloadService` → `file/download/`

- 文件：`worker-service/.../file/EhentaiDownloadService.java` → `worker-service/.../file/download/EhentaiDownloadService.java`
- package：`com.comicatlas.worker.file` → `com.comicatlas.worker.file.download`
- 类名、方法签名、依赖（`WorkerConfig`/`DownloadContext`/`ZipExtractor`）不变

### 2.2 同步 import

- `worker-service/.../event/ImportTaskHandler.java`：`import com.comicatlas.worker.file.EhentaiDownloadService` → `import com.comicatlas.worker.file.download.EhentaiDownloadService`

### 2.3 验证

- `.\mvnw -pl worker-service -am test -DfailIfNoTests=false` 全绿（基线 53）
- grep 确认无 `worker.file.EhentaiDownloadService` 残留

## 3. 排除项

- 不调整单类子包（transcode/trash）——语义域清晰，强合并不提升可读性。
- 不重命名类；不改任何业务逻辑。
